package com.storiedev.familycoverage

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import java.time.LocalTime
import java.time.OffsetDateTime

/** Hands what the SMS receivers find to the running logger (same process). With no logger running, nothing happens. */
object TextBus {
    @Volatile var sink: ((AutoTexts.() -> Unit) -> Unit)? = null

    fun post(block: AutoTexts.() -> Unit) {
        sink?.invoke(block)
    }

    /** A test text that arrived: its tag and the SIM it came in on, plus the sender's number, in memory for the reply. */
    data class Received(
        val role: String, val testId: String, val sentAt: LocalTime, val subId: Int, val from: String,
        val transport: String, val at: OffsetDateTime,
    )

    /**
     * The privacy gate, before anything leaves the receiver: a message counts only if it came from one of the
     * partner's numbers AND it's a test text. Anything else is dropped right here: never stored, logged or uploaded.
     */
    fun received(context: Context, intent: Intent, transport: String, body: (SmsMessage) -> String?) {
        val prefs = Prefs(context)
        if (!prefs.textsEnabled) return
        val parts = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull()
        if (parts.isNullOrEmpty()) return
        val from = parts[0].originatingAddress ?: return
        if (!TextMath.sameNumber(from, prefs.textPartnerMain) && !TextMath.sameNumber(from, prefs.textPartnerSecond)) return
        val tag = TextMath.parse(parts.joinToString("") { body(it) ?: "" }) ?: return
        val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, intent.getIntExtra("subscription", -1))
        val r = Received(tag.role, tag.testId, tag.sentAt, subId, from, transport, OffsetDateTime.now())
        post { onReceived(r) }
    }

    /**
     * The receivers are switched on only while logging runs with test texts on. The plain-text one is on only when
     * visible tests or replies can arrive: a partner number silent texts don't reach, or a SIM here that sends
     * visible tests. When everything is silent the app never receives ordinary texts at all.
     */
    fun applyReceivers(context: Context, prefs: Prefs, running: Boolean) {
        fun set(cls: Class<*>, on: Boolean) = context.packageManager.setComponentEnabledSetting(
            ComponentName(context, cls),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        val on = running && prefs.textsEnabled && prefs.validPartner() != null
        val partnerVisible = (TextMath.number(prefs.textPartnerMain) != null && !prefs.textMainSilent) ||
            (TextMath.number(prefs.textPartnerSecond) != null && !prefs.textSecondSilent)
        val visible = partnerVisible || prefs.textOwnVisible || prefs.textNoSilentSubs.isNotEmpty()
        set(DataSmsReceiver::class.java, on)
        set(TextSmsReceiver::class.java, on && visible)
    }
}

/** Silent test texts: data SMS to the app's own port. No other message ever reaches this receiver. */
class DataSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION) return
        TextBus.received(context, intent, "data") { m -> m.userData?.let { String(it, Charsets.US_ASCII) } }
    }
}

/**
 * Visible test texts. Android shows this receiver every incoming text, so it's switched off unless visible tests
 * can arrive, and TextBus.received drops anything that isn't a test text from the partner's numbers.
 */
class TextSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        TextBus.received(context, intent, "text") { it.messageBody }
    }
}

/** The fate of the app's own test texts: the send result, then the delivery report if the network sends one. */
class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        when (intent.action) {
            ACTION_SENT -> {
                val code = resultCode // only valid during onReceive
                TextBus.post { onSent(key, code) }
            }
            ACTION_DELIVERED -> {
                val pdu = intent.getByteArrayExtra("pdu")
                val status = pdu?.let { runCatching { SmsMessage.createFromPdu(it, intent.getStringExtra("format")).status }.getOrNull() }
                TextBus.post { onDelivered(key, status) }
            }
        }
    }

    companion object {
        const val ACTION_SENT = "com.storiedev.familycoverage.SMS_SENT"
        const val ACTION_DELIVERED = "com.storiedev.familycoverage.SMS_DELIVERED"
        const val EXTRA_KEY = "key"
    }
}
