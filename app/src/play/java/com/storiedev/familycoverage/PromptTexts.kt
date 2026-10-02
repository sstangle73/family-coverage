package com.storiedev.familycoverage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SubscriptionManager
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Test texts without SMS permissions (the Google Play build). After 10 minutes at one of the household's places, or
 * at a place's fallback time, the phone asks "Send a test text?". Tapping opens Messages with a test like
 * "FC test 7F3A12 13:30:05" addressed to the partner; the owner presses send. The partner taps "Text arrived" (or
 * "didn't arrive") in their app. The texts table records each test the owner opened ("composed"); the report pairs
 * it with the partner's tap.
 */
class PromptTexts(
    private val context: Context,
    private val prefs: Prefs,
    sampler: TelephonySampler,
    tracker: LocationTracker,
    store: CsvStore,
    wifi: () -> Boolean,
    private val inCall: () -> Boolean,
) : TextTests {
    private val visits = PlaceVisits()
    private val log = TextLog(prefs, sampler, tracker, store, wifi, visits)
    private val nm = context.getSystemService(NotificationManager::class.java)

    override fun start() {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Test text reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Asks you to send a test text at one of your places"
            },
        )
    }

    override fun stop() {
        nm.cancel(NOTIFICATION_ID)
    }

    private fun blocker(): String? = when {
        !prefs.textsEnabled -> "off"
        log.partner() == null -> "choose who to test with"
        TextMath.number(prefs.textPartnerMain) == null -> "the partner's number isn't set"
        else -> null
    }

    private fun may(nowMs: Long, t: LocalTime): String? =
        TextMath.mayStart(nowMs, t, prefs.textExchanges, prefs.textLastExchangeMs, Config.TEXT_DAY_MAX_VISIBLE, manual = false)

    override fun tick() {
        val nowMs = System.currentTimeMillis()
        val now = LocalDateTime.now()
        log.rollDay(now.toLocalDate().toString())
        visits.refresh(prefs.household)
        val fix = log.currentFix()
        val here = fix?.let { visits.placeAt(it) }
        visits.update(here, fix)
        showStatus()
        if (inCall() || blocker() != null) return
        val v = visits.dwelled(Config.TEXT_DWELL_MS)
        if (v != null && v.exchanges < 1 && may(nowMs, now.toLocalTime()) == null) {
            v.exchanges++
            prompt("place", v.placeId)
            return
        }
        for (p in visits.places) for (time in p.times) {
            val slot = runCatching { LocalTime.parse(time) }.getOrNull() ?: continue
            val days = p.days.mapNotNull { TextMath.day(it) }.toSet()
            val key = "${now.toLocalDate()} ${p.id} $time"
            if (key in prefs.textSlotsDone || !TextMath.slotDue(now, days, slot)) continue
            prefs.textSlotsDone = prefs.textSlotsDone + key
            if (nowMs - prefs.textLastExchangeMs >= Config.TEXT_FALLBACK_GAP_MS && may(nowMs, now.toLocalTime()) == null) {
                prompt("schedule", here?.id)
                return
            }
        }
    }

    override fun manual() {
        // The screen opens Messages itself (TextComposeActivity); nothing to do here.
    }

    override fun composed(testId: String, trigger: String, placeId: String?, sentAt: LocalTime) {
        val peer = log.partner() ?: return
        val subId = SubscriptionManager.getDefaultSmsSubscriptionId()
        val t = OffsetDateTime.now()
        val snap = log.snapshot(subId, placeId)
        log.write(t, "composed", testId, null, "test", trigger, "manual", subId, null, null, null, snap, peer)
        Status.lastText = "${LoggerService.clock(t)} opened Messages with test $testId to $peer"
    }

    private fun prompt(trigger: String, placeId: String?) {
        prefs.textExchanges = prefs.textExchanges + 1
        prefs.textLastExchangeMs = System.currentTimeMillis()
        val place = visits.name(placeId)
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, TextComposeActivity::class.java)
                .putExtra(LoggerService.EXTRA_TRIGGER, trigger)
                .putExtra(LoggerService.EXTRA_PLACE, placeId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(if (place != null) "At $place: send a test text?" else "Time for a test text?")
            .setContentText("Tap to open Messages with a test to ${log.partner()}, then press send.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(30 * 60_000L)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
        Status.lastText = "${LoggerService.clock(OffsetDateTime.now())} asked for a test text" + (place?.let { " at $it" } ?: "")
    }

    private fun showStatus() {
        val why = blocker()
        val at = visits.visit?.let { v -> " At ${visits.name(v.placeId) ?: v.placeId}." } ?: ""
        Status.textsToday = why ?: ("${prefs.textExchanges} of ${Config.TEXT_DAY_MAX_VISIBLE} reminders today; " +
            "${visits.places.size} places.$at")
    }

    companion object {
        private const val CHANNEL = "texts"
        const val NOTIFICATION_ID = 2
    }
}
