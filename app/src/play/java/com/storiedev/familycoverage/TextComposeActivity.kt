package com.storiedev.familycoverage

import android.app.Activity
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.time.LocalTime

/**
 * No screen of its own: notes a new test text, then opens the phone's Messages app with it addressed to the partner.
 * The owner presses send there. Reached from the reminder notification and from "Send a test text now".
 */
class TextComposeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSystemService(NotificationManager::class.java)?.cancel(PromptTexts.NOTIFICATION_ID)
        val prefs = Prefs(this)
        val number = prefs.textPartnerMain
        if (TextMath.number(number) == null || prefs.validPartner() == null) {
            Ui.toast(this, "Choose your partner and set their number in Family Coverage first.")
            finish()
            return
        }
        val testId = TextMath.newId()
        val at = LocalTime.now().withNano(0)
        if (Status.running) {
            startService(
                Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_TEXT_COMPOSED)
                    .putExtra(LoggerService.EXTRA_TEST_ID, testId)
                    .putExtra(LoggerService.EXTRA_SENT_AT, at.toString())
                    .putExtra(LoggerService.EXTRA_TRIGGER, intent.getStringExtra(LoggerService.EXTRA_TRIGGER) ?: "manual")
                    .putExtra(LoggerService.EXTRA_PLACE, intent.getStringExtra(LoggerService.EXTRA_PLACE)),
            )
        }
        try {
            startActivity(
                Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(number)))
                    .putExtra("sms_body", TextMath.tag("test", testId, at)),
            )
        } catch (e: ActivityNotFoundException) {
            Ui.toast(this, "No texting app found.")
        }
        finish()
    }
}
