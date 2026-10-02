package com.storiedev.familycoverage

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/** The test-text settings for automatic texts: on/off, the partner and their numbers, the SIMs, and the status. */
class AutoTextsUi(private val activity: Activity, private val prefs: Prefs) : TextsUi {
    private lateinit var partners: RadioGroup
    private lateinit var sims: LinearLayout
    private lateinit var status: TextView
    private var shownSims: List<Pair<Int, String>> = emptyList()
    private var shownMembers: List<String> = emptyList()

    override fun build(root: LinearLayout) {
        val c = activity
        root.addView(Ui.heading(c, "Test texts"))
        root.addView(
            Ui.text(
                c,
                "Two phones in the household text each other at your places, from each SIM ticked below, and answer " +
                    "each other's tests, so every line is tested both ways. At most 8 exchanges a day, never 9 pm to " +
                    "7 am, and none during a call. Where both lines carry silent texts (data SMS) no Messages app " +
                    "shows them; otherwise they're visible texts like \"FC test 7F3A12 13:30:05\", sent to the second " +
                    "number if there is one so replies stay out of your usual conversation. The app acts only on its " +
                    "own test texts from your partner's numbers, which never leave this phone.",
                13f,
            ),
        )
        root.addView(Ui.checkbox(c, "Send and answer test texts", prefs.textsEnabled) { on ->
            prefs.textsEnabled = on
            Flavor.applyReceivers(c, prefs, Status.running)
        })
        root.addView(Ui.text(c, "Test with:", 13f))
        partners = RadioGroup(c).apply { orientation = RadioGroup.VERTICAL }
        root.addView(partners)
        root.addView(Ui.text(c, "Test from:", 13f))
        sims = Ui.vertical(c)
        root.addView(sims)
        root.addView(Ui.phoneField(c, "Your partner's main number", prefs.textPartnerMain) { prefs.textPartnerMain = it })
        root.addView(silentBox(prefs.textMainSilent) { prefs.textMainSilent = it })
        root.addView(Ui.phoneField(c, "Their second number, if they have one", prefs.textPartnerSecond) { prefs.textPartnerSecond = it })
        root.addView(silentBox(prefs.textSecondSilent) { prefs.textSecondSilent = it })
        root.addView(
            Ui.text(
                c,
                "Silent texts: tick a number only if its line delivers them. In the US, Verizon-network lines do; " +
                    "AT&T's network (Cricket too) neither sends nor delivers them. Not sure? Leave it unticked: " +
                    "visible texts reach every network. A SIM whose network refuses silent texts switches itself to " +
                    "visible ones.\nIf Android calls SMS a restricted setting, open App info > menu (three dots) > " +
                    "Allow restricted settings, then allow SMS.",
                12f,
            ),
        )
        root.addView(Ui.button(c, "Send a test now") {
            if (!Status.running) {
                status.text = "Start recording first."
            } else {
                c.startService(Intent(c, LoggerService::class.java).setAction(LoggerService.ACTION_TEXT_NOW))
                status.text = "Sending..."
            }
        })
        status = Ui.text(c, "", 13f)
        root.addView(status)
    }

    private fun silentBox(checked: Boolean, save: (Boolean) -> Unit) =
        Ui.checkbox(activity, "Silent texts reach this number", checked) { on ->
            save(on)
            Flavor.applyReceivers(activity, prefs, Status.running)
        }

    override fun render() {
        val members = prefs.household?.members.orEmpty().filter { it != prefs.member }
        if (members != shownMembers) {
            partners.removeAllViews()
            for (m in members) {
                val b = RadioButton(activity).apply {
                    id = View.generateViewId()
                    text = m
                    setOnClickListener {
                        prefs.textPartner = m
                        Flavor.applyReceivers(activity, prefs, Status.running)
                    }
                }
                partners.addView(b)
                if (prefs.textPartner == m) partners.check(b.id)
            }
            shownMembers = members
        }
        val choices = Status.simChoices
        if (choices != shownSims) {
            sims.removeAllViews()
            val chosen = prefs.textSubs
            for ((subId, name) in choices) {
                sims.addView(CheckBox(activity).apply {
                    text = name
                    tag = subId
                    isChecked = chosen == null || subId in chosen
                    setOnCheckedChangeListener { _, _ -> saveSims() }
                })
            }
            shownSims = choices
        }
        status.text = "Test texts: ${Status.textsToday.ifBlank { if (Status.running) "starting" else "recording is off" }}\n" +
            "Last: ${Status.lastText}"
    }

    private fun saveSims() {
        prefs.textSubs = (0 until sims.childCount).map { sims.getChildAt(it) as CheckBox }
            .filter { it.isChecked }.map { it.tag as Int }.toSet()
    }

    override fun pause() {
        // The numbers may have changed, and with them whether visible test texts can arrive.
        Flavor.applyReceivers(activity, prefs, Status.running)
    }
}
