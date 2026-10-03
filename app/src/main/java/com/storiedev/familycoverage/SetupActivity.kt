package com.storiedev.familycoverage

import android.app.Activity
import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Starting a household, joining one (scan or paste its setup code), editing it, and choosing which member this phone
 * belongs to. Nothing here records anything: that starts on the main screen, after the owner agrees.
 */
class SetupActivity : Activity() {
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        when (intent.getStringExtra(EXTRA_MODE)) {
            MODE_EDIT -> prefs.household?.let { showForm(it) } ?: showWelcome()
            MODE_JOIN -> showJoin()
            MODE_MEMBER -> prefs.household?.let { showMemberPicker(it) } ?: showWelcome()
            else -> if (prefs.household == null) showWelcome() else done()
        }
    }

    private fun page(title: String, build: LinearLayout.() -> Unit) {
        val pad = Ui.dp(this, 16)
        val root = Ui.vertical(this).apply { setPadding(pad, pad, pad, pad) }
        root.addView(Ui.text(this, title, 22f, bold = true))
        root.build()
        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun showWelcome() = page("Family Coverage") {
        addView(
            Ui.text(
                this@SetupActivity,
                "Which network works where your family actually goes? Each phone in the household records its " +
                    "signal, data and test results for a few weeks, for every SIM it has, so you can compare " +
                    "networks place by place before you switch. The data stays on your phones.",
            ),
        )
        addView(Ui.heading(this@SetupActivity, "First phone in the household?"))
        addView(Ui.button(this@SetupActivity, "Start a household") { showForm(null) })
        addView(Ui.heading(this@SetupActivity, "Someone already started one?"))
        addView(Ui.button(this@SetupActivity, "Join a household") { showJoin() })
    }

    /** The household's settings: new ([existing] null) or an edit. */
    private fun showForm(existing: Household?) = page(if (existing == null) "Start a household" else "Edit the household") {
        val c = this@SetupActivity
        addView(Ui.text(c, "Household name", 13f, bold = true, top = 8))
        val name = EditText(c).apply {
            hint = "The Smiths"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(existing?.name ?: "")
        }
        addView(name)
        addView(Ui.text(c, "Members: one per line, everyone whose phone will record (you too)", 13f, bold = true, top = 8))
        val members = EditText(c).apply {
            hint = "Alex\nSam"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            minLines = 3
            setText(existing?.members?.joinToString("\n") ?: "")
        }
        addView(members)
        addView(Ui.text(c, "End date: recording stops after this day", 13f, bold = true, top = 8))
        var end = existing?.end ?: LocalDate.now().plusDays(Config.DEFAULT_DAYS)
        lateinit var endButton: Button
        endButton = Ui.button(c, label(end)) {
            DatePickerDialog(c, { _, y, m, d ->
                end = LocalDate.of(y, m + 1, d)
                endButton.text = label(end)
            }, end.year, end.monthValue - 1, end.dayOfMonth).apply {
                val zone = ZoneId.systemDefault()
                datePicker.minDate = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
                datePicker.maxDate = LocalDate.now().plusDays(Config.MAX_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()
            }.show()
        }
        addView(endButton)
        addView(Ui.text(c, "Server (optional)", 13f, bold = true, top = 8))
        addView(
            Ui.text(
                c,
                "Leave this blank and each phone keeps its own data until you export it. Or enter a Family Coverage " +
                    "server your household runs (see the website), and the phones copy their data to it: " +
                    "https://..., or http://... on your home network.",
                12f,
            ),
        )
        val server = EditText(c).apply {
            hint = "https://coverage.example.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.server ?: "")
        }
        addView(server)
        val error = Ui.text(c, "", 14f)
        addView(error)
        addView(Ui.button(c, if (existing == null) "Create the household" else "Save") {
            try {
                val list = members.text.toString().lines().mapNotNull { Household.cleanMember(it) }.distinct()
                require(list.isNotEmpty()) { "add at least one member" }
                require(list.size <= Household.MAX_MEMBERS) { "at most ${Household.MAX_MEMBERS} members" }
                require(!end.isBefore(LocalDate.now())) { "the end date has passed" }
                require(!end.isAfter(LocalDate.now().plusDays(Config.MAX_DAYS))) { "the end date is more than a year away" }
                val h = Household(
                    id = existing?.id ?: Household.newId(),
                    name = Household.cleanName(name.text.toString()) ?: throw IllegalArgumentException("give the household a name"),
                    members = list,
                    end = end,
                    server = Household.cleanServer(server.text.toString()),
                    places = existing?.places.orEmpty(),
                ).withMembers(list)
                val me = prefs.member
                if (me != null && me !in list && Status.running) {
                    throw IllegalArgumentException("this phone belongs to $me: keep $me in the list, or stop recording first")
                }
                if (h.server != prefs.household?.server) prefs.resetServer()
                if (termsChanged(existing, h)) agreeAgain()
                prefs.household = h
                if (me == null || me !in list) showMemberPicker(h) else done()
            } catch (e: IllegalArgumentException) {
                error.text = "✗  ${e.message?.replaceFirstChar { it.uppercase() }}."
            }
        })
    }

    private fun showJoin() = page("Join a household") {
        val c = this@SetupActivity
        addView(
            Ui.text(
                c,
                "On a phone that's already in the household, open Family Coverage and choose \"Share this household\". " +
                    "Then scan its QR code here, or paste the code it shared as text.",
            ),
        )
        addView(Ui.button(c, "Scan the QR code") {
            startActivityForResult(Intent(c, ScanActivity::class.java), REQ_SCAN)
        })
        addView(Ui.text(c, "Or paste the setup code:", 13f, bold = true, top = 16))
        val code = EditText(c).apply {
            hint = "FC1...."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
        }
        addView(code)
        val error = Ui.text(c, "", 14f)
        addView(error)
        addView(Ui.button(c, "Use this code") {
            try {
                showPreview(Household.decode(code.text.toString()))
            } catch (e: IllegalArgumentException) {
                error.text = "✗  ${e.message?.replaceFirstChar { it.uppercase() }}."
            }
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_SCAN || resultCode != RESULT_OK) return
        val text = data?.getStringExtra(ScanActivity.EXTRA_CODE) ?: return
        try {
            showPreview(Household.decode(text))
        } catch (e: IllegalArgumentException) {
            Ui.toast(this, e.message ?: "That code didn't work")
        }
    }

    /** What the code holds, before this phone takes it. */
    private fun showPreview(h: Household) = page("Join ${h.name}?") {
        val c = this@SetupActivity
        val current = prefs.household
        addView(
            Ui.text(
                c,
                "Members: ${h.members.joinToString(", ")}\n" +
                    "Records until: ${label(h.end)}\n" +
                    "Data: " + (h.server?.let { "stays on each phone, copied to $it" } ?: "stays on each phone") + "\n" +
                    "Places: ${h.places.size}" +
                    if (current != null && current.id != h.id) {
                        "\n\nThis phone is set up for a different household (${current.name}). Joining replaces it; " +
                            "what this phone has recorded stays on it."
                    } else {
                        ""
                    },
            ),
        )
        addView(Ui.button(c, if (current?.id == h.id) "Update this phone" else "Join") {
            val me = prefs.member
            if (current != null && current.id != h.id && Status.running) {
                Ui.toast(c, "Stop recording first.")
                return@button
            }
            if (h.server != current?.server) prefs.resetServer()
            if (current?.id != h.id) {
                prefs.consentAt = null // a different household: its own terms, agreed afresh
                prefs.member = null
            } else if (termsChanged(current, h)) {
                agreeAgain()
            }
            prefs.household = h
            if (me != null && current?.id == h.id && me in h.members) done() else showMemberPicker(h)
        })
    }

    private fun showMemberPicker(h: Household) = page("Whose phone is this?") {
        val c = this@SetupActivity
        if (Status.running) {
            addView(Ui.text(c, "Stop recording first: the member can't change while the phone records."))
            addView(Ui.button(c, "Back") { done() })
            return@page
        }
        val group = RadioGroup(c).apply { orientation = RadioGroup.VERTICAL }
        for (m in h.members) {
            group.addView(RadioButton(c).apply {
                id = View.generateViewId()
                text = m
                tag = m
                isChecked = prefs.member == m
            })
        }
        addView(group)
        addView(Ui.text(c, "Every row this phone records carries this name. Not on the list? Edit the household first.", 13f))
        addView(Ui.button(c, "This is me") {
            val choice = group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? String
            if (choice == null) {
                Ui.toast(c, "Choose a name first.")
                return@button
            }
            if (choice != prefs.member) prefs.consentAt = null
            prefs.member = choice
            done()
        })
    }

    /** A new server or a later end date changes what the owner agreed to. */
    private fun termsChanged(old: Household?, new: Household): Boolean =
        old != null && (old.server != new.server || new.end.isAfter(old.end))

    /** The owner agrees again before recording resumes; a running recording stops until then. */
    private fun agreeAgain() {
        prefs.consentAt = null
        if (Status.running) {
            prefs.loggingEnabled = false
            startService(Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_STOP))
            Ui.toast(this, "The household's terms changed: read them and agree again to keep recording.")
        }
    }

    private fun done() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun label(d: LocalDate) = d.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault()))

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_EDIT = "edit"
        const val MODE_JOIN = "join"
        const val MODE_MEMBER = "member"
        private const val REQ_SCAN = 1
    }
}
