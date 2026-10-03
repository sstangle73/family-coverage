package com.storiedev.familycoverage

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Automatic test texts between this phone and its partner in the household (the full build only).
 *
 * After 10 minutes inside one of the household's places, or at a place's fallback time, this phone starts an
 * exchange: one test text from each chosen SIM to the partner's main number. The partner's phone answers each one on
 * the SIM it came in on, back to the line that sent it, so every line is tested both ways. The texts are silent data
 * SMS where both ends carry them, visible texts otherwise. Every sent, delivered and received text is a row in the
 * texts table. All state lives on the logger's handler thread.
 */
class AutoTexts(
    private val context: Context,
    private val handler: Handler,
    private val prefs: Prefs,
    private val sampler: TelephonySampler,
    private val tracker: LocationTracker,
    store: CsvStore,
    wifi: () -> Boolean,
    /** A call on either SIM: no test texts start then, and replies wait until it ends. */
    private val inCall: () -> Boolean,
) : TextTests {
    private class Pending(
        val key: String, val testId: String, val exchangeId: String?, val role: String, val trigger: String,
        val transport: String, val subId: Int, val sentAt: OffsetDateTime, val sentMs: Long, val snap: TextSnap,
        /** In memory only, for a visible resend; never written. */
        val dest: String,
    ) {
        var sentWritten = false
    }

    private val visits = PlaceVisits()
    private val log = TextLog(prefs, sampler, tracker, store, wifi, visits)
    private val pending = LinkedHashMap<String, Pending>()
    private val seen = ArrayDeque<String>()
    /** Tests that arrived during a call, answered once it ends (within 15 minutes). */
    private val deferred = ArrayDeque<TextBus.Received>()

    override fun start() {
        // The SMS receivers reach this tester through TextBus, on the logger's thread.
        TextBus.sink = { block ->
            handler.post {
                try {
                    block()
                } catch (e: Exception) {
                    Status.lastText = "test text failed: ${e.javaClass.simpleName}"
                }
            }
        }
        TextBus.applyReceivers(context, prefs, running = true)
    }

    override fun stop() {
        TextBus.sink = null
        runCatching { TextBus.applyReceivers(context, prefs, running = false) }
    }

    private fun permitted(): Boolean =
        context.checkSelfPermission(Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    private fun chosenSubs(): List<TelephonySampler.SubState> {
        val chosen = prefs.textSubs
        return sampler.subscriptions.filter { chosen == null || it.subId in chosen }
    }

    /** Whether this SIM can send silent texts: not on a network known to refuse them, and it hasn't refused one. */
    private fun simSilent(s: TelephonySampler.SubState): Boolean =
        s.subId !in prefs.textNoSilentSubs && (s.knownMccMnc ?: s.lastRow?.mccMnc) !in TextMath.NO_SILENT_PLMNS

    /** (transport, destination) for one SIM's test: see TextMath.route. */
    private fun route(s: TelephonySampler.SubState): Pair<String, String>? = TextMath.route(
        simSilent(s), prefs.textPartnerMain, prefs.textMainSilent, prefs.textPartnerSecond, prefs.textSecondSilent,
    )

    /** 8 exchanges a day, or 4 when each exchange would send more than one visible text. */
    private fun cap(): Int =
        if (chosenSubs().count { route(it)?.first == "text" } >= 2) Config.TEXT_DAY_MAX_VISIBLE else Config.TEXT_DAY_MAX

    /** Why this phone can't start test texts right now, or null when it can. */
    private fun blocker(): String? = when {
        !prefs.textsEnabled -> "off"
        log.partner() == null -> "choose who to test with"
        !permitted() -> "SMS permission missing"
        TextMath.number(prefs.textPartnerMain) == null -> "the partner's number isn't set"
        chosenSubs().isEmpty() -> "no SIM ticked"
        else -> null
    }

    private fun may(nowMs: Long, t: LocalTime, manual: Boolean): String? =
        TextMath.mayStart(nowMs, t, prefs.textExchanges, prefs.textLastExchangeMs, cap(), manual)

    override fun tick() {
        val nowMs = System.currentTimeMillis()
        val now = LocalDateTime.now()
        log.rollDay(now.toLocalDate().toString())
        visits.refresh(prefs.household, prefs.member)
        expirePending(nowMs)
        val fix = log.currentFix()
        val here = fix?.let { visits.placeAt(it) }
        visits.update(here, fix)
        showStatus()
        if (inCall()) return // never on a call: nothing starts, and waiting replies stay waiting
        flushDeferred()
        if (blocker() != null) return
        val v = visits.dwelled(Config.TEXT_DWELL_MS)
        if (v != null && visits.isMine(v.placeId) && v.exchanges < Config.TEXT_VISIT_MAX &&
            may(nowMs, now.toLocalTime(), manual = false) == null
        ) {
            v.exchanges++
            startExchange("place", v.placeId)
            return
        }
        for (p in visits.mine) for (time in p.times) {
            val slot = runCatching { LocalTime.parse(time) }.getOrNull() ?: continue
            val days = p.days.mapNotNull { TextMath.day(it) }.toSet()
            val key = "${now.toLocalDate()} ${p.id} $time"
            if (key in prefs.textSlotsDone || !TextMath.slotDue(now, days, slot)) continue
            if (nowMs - prefs.textLastExchangeMs < Config.TEXT_FALLBACK_GAP_MS) {
                prefs.textSlotsDone = prefs.textSlotsDone + key // a test went out in the last hour: not needed
            } else if (may(nowMs, now.toLocalTime(), manual = false) == null) {
                prefs.textSlotsDone = prefs.textSlotsDone + key
                startExchange("schedule", here?.id)
                return
            }
        }
    }

    override fun manual() {
        log.rollDay(LocalDate.now().toString())
        visits.refresh(prefs.household, prefs.member)
        val why = (if (inCall()) "on a call" else null) ?: blocker() ?: may(System.currentTimeMillis(), LocalTime.now(), manual = true)
        if (why != null) {
            Status.lastText = "${LoggerService.clock(OffsetDateTime.now())} not sent: $why"
            return
        }
        startExchange("manual", log.currentFix()?.let { visits.placeAt(it) }?.id)
    }

    private fun startExchange(trigger: String, placeId: String?) {
        val exchangeId = TextMath.newId()
        val at = OffsetDateTime.now()
        val nowMs = System.currentTimeMillis()
        prefs.textExchanges = prefs.textExchanges + 1
        prefs.textLastExchangeMs = nowMs
        val names = mutableListOf<String>()
        for (s in chosenSubs()) {
            val (transport, dest) = route(s) ?: continue
            val testId = TextMath.newId()
            val snap = log.snapshot(s.subId, placeId)
            send(Pending("test:$testId", testId, exchangeId, "test", trigger, transport, s.subId, at, nowMs, snap, dest),
                TextMath.tag("test", testId, at.toLocalTime()))
            names += (snap.carrier ?: "SIM ${s.subId}") + if (transport == "data") " (silent)" else " (visible)"
        }
        Status.lastText = "${LoggerService.clock(at)} tests sent from ${names.joinToString(" and ")} ($trigger" +
            (visits.name(placeId)?.let { ", $it" } ?: "") + ")"
        showStatus()
    }

    /** A test text or a reply from the partner's phone (already through TextBus's gate). */
    fun onReceived(r: TextBus.Received) {
        val peer = log.partner() ?: return
        if (!prefs.textsEnabled) return
        val key = "${r.role}:${r.testId}"
        if (key in seen) return // the network delivered it twice
        seen.addLast(key)
        while (seen.size > 100) seen.removeFirst()
        log.rollDay(r.at.toLocalDate().toString())
        visits.refresh(prefs.household, prefs.member)
        val snap = log.snapshot(r.subId, null)
        val mine = if (r.role == "echo") pending["test:${r.testId}"] else null
        val rtt = mine?.let { Csv.round((System.currentTimeMillis() - it.sentMs) / 1000.0, 1) }
        log.write(r.at, "received", r.testId, mine?.exchangeId, r.role, null, r.transport, r.subId, null,
            TextMath.latency(r.sentAt, r.at.toLocalTime()), rtt, snap, peer)
        if (r.role == "echo") {
            Status.lastText = "${LoggerService.clock(r.at)} reply arrived on ${snap.carrier ?: "?"}" + (rtt?.let { " after $it s" } ?: "")
            return
        }
        // A test from the partner: answer on the SIM it came in on, back to the line that sent it. Never during a
        // call: on a single-radio dual-SIM phone, sending on one SIM takes the radio from a call on the other.
        if (inCall()) {
            if (deferred.size < 20) deferred.addLast(r)
            Status.lastText = "${LoggerService.clock(r.at)} test arrived during a call; replying after it"
            return
        }
        reply(r, snap)
    }

    private fun reply(r: TextBus.Received, snap: TextSnap) {
        if (prefs.textEchoes >= Config.TEXT_ECHO_DAY_MAX || !permitted()) {
            Status.lastText = "${LoggerService.clock(r.at)} test arrived; no reply (" +
                (if (permitted()) "today's reply limit" else "SMS permission missing") + ")"
            return
        }
        prefs.textEchoes = prefs.textEchoes + 1
        val at = OffsetDateTime.now()
        val subId = if (r.subId >= 0) r.subId else SubscriptionManager.getDefaultSmsSubscriptionId()
        // The reply goes the way the test came: silent answers silent (both ends then carry them), visible answers
        // visible (a visible test means one end's network won't carry silent texts).
        send(Pending("echo:${r.testId}", r.testId, null, "echo", "reply", r.transport, subId, at, System.currentTimeMillis(), snap, r.from),
            TextMath.tag("echo", r.testId, at.toLocalTime()))
        Status.lastText = "${LoggerService.clock(at)} test arrived on ${snap.carrier ?: "?"}; replied"
        showStatus()
    }

    /** Replies held for a call, sent once it has ended; ones older than 15 minutes are dropped (the sender gave up). */
    private fun flushDeferred() {
        while (deferred.isNotEmpty()) {
            val r = deferred.removeFirst()
            if (Duration.between(r.at, OffsetDateTime.now()).toMinutes() > 15) continue
            reply(r, log.snapshot(r.subId, null))
        }
    }

    fun onSent(key: String, code: Int) {
        val p = pending[key] ?: return
        if (!p.sentWritten) writeSent(p, TextMath.sendResult(code))
        if (code != -1) {
            Status.lastText = "${LoggerService.clock(OffsetDateTime.now())} ${p.role} from ${p.snap.carrier ?: "?"}: " +
                TextMath.sendResult(code)
        }
        // The SIM's network refused a silent text while on a cell: it won't carry them. Remember that, and send this
        // one again as a visible text (a new test id, so the refused attempt stays its own row). Not on Wi-Fi calling
        // alone, where Android also says IN_SERVICE but a refusal says nothing about the network.
        if (p.transport == "data" && code in TextMath.SILENT_UNSUPPORTED && p.snap.cellService == "IN_SERVICE") {
            prefs.textNoSilentSubs = prefs.textNoSilentSubs + p.subId
            TextBus.applyReceivers(context, prefs, running = true)
            val at = OffsetDateTime.now()
            if (p.role == "test") {
                val s = sampler.subscriptions.firstOrNull { it.subId == p.subId } ?: return
                val (_, dest) = route(s) ?: return
                val testId = TextMath.newId()
                send(Pending("test:$testId", testId, p.exchangeId, "test", p.trigger, "text", p.subId, at,
                    System.currentTimeMillis(), log.snapshot(p.subId, p.snap.placeId), dest), TextMath.tag("test", testId, at.toLocalTime()))
            } else {
                send(Pending("echo:${p.testId}:text", p.testId, null, "echo", "reply", "text", p.subId, at,
                    System.currentTimeMillis(), p.snap, p.dest), TextMath.tag("echo", p.testId, at.toLocalTime()))
            }
            Status.lastText = "${LoggerService.clock(at)} ${p.snap.carrier ?: "this SIM"} can't send silent texts: resent visible"
        }
    }

    fun onDelivered(key: String, status: Int?) {
        val p = pending[key] ?: return
        val peer = log.partner() ?: return
        log.write(OffsetDateTime.now(), "delivered", p.testId, p.exchangeId, p.role, p.trigger, p.transport, p.subId,
            TextMath.deliveryStatus(status), Csv.round((System.currentTimeMillis() - p.sentMs) / 1000.0, 1), null,
            p.snap, peer, state = false)
    }

    private fun send(p: Pending, body: String) {
        pending[p.key] = p
        try {
            val sms = context.getSystemService(SmsManager::class.java).createForSubscriptionId(p.subId)
            val sent = result(SmsResultReceiver.ACTION_SENT, p.key)
            val delivered = result(SmsResultReceiver.ACTION_DELIVERED, p.key)
            if (p.transport == "data") {
                sms.sendDataMessage(p.dest, null, TextMath.PORT, body.toByteArray(Charsets.US_ASCII), sent, delivered)
            } else {
                sms.sendTextMessage(p.dest, null, body, sent, delivered)
            }
        } catch (e: Exception) {
            // Android refused it before any network was involved (permission, number): not a coverage result.
            writeSent(p, "APP_ERROR")
            Status.lastText = "${LoggerService.clock(OffsetDateTime.now())} couldn't send: ${e.javaClass.simpleName}"
        }
    }

    private fun result(action: String, key: String): PendingIntent = PendingIntent.getBroadcast(
        context, (action + key).hashCode(),
        Intent(context, SmsResultReceiver::class.java).setAction(action)
            .setData(Uri.parse("familycoverage://text/$key")) // one PendingIntent per text, whatever the request code
            .putExtra(SmsResultReceiver.EXTRA_KEY, key),
        // Mutable: Android adds the delivery report's PDU to the delivered broadcast.
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_ONE_SHOT,
    )

    private fun writeSent(p: Pending, result: String) {
        p.sentWritten = true
        val peer = log.partner() ?: return
        log.write(p.sentAt, "sent", p.testId, p.exchangeId, p.role, p.trigger, p.transport, p.subId, result, null, null,
            p.snap, peer)
    }

    /** A sent text Android never reported on gets its row anyway; old entries go after two hours. */
    private fun expirePending(nowMs: Long) {
        for (p in pending.values) if (!p.sentWritten && nowMs - p.sentMs > 120_000L) writeSent(p, "NO_RESULT")
        pending.values.removeAll { nowMs - it.sentMs > 2 * 3_600_000L }
    }

    private fun showStatus() {
        val why = blocker()
        val at = visits.visit?.let { v -> " At ${visits.name(v.placeId) ?: v.placeId}." } ?: ""
        val routes = chosenSubs().map { s -> (s.lastRow?.carrier ?: "SIM ${s.subId}") to route(s)?.first }
        // Visible replies come back to a SIM that sends visible tests: the plain-text receiver must be on for them.
        val ownVisible = routes.any { it.second == "text" }
        if (ownVisible != prefs.textOwnVisible) {
            prefs.textOwnVisible = ownVisible
            TextBus.applyReceivers(context, prefs, running = true)
        }
        val lines = routes.joinToString(", ") { (name, t) -> "$name ${if (t == "data") "silent" else if (t == "text") "visible" else "-"}" }
        Status.textsToday = if (why != null && why != "no SIM ticked") {
            why
        } else {
            "${prefs.textExchanges} of ${cap()} exchanges and ${prefs.textEchoes} replies today; ${visits.places.size} places. " +
                "$lines.$at" + (why?.let { " ($it)" } ?: "")
        }
    }
}
