package com.storiedev.familycoverage

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/** The test-text settings for the Google Play build: on/off, the partner and their number, and the status. */
class PromptTextsUi(private val activity: Activity, private val prefs: Prefs) : TextsUi {
    private lateinit var partners: RadioGroup
    private lateinit var status: TextView
    private var shownMembers: List<String> = emptyList()

    override fun build(root: LinearLayout) {
        val c = activity
        root.addView(Ui.heading(c, "Test texts"))
        root.addView(
            Ui.text(
                c,
                "After 10 minutes at one of your places, the app asks whether to send a test text to your partner " +
                    "(at most 4 a day, never 9 pm to 7 am). Tapping opens Messages with a test like \"FC test 7F3A12 " +
                    "13:30:05\" filled in; press send. When your partner's test reaches you, tap \"Text arrived\" " +
                    "under Call and text tests. This build can't send or read texts itself: Google Play allows that " +
                    "only for texting apps. The F-Droid build does both automatically.",
                13f,
            ),
        )
        root.addView(Ui.checkbox(c, "Remind me to send test texts", prefs.textsEnabled) { prefs.textsEnabled = it })
        root.addView(Ui.text(c, "Test with:", 13f))
        partners = RadioGroup(c).apply { orientation = RadioGroup.VERTICAL }
        root.addView(partners)
        root.addView(Ui.phoneField(c, "Your partner's number", prefs.textPartnerMain) { prefs.textPartnerMain = it })
        root.addView(Ui.button(c, "Send a test text now") {
            c.startActivity(Intent(c, TextComposeActivity::class.java).putExtra(LoggerService.EXTRA_TRIGGER, "manual"))
        })
        status = Ui.text(c, "", 13f)
        root.addView(status)
    }

    override fun render() {
        val members = prefs.household?.members.orEmpty().filter { it != prefs.member }
        if (members != shownMembers) {
            partners.removeAllViews()
            for (m in members) {
                val b = RadioButton(activity).apply {
                    id = View.generateViewId()
                    text = m
                    setOnClickListener { prefs.textPartner = m }
                }
                partners.addView(b)
                if (prefs.textPartner == m) partners.check(b.id)
            }
            shownMembers = members
        }
        status.text = "Test texts: ${Status.textsToday.ifBlank { if (Status.running) "starting" else "recording is off" }}\n" +
            "Last: ${Status.lastText}"
    }
}
