package com.storiedev.familycoverage

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The logger: a foreground service of type "location" (no daily time cap, may start at boot).
 *
 * One scheduler, tick(), runs every job when it's due (Cadence). While moving it ticks every 10 s under a partial
 * wake lock, as GPS keeps the phone busy then anyway. While still the phone sleeps between ticks: an idle-safe alarm
 * wakes it about every 2 minutes, and short wake locks cover each tick's burst and each network job. Nothing
 * measures during a call. Tests, checks and uploads run on a second thread, one at a time, so a slow network never
 * delays a sample and no two measurements overlap.
 */
class LoggerService : Service() {
    private lateinit var prefs: Prefs
    private lateinit var store: CsvStore
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var sampler: TelephonySampler
    private lateinit var tracker: LocationTracker
    private lateinit var usage: UsageMeter
    private lateinit var texts: TextTests
    private lateinit var cm: ConnectivityManager
    private val net: ExecutorService = Executors.newSingleThreadExecutor()
    /** Held while moving; released while still. */
    private var wakeLock: PowerManager.WakeLock? = null
    /** A still-mode tick's burst: the modem's fresh cell list and the writes (auto-releases after 20 s). */
    private var tickLock: PowerManager.WakeLock? = null
    /** Network jobs, from queueing to the last one's end (auto-releases after 3 minutes). */
    private var netLock: PowerManager.WakeLock? = null
    private val netPending = AtomicInteger(0)
    /** When each job is next due (elapsedRealtime). */
    private val nextDue = HashMap<String, Long>()
    private val alarms by lazy { getSystemService(AlarmManager::class.java) }
    private val tickIntent by lazy {
        PendingIntent.getBroadcast(this, 7, Intent(this, TickReceiver::class.java), PendingIntent.FLAG_IMMUTABLE)
    }
    private val wifiNetworks: MutableSet<Network> = ConcurrentHashMap.newKeySet()
    private val checkQueued = AtomicBoolean(false)
    /** This app's byte counters after its last network job (network thread only). */
    private var uidMark: LongArray? = null
    private var nextTick = 0L
    private var ended = false
    // Today's mobile data per SIM, for the status screen (handler thread): name -> [bytes, the app's own bytes].
    private val usageToday = LinkedHashMap<String, LongArray>()
    private var usageDay: LocalDate? = null
    private var usageSince = ""

    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            wifiNetworks.add(network)
        }

        override fun onLost(network: Network) {
            wifiNetworks.remove(network)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        store = CsvStore.of(this)
        if (prefs.household == null || prefs.member == null || !goForeground()) {
            stopSelf()
            return
        }
        thread = HandlerThread("logger").also { it.start() }
        handler = Handler(thread.looper)
        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "familycoverage:moving").apply { setReferenceCounted(false) }
        tickLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "familycoverage:tick").apply { setReferenceCounted(false) }
        netLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "familycoverage:net").apply { setReferenceCounted(false) }
        wakeLock?.acquire() // the tracker starts in moving mode
        cm = getSystemService(ConnectivityManager::class.java)
        cm.registerNetworkCallback(
            NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
            wifiCallback,
        )
        sampler = TelephonySampler(this, handler)
        tracker = LocationTracker(this, handler) { writeTrack(it) }
        usage = UsageMeter(this, handler)
        usageSince = clock(OffsetDateTime.now()).take(5)
        texts = Flavor.texts(this, handler, prefs, sampler, tracker, store, { wifiNetworks.isNotEmpty() }, { inCall() })
        tracker.onMovingChanged = { moving -> onMovingChanged(moving) }
        // The still-mode alarm reaches the scheduler through this.
        TickBus.sink = {
            tickLock?.acquire(20_000L)
            handler.post(tickRunnable)
        }
        handler.post {
            sampler.start()
            tracker.start()
            usage.start(sampler.dataSubId)
            texts.start()
            // A data SIM change ends the usage interval at once, so each row belongs to one SIM.
            sampler.onDataSubChanged = { if (usage.due(sampler.dataSubId)) writeUsage() }
            Status.running = true
            Status.note = ""
            heartbeat("RUNNING")
            val now = SystemClock.elapsedRealtime()
            nextDue["heartbeat"] = now + Cadence.HEARTBEAT_MS
            nextDue["check"] = now + Cadence.FIRST_CHECK_MS
            nextDue["test"] = now + Cadence.FIRST_TEST_MS
            nextDue["server"] = now + Cadence.FIRST_SERVER_MS
            nextDue["upload"] = now + Cadence.FIRST_UPLOAD_MS
            nextDue["text"] = now + 60_000L
            nextTick = SystemClock.uptimeMillis()
            tick()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.loggingEnabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPLOAD -> if (::handler.isInitialized) netJob { upload() }
            ACTION_TEXT_NOW -> if (::handler.isInitialized) handler.post { texts.manual() }
            ACTION_TEXT_COMPOSED -> if (::handler.isInitialized) {
                val testId = intent.getStringExtra(EXTRA_TEST_ID)
                val sentAt = intent.getStringExtra(EXTRA_SENT_AT)?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
                val trigger = intent.getStringExtra(EXTRA_TRIGGER) ?: "manual"
                if (testId != null && sentAt != null) {
                    handler.post { texts.composed(testId, trigger, intent.getStringExtra(EXTRA_PLACE), sentAt) }
                }
            }
            ACTION_EVENT -> if (::handler.isInitialized) {
                val kind = intent.getStringExtra(EXTRA_KIND)
                val outcome = intent.getStringExtra(EXTRA_OUTCOME)
                val target = intent.getIntExtra(EXTRA_SUB, -1)
                if (kind != null && kind in EVENT_KINDS && (kind == "UNDO" || outcome in EVENT_OUTCOMES)) {
                    handler.post { writeEvent(kind, outcome, target) }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        TickBus.sink = null
        runCatching { alarms.cancel(tickIntent) }
        if (::handler.isInitialized) {
            handler.removeCallbacksAndMessages(null)
            handler.post {
                if (!ended) heartbeat("PAUSED")
                writeUsage() // the part-interval, so a stop loses no bytes
                texts.stop()
                usage.stop()
                sampler.stop()
                tracker.stop()
            }
            thread.quitSafely()
            runCatching { cm.unregisterNetworkCallback(wifiCallback) }
        }
        net.shutdown()
        for (lock in listOf(wakeLock, tickLock, netLock)) lock?.let { if (it.isHeld) it.release() }
        Status.running = false
        super.onDestroy()
    }

    private fun goForeground(): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while Family Coverage is recording"
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, LoggerService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val end = prefs.household?.end?.format(DateTimeFormatter.ofPattern("d MMM yyyy")) ?: "-"
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Family Coverage is recording")
            .setContentText("Signal, location and tests, until $end")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .build()
        return try {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            true
        } catch (e: Exception) {
            // Missing location permission, or a background start Android refused.
            Status.note = "could not start: ${e.javaClass.simpleName}"
            false
        }
    }

    private val tickRunnable = Runnable { tick() }

    /** The scheduler: runs whatever is due, then arranges the next tick (10 s moving, an alarm ~2 min still). */
    private fun tick() {
        val household = prefs.household
        if (household == null || Config.ended(household.end)) {
            endLogging(household)
            return
        }
        val moving = tracker.isMoving
        if (!moving) tickLock?.acquire(20_000L) // the burst below: 3 s for the modem, then the writes
        val now = SystemClock.elapsedRealtime()
        val wifi = wifiNetworks.isNotEmpty()
        if (due("sample", now)) {
            nextDue["sample"] = now + Cadence.sampleMs(moving)
            sampler.requestUpdates()
            handler.postDelayed({ writeSamples() }, Config.CELL_WAIT_MS)
        }
        if (usage.due(sampler.dataSubId)) writeUsage()
        if (due("heartbeat", now)) {
            nextDue["heartbeat"] = now + Cadence.HEARTBEAT_MS
            heartbeat("RUNNING")
        }
        if (due("text", now)) {
            nextDue["text"] = now + Cadence.textMs(moving)
            try {
                texts.tick()
            } catch (e: Exception) {
                Status.lastText = "test texts failed: ${e.javaClass.simpleName}"
            }
        }
        // Nothing measures during a call: a skipped job stays due and runs at the first tick after it.
        if (!inCall()) {
            if (prefs.dataChecks && !wifi && due("check", now) && checkQueued.compareAndSet(false, true)) {
                nextDue["check"] = now + Cadence.checkMs(moving)
                val row = sampler.subscriptions.firstOrNull { it.subId == sampler.dataSubId }?.lastRow
                netJob { runCheck(row) }
            }
            if (prefs.speedTests && due("test", now)) {
                nextDue["test"] = now + Cadence.testMs(moving)
                if (!Cadence.skipSpeedTest(wifi, vpnActive())) {
                    val rows = sampler.subscriptions.mapNotNull { it.lastRow }
                    netJob { runTest(rows) }
                }
            }
            val server = household.server
            if (server != null && !wifi && due("server", now)) {
                nextDue["server"] = now + Cadence.serverMs(moving)
                val rows = sampler.subscriptions.mapNotNull { it.lastRow }
                netJob { runServerTest(server, rows) }
            }
        }
        if (household.server != null && due("upload", now)) {
            nextDue["upload"] = now + Cadence.uploadMs(wifi)
            netJob { upload() }
        }
        schedule(moving)
    }

    /** Due, give or take a second, so a 10 s tick never just misses a 10 s job. */
    private fun due(job: String, now: Long): Boolean = now + 1_000L >= (nextDue[job] ?: 0L)

    private fun schedule(moving: Boolean) {
        handler.removeCallbacks(tickRunnable)
        if (moving) {
            runCatching { alarms.cancel(tickIntent) }
            if (wakeLock?.isHeld != true) wakeLock?.acquire()
            nextTick += Cadence.tickMs(true)
            val up = SystemClock.uptimeMillis()
            if (nextTick < up) nextTick = up + Cadence.tickMs(true)
            handler.postAtTime(tickRunnable, nextTick)
        } else {
            // Still: let the phone sleep. The alarm wakes it (Android may stretch it while deeply idle); the handler
            // post covers the times it's awake anyway. Whichever comes first ticks; due() stops double work.
            val delay = Cadence.tickMs(false)
            runCatching {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delay, tickIntent)
            }
            handler.postDelayed(tickRunnable, delay)
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        Status.power = if (moving) "moving: sampling every 10 s, GPS on" else "still: sleeping, sampling about every 2 min"
    }

    /** From the tracker (handler thread): back to the 10 s pace at once, or down to the still pace. */
    private fun onMovingChanged(moving: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (moving) {
            wakeLock?.acquire()
            nextDue["sample"] = now
            nextDue["text"] = now
            for ((job, interval) in listOf("check" to Cadence.checkMs(true), "test" to Cadence.testMs(true))) {
                if ((nextDue[job] ?: 0L) > now + interval) nextDue[job] = now + interval
            }
            nextTick = SystemClock.uptimeMillis()
        }
        tick()
    }

    /** A network job on the network thread, with the phone kept awake from queueing to the last job's end. */
    private fun netJob(job: () -> Unit) {
        netPending.incrementAndGet()
        netLock?.acquire(180_000L)
        net.execute {
            try {
                job()
            } finally {
                if (netPending.decrementAndGet() == 0) netLock?.let { if (it.isHeld) it.release() }
            }
        }
    }

    private fun inCall(): Boolean = try {
        getSystemService(TelecomManager::class.java)?.isInCall == true
    } catch (e: SecurityException) {
        false
    }

    private fun vpnActive(): Boolean =
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    private fun writeSamples() {
        val member = prefs.member ?: return
        try {
            val t = OffsetDateTime.now()
            val fix = tracker.rowFix()
            val wifi = wifiNetworks.isNotEmpty()
            val lines = mutableListOf<String>()
            val choices = mutableListOf<Pair<Int, String>>()
            for (s in sampler.subscriptions) {
                val r = sampler.read(s)
                store.append("samples", t, TelephonySampler.values(t, member, r, wifi, fix, tracker.isMoving))
                val data = if (r.dataSim) " (data)" else ""
                val icon = r.displayOverride?.takeIf { it != "NONE" }?.let { " [$it]" } ?: ""
                lines += "${r.carrier ?: "SIM ${r.subId}"}$data: ${r.rat}$icon ${r.rsrp?.let { "$it dBm" } ?: "-"} " +
                    "${r.cellService} voice ${r.voiceTransport}"
                choices += r.subId to r.displayName
            }
            Status.sims = if (lines.isEmpty()) "no active SIM (phone permission?)" else lines.joinToString("\n")
            Status.simChoices = choices
            Status.lastSample = clock(t)
            Status.location = fix?.let {
                "${tracker.mode}, ±${it.accuracy.toInt()} m, ${LocationTracker.fixAgeS(it).toInt()} s old"
            } ?: "${tracker.mode}, no fix yet"
        } catch (e: Exception) {
            Status.note = "sample failed: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            // The still-mode burst is over: let the phone sleep (network jobs hold their own lock).
            if (!tracker.isMoving) tickLock?.let { if (it.isHeld) it.release() }
        }
    }

    private fun writeTrack(loc: Location) {
        val member = prefs.member ?: return
        try {
            val t = Instant.ofEpochMilli(loc.time).atZone(ZoneId.systemDefault()).toOffsetDateTime()
            store.append(
                "track", t, listOf(
                    Csv.ts(t), member, Csv.round(loc.latitude, 7), Csv.round(loc.longitude, 7),
                    if (loc.hasAccuracy()) Csv.round(loc.accuracy.toDouble(), 1) else null,
                    if (loc.hasAltitude()) Csv.round(loc.altitude, 1) else null,
                    if (loc.hasSpeed()) Csv.round(loc.speed.toDouble(), 2) else null,
                    if (loc.hasBearing()) Csv.round(loc.bearing.toDouble(), 1) else null,
                    loc.provider,
                ),
            )
        } catch (e: Exception) {
            Status.note = "track failed: ${e.javaClass.simpleName}"
        }
    }

    /** Ends the usage interval and writes its row, for the SIM that carried data during it (handler thread). */
    private fun writeUsage() {
        val member = prefs.member ?: return
        if (!usage.started) return
        try {
            val t = OffsetDateTime.now()
            val iv = usage.take(sampler.dataSubId)
            val sub = sampler.subscriptions.firstOrNull { it.subId == iv.subId }?.lastRow
            store.append(
                "usage", t, listOf(
                    Csv.ts(t), member, Csv.round(iv.seconds, 1), iv.subId.takeIf { it >= 0 }, sub?.subLabel,
                    sub?.carrier, sub?.mccMnc, wifiNetworks.isNotEmpty(), iv.rx, iv.tx, iv.appRx, iv.appTx,
                    iv.allRx, iv.allTx,
                ),
            )
            tallyUsage(t, sub?.displayName ?: if (iv.subId >= 0) "SIM ${iv.subId}" else null, iv)
        } catch (e: Exception) {
            Status.note = "usage failed: ${e.javaClass.simpleName}"
        }
    }

    private fun tallyUsage(t: OffsetDateTime, name: String?, iv: UsageMeter.Interval) {
        if (usageDay != t.toLocalDate()) {
            if (usageDay != null) usageSince = "00:00"
            usageDay = t.toLocalDate()
            usageToday.clear()
        }
        if (name != null) {
            val a = usageToday.getOrPut(name) { LongArray(2) }
            a[0] += iv.rx + iv.tx
            a[1] += iv.appRx + iv.appTx
        }
        Status.usage = if (usageToday.isEmpty()) {
            "none since $usageSince"
        } else {
            usageToday.entries.joinToString(", ") { (n, v) -> "$n ${mb(v[0])} (app ${mb(v[1])})" } + " since $usageSince"
        }
    }

    private fun heartbeat(state: String) {
        val member = prefs.member ?: return
        try {
            val bm = getSystemService(BatteryManager::class.java)
            val t = OffsetDateTime.now()
            store.append(
                "heartbeat", t, listOf(
                    Csv.ts(t), member, state, bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
                    bm.isCharging, BuildConfig.VERSION_NAME,
                ),
            )
        } catch (e: Exception) {
            Status.note = "heartbeat failed: ${e.javaClass.simpleName}"
        }
    }

    /** A call or text test the owner tapped: one row per SIM, with each SIM's state right now (handler thread). */
    private fun writeEvent(kind: String, outcome: String?, target: Int) {
        val member = prefs.member ?: return
        try {
            val t = OffsetDateTime.now()
            if (kind == "UNDO") {
                store.append("events", t, Tables.EVENTS.map { col ->
                    when (col) {
                        "ts" -> Csv.ts(t)
                        "member" -> member
                        "kind" -> kind
                        else -> null
                    }
                })
                Status.lastEvent = "${clock(t)} undone: the tap before this one won't count."
            } else {
                val fix = tracker.rowFix()
                val wifi = wifiNetworks.isNotEmpty()
                val rows = sampler.subscriptions.map { sampler.read(it) }
                for (r in rows.ifEmpty { listOf(null) }) {
                    store.append(
                        "events", t, listOf(
                            Csv.ts(t), member, kind, outcome, target.takeIf { it >= 0 }, r?.subId, r?.subLabel,
                            r?.carrier, r?.mccMnc, r?.dataSim, r?.serviceState, r?.voiceTransport, r?.rat, r?.rsrp,
                            wifi, fix?.let { Csv.round(it.latitude, 7) }, fix?.let { Csv.round(it.longitude, 7) },
                            fix?.takeIf { it.hasAccuracy() }?.let { Csv.round(it.accuracy.toDouble(), 1) },
                        ),
                    )
                }
                val name = rows.firstOrNull { it.subId == target }?.displayName ?: "SIM $target"
                val what = when (kind to outcome) {
                    "CALL_IN" to "REACHED" -> "call to $name rang"
                    "CALL_IN" to "MISSED" -> "call to $name didn't ring"
                    "TEXT_IN" to "REACHED" -> "text to $name arrived"
                    else -> "text to $name didn't arrive"
                }
                Status.lastEvent = "${clock(t)} recorded: $what."
            }
            Status.lastEventAtMs = System.currentTimeMillis()
        } catch (e: Exception) {
            Status.lastEvent = "Not recorded: ${e.javaClass.simpleName}"
        }
    }

    /**
     * Runs one network job (network thread) and, when it went over cellular, adds its bytes to the usage row's app
     * share. Jobs never overlap, so this app's counters moved only for this job since the last one ended.
     */
    private fun <T> metered(cellular: Boolean, job: () -> T): T {
        val before = uidMark ?: uidBytes()
        try {
            return job()
        } finally {
            val after = uidBytes()
            uidMark = after
            if (cellular) usage.addApp(after[0] - before[0], after[1] - before[1])
        }
    }

    private fun uidBytes(): LongArray {
        val uid = Process.myUid()
        return longArrayOf(
            TrafficStats.getUidRxBytes(uid).coerceAtLeast(0), TrafficStats.getUidTxBytes(uid).coerceAtLeast(0),
        )
    }

    private fun runCheck(sub: TelephonySampler.Row?) {
        try {
            val member = prefs.member ?: return
            val t = OffsetDateTime.now()
            val r = metered(cellular = true) { DataCheck(this).run(usage.cellularUp) }
            val fix = tracker.rowFix()
            store.append(
                "checks", t, listOf(
                    Csv.ts(t), member, sub?.subId ?: SubscriptionManager.getActiveDataSubscriptionId().takeIf { it >= 0 },
                    sub?.subLabel, sub?.carrier, sub?.mccMnc, sub?.serviceState, sub?.rat, sub?.rsrp,
                    fix?.let { Csv.round(it.latitude, 7) }, fix?.let { Csv.round(it.longitude, 7) },
                    fix?.takeIf { it.hasAccuracy() }?.let { Csv.round(it.accuracy.toDouble(), 1) },
                    r.vpnActive, r.testPath, r.connectMs, r.totalMs, r.colo, r.result,
                ),
            )
            Status.lastCheck = "${clock(t)} ${r.result}${r.connectMs?.let { ", $it ms" } ?: ""} " +
                "(${sub?.carrier ?: "?"})" + (r.error?.let { "\n  $it" } ?: "")
        } catch (e: Exception) {
            Status.lastCheck = "check failed: ${e.javaClass.simpleName}"
        } finally {
            checkQueued.set(false)
        }
    }

    private fun runTest(rows: List<TelephonySampler.Row>) {
        val member = prefs.member ?: return
        try {
            val t = OffsetDateTime.now()
            val dataSub = SubscriptionManager.getActiveDataSubscriptionId()
            val sub = rows.firstOrNull { it.subId == dataSub }
            val wifi = wifiNetworks.isNotEmpty()
            // Always cellular: bound to it, or (inside a VPN, off Wi-Fi) the default path, which is the carrier.
            val r = metered(cellular = true) { SpeedTester(this).run(wifi) }
            val fix = tracker.rowFix()
            store.append(
                "tests", t, listOf(
                    Csv.ts(t), member, sub?.subId ?: dataSub.takeIf { it >= 0 }, sub?.subLabel, sub?.carrier,
                    sub?.mccMnc, sub?.rat, wifi,
                    fix?.let { Csv.round(it.latitude, 7) }, fix?.let { Csv.round(it.longitude, 7) },
                    fix?.takeIf { it.hasAccuracy() }?.let { Csv.round(it.accuracy.toDouble(), 1) },
                    r.latencyMs, r.jitterMs, r.downMbps, r.upMbps, r.downBytes, r.upBytes,
                    SpeedTester.SERVER, r.colo, r.asn, r.org, r.vpnActive, r.result, r.testPath, r.cellIpv6,
                ),
            )
            Status.lastTest = "${clock(t)} ${r.result} via ${r.testPath ?: "-"}: ${r.downMbps ?: "-"} down, " +
                "${r.upMbps ?: "-"} up Mbps, ${r.latencyMs ?: "-"} ms (${sub?.carrier ?: "?"}" +
                "${if (r.vpnActive) ", VPN" else ""})" + (r.error?.let { "\n  $it" } ?: "") +
                (if (r.result == "VPN_BLOCKED") "\n  On Wi-Fi inside a VPN: carrier tests resume off Wi-Fi." else "")
        } catch (e: Exception) {
            Status.lastTest = "test failed: ${e.javaClass.simpleName}"
        }
    }

    private fun runServerTest(server: String, rows: List<TelephonySampler.Row>) {
        val member = prefs.member ?: return
        try {
            val t = OffsetDateTime.now()
            val dataSub = SubscriptionManager.getActiveDataSubscriptionId()
            val sub = rows.firstOrNull { it.subId == dataSub }
            val wifi = wifiNetworks.isNotEmpty()
            val r = metered(cellular = !wifi) { ServerTester(this, prefs).run(server) }
            val fix = tracker.rowFix()
            store.append(
                "server", t, listOf(
                    Csv.ts(t), member, sub?.subId ?: dataSub.takeIf { it >= 0 }, sub?.subLabel, sub?.carrier,
                    sub?.mccMnc, sub?.rat, wifi, r.vpnActive, r.cellIpv6,
                    fix?.let { Csv.round(it.latitude, 7) }, fix?.let { Csv.round(it.longitude, 7) },
                    fix?.takeIf { it.hasAccuracy() }?.let { Csv.round(it.accuracy.toDouble(), 1) },
                    r.latencyMs, r.jitterMs, r.downMbps, r.upMbps, r.downBytes, r.upBytes, r.result,
                    r.start?.path, r.end?.path, r.end?.derpRegion ?: r.start?.derpRegion,
                    r.end?.directFamily ?: r.start?.directFamily, r.end?.directLan ?: r.start?.directLan,
                ),
            )
            val route = r.start?.path?.takeIf { it != "not_tailscale" }?.let { ", Tailscale $it -> ${r.end?.path ?: "-"}" } ?: ""
            Status.lastServer = "${clock(t)} ${r.result}: ${r.downMbps ?: "-"} down, ${r.upMbps ?: "-"} up Mbps, " +
                "${r.latencyMs ?: "-"} ms$route" + (r.error?.let { "\n  $it" } ?: "")
        } catch (e: Exception) {
            Status.lastServer = "server test failed: ${e.javaClass.simpleName}"
        }
    }

    private fun upload() {
        val result = runCatching {
            metered(cellular = wifiNetworks.isEmpty()) { Uploader(prefs, store).sync() }
        }.getOrElse { "upload failed: ${it.javaClass.simpleName}" }
        Status.lastUpload = "${clock(OffsetDateTime.now())} $result"
    }

    /** The end date has passed (or the household was removed): a last heartbeat and upload, then stop for good. */
    private fun endLogging(household: Household?) {
        if (ended) return
        ended = true
        heartbeat("ENDED")
        prefs.loggingEnabled = false
        netJob {
            if (household?.server != null) upload()
            handler.post { stopSelf() }
        }
        Status.note = household?.let { "The household's end date (${it.end}) has passed. Recording has stopped." }
            ?: "Not set up. Recording has stopped."
    }

    companion object {
        const val ACTION_STOP = "com.storiedev.familycoverage.STOP"
        const val ACTION_UPLOAD = "com.storiedev.familycoverage.UPLOAD"
        const val ACTION_EVENT = "com.storiedev.familycoverage.EVENT"
        const val ACTION_TEXT_NOW = "com.storiedev.familycoverage.TEXT_NOW"
        const val ACTION_TEXT_COMPOSED = "com.storiedev.familycoverage.TEXT_COMPOSED"
        const val EXTRA_KIND = "kind"
        const val EXTRA_OUTCOME = "outcome"
        const val EXTRA_SUB = "sub"
        const val EXTRA_TEST_ID = "test_id"
        const val EXTRA_SENT_AT = "sent_at"
        const val EXTRA_TRIGGER = "trigger"
        const val EXTRA_PLACE = "place"
        val EVENT_KINDS = setOf("CALL_IN", "TEXT_IN", "UNDO")
        val EVENT_OUTCOMES = setOf("REACHED", "MISSED")
        private const val CHANNEL = "logging"
        private const val NOTIFICATION_ID = 1

        fun clock(t: OffsetDateTime): String = t.format(DateTimeFormatter.ofPattern("HH:mm:ss"))

        private fun mb(bytes: Long): String = String.format(Locale.US, "%.1f MB", bytes / 1e6)
    }
}
