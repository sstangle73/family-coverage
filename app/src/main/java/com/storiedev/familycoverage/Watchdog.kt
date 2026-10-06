package com.storiedev.familycoverage

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The watchdog's job: about every 30 minutes while recording should be on, kept across reboots (Watchdog). */
class WatchdogJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Watchdog.check(this)
        return false // done: nothing carries on in the background
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}

/**
 * Says when recording stops by itself, and how to fix it. Taking a permission back in Android's settings kills the
 * app at once; Android then restarts the logger, which finds the permission missing and says so (LoggerService). But
 * Android may not restart it, or may refuse to from the background. So while recording should be on, a job checks
 * about every half hour, in the app's own process, that the logger runs. If it doesn't, the job starts it again when
 * everything it needs is there, and otherwise a notification says why and what to tap, at most once a day for each
 * reason (StopCheck). Boot runs the same check. The end date has a notice of its own, once.
 */
object Watchdog {
    /** For `adb shell cmd jobscheduler run -f com.storiedev.familycoverage 1` (TESTING.md). */
    private const val JOB_ID = 1
    private const val PERIOD_MS = 30 * 60_000L
    /** It runs in the last 10 minutes of each half hour, whenever suits Android best. */
    private const val FLEX_MS = 10 * 60_000L
    private const val CHANNEL = "stopped"
    // The logger's own notification is 1, and the Play build's test-text reminder 2.
    private const val ALERT_ID = 3
    private const val END_ID = 4

    /** Starts the watchdog, or again with this version's timing: when recording turns on, and at boot or an update. */
    fun schedule(context: Context) {
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, WatchdogJob::class.java))
            .setPeriodic(PERIOD_MS, FLEX_MS)
            .setPersisted(true)
            .build()
        runCatching { context.getSystemService(JobScheduler::class.java).schedule(job) }
    }

    /** No more checks: the person stopped recording, left the household or deleted the data, or it ended. */
    fun cancel(context: Context, prefs: Prefs) {
        runCatching { context.getSystemService(JobScheduler::class.java).cancel(JOB_ID) }
        clear(context, prefs)
    }

    /** The logger started: any "stopped recording" notification goes, and the daily limit starts over. */
    fun started(context: Context, prefs: Prefs) = clear(context, prefs)

    private fun clear(context: Context, prefs: Prefs) {
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(ALERT_ID) }
        if (prefs.stopAlertReason != null) {
            prefs.stopAlertReason = null
            prefs.stopAlertAtMs = 0L
        }
    }

    /**
     * The check (the job, and at boot or after an update): when recording should be on but the logger isn't running
     * in this process, start it again, or say why it can't.
     */
    fun check(context: Context) {
        val prefs = Prefs(context)
        val household = prefs.household
        val meant = prefs.loggingEnabled && prefs.member != null && prefs.consentAt != null
        if (household != null && meant && Config.ended(household.end) && !Status.running) {
            // The end date passed while the logger wasn't running: recording is over, as the logger would have said.
            prefs.loggingEnabled = false
            ended(context, prefs, household)
            return
        }
        var action = decide(context, prefs, StopCheck.Start.NOT_TRIED, Status.running)
        if (action == StopCheck.Action.Restart) {
            val start = try {
                context.startForegroundService(Intent(context, LoggerService::class.java))
                StopCheck.Start.STARTED
            } catch (e: Exception) {
                // Mostly ForegroundServiceStartNotAllowedException: a start from the background, without the battery
                // exemption.
                StopCheck.Start.FAILED
            }
            action = decide(context, prefs, start, Status.running)
        }
        if (action is StopCheck.Action.Alert) alert(context, prefs, action.reason)
    }

    /** The logger couldn't start (a permission is off, or Android refused): says why, if recording should be on. */
    fun cantStart(context: Context, prefs: Prefs) {
        val action = decide(context, prefs, StopCheck.Start.FAILED, running = false)
        if (action is StopCheck.Action.Alert) alert(context, prefs, action.reason)
    }

    /**
     * Recording is over: the household's end date passed, or the household went. The watchdog stops, and after an end
     * date a notice says so, once (moving the date and recording again earns a new one).
     */
    fun ended(context: Context, prefs: Prefs, household: Household?) {
        cancel(context, prefs)
        if (household == null) return
        val key = "${household.id} ${household.end}"
        if (prefs.endNoticeFor == key) return
        prefs.endNoticeFor = key
        val text = StopCheck.endText(household.end, household.server != null)
        show(context, END_ID, "Family Coverage finished recording", text)
    }

    /** What recording needs that can be taken back (what the Start button asks for), as the reason each would give. */
    fun missing(context: Context): Set<StopCheck.Reason> {
        fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        return buildSet {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                val approximate = granted(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(if (approximate) StopCheck.Reason.PRECISE else StopCheck.Reason.LOCATION)
            }
            if (!granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) add(StopCheck.Reason.BACKGROUND)
            if (!granted(Manifest.permission.READ_PHONE_STATE)) add(StopCheck.Reason.PHONE)
        }
    }

    private fun decide(context: Context, prefs: Prefs, start: StopCheck.Start, running: Boolean): StopCheck.Action {
        val household = prefs.household
        val power = context.getSystemService(PowerManager::class.java)
        return StopCheck.decide(
            setUp = household != null && prefs.member != null,
            agreed = prefs.consentAt != null,
            enabled = prefs.loggingEnabled,
            ended = household != null && Config.ended(household.end),
            running = running,
            missing = missing(context),
            batteryExempt = power.isIgnoringBatteryOptimizations(context.packageName),
            start = start,
            lastReason = StopCheck.Reason.entries.firstOrNull { it.name == prefs.stopAlertReason },
            lastAlertMs = prefs.stopAlertAtMs,
            nowMs = System.currentTimeMillis(),
        )
    }

    private fun alert(context: Context, prefs: Prefs, reason: StopCheck.Reason) {
        // Without notification permission (Android 13 and later) nothing can show, so nothing counts as said.
        if (!context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) return
        show(context, ALERT_ID, "Family Coverage stopped recording", reason.text)
        prefs.stopAlertReason = reason.name
        prefs.stopAlertAtMs = System.currentTimeMillis()
    }

    private fun show(context: Context, id: Int, title: String, text: String) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording stopped", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When recording stops by itself, and why, or at the household's end date"
            },
        )
        // The main screen: its setup list marks what's missing with a ✗, and a tap there fixes it.
        val open = PendingIntent.getActivity(
            context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(id, n) }
    }
}

/** The watchdog's decisions, free of Android types for the unit tests. */
object StopCheck {
    /** One notification per reason a day; a different reason shows at once. */
    const val ALERT_GAP_MS = 24 * 3_600_000L

    /**
     * Why recording stopped, with what the notification says: what happened, then the fix. A missing permission
     * comes first, in this order (no location at all before its parts); a failed start only when they're all there.
     */
    enum class Reason(val text: String) {
        LOCATION("Location access was turned off. Tap to allow it again."),
        PRECISE("Precise location was turned off. Tap to allow it again."),
        BACKGROUND("Location is no longer allowed all the time. Tap to fix."),
        PHONE("Phone access was turned off. Tap to allow it again."),
        BATTERY("Android didn't let it restart. Tap, then set Battery to Unrestricted."),
        OTHER("It couldn't restart by itself. Tap, then start recording again."),
    }

    /** Starting the logger: not tried yet, accepted, or refused (it threw, or the logger couldn't go foreground). */
    enum class Start { NOT_TRIED, STARTED, FAILED }

    sealed interface Action {
        /** Recording runs, or shouldn't, or the person heard about this reason in the last day. */
        data object None : Action
        /** Recording should be on and everything it needs is there: start the logger again. */
        data object Restart : Action
        data class Alert(val reason: Reason) : Action
    }

    /**
     * Recording should be on when the phone is set up (a household and its member), its owner agreed, recording is
     * on (the person didn't stop it) and the end date hasn't passed. Then, if the logger isn't running: start it
     * again when nothing is [missing], or alert. Battery-exempt apps may start a foreground service from the
     * background and others mostly may not, so a refused start without the exemption asks for it.
     */
    fun decide(
        setUp: Boolean,
        agreed: Boolean,
        enabled: Boolean,
        ended: Boolean,
        running: Boolean,
        missing: Set<Reason>,
        batteryExempt: Boolean,
        start: Start,
        lastReason: Reason?,
        lastAlertMs: Long,
        nowMs: Long,
    ): Action {
        if (!setUp || !agreed || !enabled || ended || running) return Action.None
        val reason = missing.minOrNull() ?: when (start) {
            Start.NOT_TRIED -> return Action.Restart
            Start.STARTED -> return Action.None
            Start.FAILED -> if (batteryExempt) Reason.OTHER else Reason.BATTERY
        }
        // A clock set back counts as a day gone, rather than silence until it catches up.
        val toldToday = reason == lastReason && nowMs - lastAlertMs in 0 until ALERT_GAP_MS
        return if (toldToday) Action.None else Action.Alert(reason)
    }

    /** The end date's notice: which date passed, and where the data is now. */
    fun endText(end: LocalDate, server: Boolean): String {
        val date = end.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.US))
        return "Recording has ended because the household's end date ($date) passed. " +
            if (server) "The data is on your household's server." else "Export the data from the app."
    }
}
