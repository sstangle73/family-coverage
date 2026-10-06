package com.storiedev.familycoverage

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors

/** The main screen: agreement, permissions, recording, places, tests, the household, the data, and About. */
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var header: TextView
    private lateinit var consentText: TextView
    private lateinit var consent: CheckBox
    private lateinit var steps: LinearLayout
    private lateinit var startButton: Button
    private lateinit var status: TextView
    private lateinit var placesList: LinearLayout
    private lateinit var sims: RadioGroup
    private lateinit var undo: Button
    private lateinit var eventStatus: TextView
    private lateinit var dataStatus: TextView
    private lateinit var textsUi: TextsUi
    private var shownSims: List<Pair<Int, String>> = emptyList()
    private var shownEventAt = 0L
    private var shownPlaces: List<Place>? = null
    private var shownSteps: List<Pair<String, Boolean>>? = null
    private var shownPlacesLocated = false
    private lateinit var uploadButton: Button
    private lateinit var reportButton: Button
    private lateinit var filesReportButton: Button
    private lateinit var dataText: TextView
    private var consentShown = false
    private var settingConsent = false
    private val io = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            ui.postDelayed(this, 2_000L)
        }
    }

    private data class Step(
        val label: String,
        val done: () -> Boolean,
        val visible: () -> Boolean = { true },
        val action: () -> Unit,
    )

    private val setupSteps by lazy {
        listOf(
            Step("Location: precise", { granted(Manifest.permission.ACCESS_FINE_LOCATION) }) {
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1,
                )
            },
            Step("Location: allow all the time", { granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) }) {
                if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                    backgroundLocationDisclosure()
                } else {
                    Ui.toast(this, "Allow precise location first.")
                }
            },
            Step("Phone: read each SIM's network", { granted(Manifest.permission.READ_PHONE_STATE) }) {
                requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 3)
            },
            Step("Notifications", { Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS) }) {
                if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4)
            },
            Step("Battery: unrestricted", { batteryExempt() }) {
                runCatching { startActivity(Flavor.batteryIntent(this)) }
            },
            Step(
                "SMS: test texts",
                { Flavor.smsPermissions.all { granted(it) } },
                { Flavor.smsPermissions.isNotEmpty() && prefs.textsEnabled },
            ) {
                requestPermissions(Flavor.smsPermissions, 5)
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        if (prefs.household == null || prefs.member == null) {
            startActivity(Intent(this, SetupActivity::class.java).putExtra(
                SetupActivity.EXTRA_MODE, if (prefs.household == null) null else SetupActivity.MODE_MEMBER,
            ))
            finish()
            return
        }
        val c = this
        val pad = Ui.dp(c, 16)
        val root = Ui.vertical(c).apply { setPadding(pad, pad, pad, pad) }
        root.addView(Ui.text(c, "Family Coverage", 24f, bold = true))
        header = Ui.text(c, "", 15f)
        root.addView(header)

        root.addView(Ui.heading(c, "What this phone records"))
        consentText = Ui.text(c, "", 14f)
        root.addView(consentText)
        root.addView(Ui.button(c, "Show or hide the details") {
            consentShown = !consentShown
            render()
        })
        consent = CheckBox(c).apply {
            text = "I've read what this records, and I agree."
            isChecked = prefs.consentAt != null
            setOnCheckedChangeListener { _, checked ->
                if (settingConsent || Status.running) return@setOnCheckedChangeListener
                prefs.consentAt = if (checked) Csv.ts(OffsetDateTime.now()) else null
                consentShown = !checked
                render()
            }
        }
        root.addView(consent)

        root.addView(Ui.heading(c, "Setup"))
        steps = Ui.vertical(c)
        root.addView(steps)
        root.addView(
            Ui.text(
                c,
                "Android asks the Phone step as \"make and manage phone calls\": the app only reads each SIM's " +
                    "network and signal, and never makes, answers or reads calls. Samsung: also add Family Coverage " +
                    "to Settings > Battery > Background usage limits > Never sleeping apps. A VPN (Tailscale, say) can " +
                    "stay on: carrier tests then run whenever you're off Wi-Fi.",
                13f,
                top = 8,
            ),
        )
        startButton = Ui.button(c, "Start recording") { toggleRecording() }
        root.addView(startButton)
        status = Ui.text(c, "", 13f).apply { typeface = Typeface.MONOSPACE }
        root.addView(status)

        root.addView(Ui.heading(c, "Places"))
        root.addView(
            Ui.text(
                c,
                "The places that matter to your household: home, school, work, the grandparents'. The report compares " +
                    "networks at each one, and test texts go out there. Stand at a place and add it.",
                13f,
            ),
        )
        placesList = Ui.vertical(c)
        root.addView(placesList)
        root.addView(Ui.button(c, "Add the spot you're at") { addPlace() })

        root.addView(Ui.heading(c, "Call and text tests"))
        root.addView(
            Ui.text(
                c,
                "When someone calls or texts you to test coverage, tap what happened, right away. The app notes the " +
                    "time, where you are and each SIM's service. First pick the number they used.",
                13f,
            ),
        )
        sims = RadioGroup(c).apply { orientation = RadioGroup.VERTICAL }
        root.addView(sims)
        root.addView(Ui.pair(c, eventButton("✓  Call rang", "CALL_IN", "REACHED"), eventButton("✗  Call didn't ring", "CALL_IN", "MISSED")))
        root.addView(Ui.pair(c, eventButton("✓  Text arrived", "TEXT_IN", "REACHED"), eventButton("✗  Text didn't arrive", "TEXT_IN", "MISSED")))
        undo = Ui.button(c, "Undo the last tap") { sendEvent("UNDO", null) }
        root.addView(undo)
        eventStatus = Ui.text(c, "", 13f)
        root.addView(eventStatus)

        textsUi = Flavor.textsUi(this, prefs)
        textsUi.build(root)

        root.addView(Ui.heading(c, "Measurements"))
        root.addView(Ui.checkbox(c, "Speed tests over cellular (Cloudflare's speed test, about 1.3 MB each)", prefs.speedTests) {
            prefs.speedTests = it
            render()
        })
        root.addView(Ui.checkbox(c, "Data checks off Wi-Fi (one tiny request to Cloudflare's 1.1.1.1)", prefs.dataChecks) {
            prefs.dataChecks = it
            render()
        })

        root.addView(Ui.heading(c, "Household"))
        root.addView(Ui.button(c, "Share this household (add a phone)") { startActivity(Intent(c, ShareActivity::class.java)) })
        root.addView(Ui.button(c, "Scan an updated code") {
            startActivity(Intent(c, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_MODE, SetupActivity.MODE_JOIN))
        })
        root.addView(Ui.button(c, "Edit the household") {
            startActivity(Intent(c, SetupActivity::class.java).putExtra(SetupActivity.EXTRA_MODE, SetupActivity.MODE_EDIT))
        })
        root.addView(Ui.button(c, "Leave the household") { leave() })

        root.addView(Ui.heading(c, "Your data"))
        dataText = Ui.text(c, "", 13f)
        root.addView(dataText)
        root.addView(Ui.button(c, "Export the data") { export() })
        // With a server, the live report reads every phone's uploads; without one, the website's page reads exports.
        reportButton = Ui.button(c, "Open the report page") {
            val server = prefs.household?.server
            Ui.openUrl(c, if (server != null) Household.liveReportUrl(server) else BuildConfig.SITE_URL + "report/")
        }
        root.addView(reportButton)
        filesReportButton = Ui.button(c, "Report page for exported files") { Ui.openUrl(c, BuildConfig.SITE_URL + "report/") }
        root.addView(filesReportButton)
        uploadButton = Ui.button(c, "Upload to the server now") {
            if (prefs.household?.server == null) {
                Ui.toast(c, "This household has no server: the data stays on the phone.")
            } else if (Status.running) {
                startService(Intent(c, LoggerService::class.java).setAction(LoggerService.ACTION_UPLOAD))
                Ui.toast(c, "Uploading...")
            } else {
                Ui.toast(c, "Start recording first.")
            }
        }
        root.addView(uploadButton)
        root.addView(Ui.button(c, "Delete the recorded data") { deleteData() })
        dataStatus = Ui.text(c, "", 13f)
        root.addView(dataStatus)

        root.addView(Ui.heading(c, "About"))
        root.addView(
            Ui.text(
                c,
                "Family Coverage ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}), by StorieDev. Free and open " +
                    "source under the Apache License 2.0. No ads, no analytics, no account.",
                13f,
            ),
        )
        root.addView(Ui.button(c, "Website and help") { Ui.openUrl(c, BuildConfig.SITE_URL) })
        root.addView(Ui.button(c, "Privacy policy") { Ui.openUrl(c, BuildConfig.PRIVACY_URL) })
        if (BuildConfig.SOURCE_URL.isNotEmpty()) {
            root.addView(Ui.button(c, "Source code") { Ui.openUrl(c, BuildConfig.SOURCE_URL) })
        }
        if (BuildConfig.COFFEE_URL.isNotEmpty()) {
            root.addView(Ui.button(c, "☕  Buy me a coffee") { Ui.openUrl(c, BuildConfig.COFFEE_URL) })
        }

        Ui.show(this, ScrollView(c).apply { addView(root) })
        consentShown = prefs.consentAt == null
    }

    override fun onResume() {
        super.onResume()
        if (prefs.household == null || prefs.member == null) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }
        ui.post(refresh)
    }

    override fun onPause() {
        ui.removeCallbacks(refresh)
        if (::textsUi.isInitialized) textsUi.pause()
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        render()
    }

    private fun toggleRecording() {
        if (Status.running) {
            prefs.loggingEnabled = false
            startService(Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_STOP))
        } else {
            val h = prefs.household ?: return
            val missing = mutableListOf<String>()
            if (prefs.consentAt == null) missing += "tick the agreement"
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) missing += "allow precise location"
            if (!granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) missing += "allow location all the time"
            if (!granted(Manifest.permission.READ_PHONE_STATE)) missing += "allow phone access"
            if (Config.ended(h.end)) missing += "move the end date (it has passed)"
            if (missing.isNotEmpty()) {
                status.text = "Before starting: " + missing.joinToString(", ") + "."
                return
            }
            prefs.loggingEnabled = true
            startForegroundService(Intent(this, LoggerService::class.java))
            Watchdog.schedule(this) // a check every half hour that it still records, until it's stopped
        }
        ui.postDelayed({ render() }, 500L)
    }

    /** A call or text test: the service records it with each SIM's state (it owns the SIM readings). */
    private fun sendEvent(kind: String, outcome: String?) {
        if (!Status.running) {
            eventStatus.text = "Start recording first."
            return
        }
        val target = (sims.findViewById<RadioButton>(sims.checkedRadioButtonId)?.tag as? Int)
            ?: shownSims.singleOrNull()?.first
        if (kind != "UNDO" && target == null) {
            eventStatus.text = "First pick the number they called or texted."
            return
        }
        startService(
            Intent(this, LoggerService::class.java).setAction(LoggerService.ACTION_EVENT)
                .putExtra(LoggerService.EXTRA_KIND, kind)
                .putExtra(LoggerService.EXTRA_OUTCOME, outcome)
                .putExtra(LoggerService.EXTRA_SUB, target ?: -1),
        )
        eventStatus.text = "Recording..."
        ui.postDelayed({ render() }, 500L)
    }

    private fun eventButton(label: String, kind: String, outcome: String) = Ui.button(this, label) { sendEvent(kind, outcome) }

    /**
     * Says what location in the background is for, right before Android's own screen asks for it: Google Play
     * requires this disclosure, in the app, immediately before the request.
     */
    private fun backgroundLocationDisclosure() {
        val server = prefs.household?.server?.let { runCatching { Uri.parse(it).host }.getOrNull() ?: it }
        val where = if (server == null) {
            "It stays on this phone until you export it."
        } else {
            "It stays on this phone and is copied to $server, your household's server."
        }
        AlertDialog.Builder(this)
            .setTitle("Location in the background")
            .setMessage(
                "Family Coverage collects your precise location with each SIM's signal, to show which network works " +
                    "where. It does this in the background, even when the app is closed or not in use, while you're " +
                    "recording and until the household's end date. $where\n\nOn the next screen, choose \"Allow all " +
                    "the time\".",
            )
            .setPositiveButton("Continue") { _, _ ->
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    // ---- Places --------------------------------------------------------------------------------------------------

    /** Saves the current spot as a place: the logger's latest fix, or a fresh one when it isn't running. */
    private fun addPlace() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            Ui.toast(this, "Allow precise location first.")
            return
        }
        val recent = Status.lastFix?.takeIf { LocationTracker.fixAgeS(it) < 120 }
        if (recent != null) {
            placeDialog(recent)
            return
        }
        Ui.toast(this, "Finding where you are...")
        val lm = getSystemService(LocationManager::class.java)
        val provider = if (lm.hasProvider(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
        try {
            lm.getCurrentLocation(provider, null, mainExecutor) { loc ->
                if (loc == null) Ui.toast(this, "No location yet: try again outdoors.") else placeDialog(loc)
            }
        } catch (e: SecurityException) {
            Ui.toast(this, "Allow precise location first.")
        }
    }

    /** Adds a place centred on [fix], or edits [existing]: everything but where it is. */
    private fun placeDialog(fix: Location?, existing: Place? = null) {
        val h = prefs.household ?: return
        if (existing == null && h.places.size >= Household.MAX_PLACES) {
            Ui.toast(this, "That's the most places a household can have (${Household.MAX_PLACES}).")
            return
        }
        val pad = Ui.dp(this, 16)
        val box = Ui.vertical(this).apply { setPadding(pad, 0, pad, 0) }
        val name = EditText(this).apply {
            hint = "Home, school, work..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setText(existing?.name ?: "")
        }
        box.addView(name)
        box.addView(Ui.text(this, "How far around it counts:", 13f, top = 8))
        val radius = existing?.radiusM?.toInt() ?: 150
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        for (r in (listOf(100, 150, 250, 500) + radius).distinct().sorted()) {
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = "$r m"
                tag = r
                isChecked = r == radius
            })
        }
        box.addView(group)
        if (fix != null) {
            val accuracy = if (fix.hasAccuracy()) "±${fix.accuracy.toInt()} m" else "accuracy unknown"
            box.addView(Ui.text(this, "Your location now ($accuracy) is the centre.", 12f))
        }
        // Whose tests: every member ticked means everyone's, including members added later.
        val memberBoxes = if (h.members.size > 1) {
            box.addView(Ui.text(this, "Whose test texts it's for:", 13f, top = 8))
            h.members.map { m ->
                CheckBox(this).apply {
                    text = m
                    isChecked = existing?.isFor(m) ?: true
                }.also { box.addView(it) }
            }
        } else {
            emptyList()
        }
        box.addView(
            Ui.text(
                this,
                "Fallback times (optional): on these days, a test text goes out at each time unless one went out in " +
                    "the hour before, wherever the phone is. For a regular trip, such as the school run.",
                13f,
                top = 8,
            ),
        )
        val dayBoxes = Household.DAYS.map { d ->
            CheckBox(this).apply {
                text = d.lowercase(Locale.US).replaceFirstChar { it.uppercase() }
                // A place without times has no days of its own yet: start from weekdays, as a new place does.
                isChecked = if (existing?.times?.isNotEmpty() == true) d in existing.days else d != "SAT" && d != "SUN"
            }
        }
        for (week in listOf(dayBoxes.take(4), dayBoxes.drop(4))) {
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                for (b in week) addView(b)
            })
        }
        val times = EditText(this).apply {
            hint = "Times, such as 8:15, 15:20"
            // Plain text: Android's time keyboard has no comma or space, so "8:15, 15:20" couldn't be typed.
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(existing?.times?.joinToString(", ") ?: "")
        }
        box.addView(times)
        val error = Ui.text(this, "", 13f)
        box.addView(error)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Add this place" else "Edit ${existing.name}")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(if (existing == null) "Add" else "Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            // Set here rather than on the builder, so a mistake keeps the dialog open.
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val label = Household.cleanName(name.text.toString())
                        ?: throw IllegalArgumentException("give the place a name")
                    val slots = Household.cleanTimes(times.text.toString())
                    val days = Household.DAYS.filterIndexed { i, _ -> dayBoxes[i].isChecked }
                    require(slots.isEmpty() || days.isNotEmpty()) { "pick the days for those times" }
                    val chosen = memberBoxes.filter { it.isChecked }.map { it.text.toString() }
                    require(memberBoxes.isEmpty() || chosen.isNotEmpty()) { "pick at least one member" }
                    val current = prefs.household ?: return@setOnClickListener
                    val r = (group.findViewById<RadioButton>(group.checkedRadioButtonId)?.tag as? Int ?: 150).toDouble()
                    val members = if (chosen.size == memberBoxes.size) emptyList() else chosen
                    val place = when {
                        existing != null -> existing.copy(
                            name = label, radiusM = r, days = if (slots.isEmpty()) emptyList() else days,
                            times = slots, members = members,
                        )
                        fix != null -> Place(
                            id = Household.placeId(label, current.places.map { it.id }.toSet()),
                            name = label, lat = Csv.round(fix.latitude, 6), lon = Csv.round(fix.longitude, 6),
                            radiusM = r, days = if (slots.isEmpty()) emptyList() else days, times = slots,
                            members = members,
                        )
                        else -> return@setOnClickListener
                    }
                    prefs.household = current.withPlace(place)
                    Ui.toast(this, "${if (existing == null) "Added" else "Saved"} $label. Share the household " +
                        "again so the other phones get it.")
                    dialog.dismiss()
                    render()
                } catch (e: IllegalArgumentException) {
                    error.text = "✗  ${e.message?.replaceFirstChar { it.uppercase() }}."
                }
            }
        }
        dialog.show()
    }

    private fun renderPlaces() {
        val places = prefs.household?.places.orEmpty()
        // Rebuilt when the places change, and once a location arrives (for the distances); not on every refresh,
        // which would lose taps on Edit and Remove.
        val located = Status.lastFix != null
        if (places == shownPlaces && located == shownPlacesLocated) return
        shownPlaces = places
        shownPlacesLocated = located
        placesList.removeAllViews()
        if (places.isEmpty()) {
            placesList.addView(Ui.text(this, "No places yet.", 13f))
            return
        }
        val fix = Status.lastFix
        for (p in places) {
            val distance = fix?.let {
                val d = FloatArray(1)
                Location.distanceBetween(it.latitude, it.longitude, p.lat, p.lon, d)
                if (d[0] < 1000) " · ${d[0].toInt()} m away" else String.format(Locale.US, " · %.1f km away", d[0] / 1000)
            } ?: ""
            val who = if (p.members.isEmpty()) "" else "\n   for ${p.members.joinToString(", ")}"
            val slots = Household.describeTimes(p)?.let { "\n   tests $it" } ?: ""
            // Not baseline-aligned: that clipped a place's third line (its tests) beside the buttons.
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(Ui.text(this, "● ${p.name} (${p.radiusM.toInt()} m)$distance$who$slots", 14f),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.button(this, "Edit") { placeDialog(null, p) })
            row.addView(Ui.button(this, "Remove") {
                Ui.confirm(this, "Remove ${p.name}?", "The report stops comparing networks there, and test texts stop " +
                    "going out there. Recorded data isn't touched.", "Remove") {
                    prefs.household = prefs.household?.withoutPlace(p.id)
                    render()
                }
            })
            placesList.addView(row)
        }
    }

    // ---- Household and data -------------------------------------------------------------------------------------

    private fun leave() {
        if (Status.running) {
            Ui.toast(this, "Stop recording first.")
            return
        }
        Ui.confirm(this, "Leave ${prefs.household?.name}?", "This phone forgets the household, its places and your " +
            "agreement. What it recorded stays on it until you delete it here or uninstall the app.", "Leave") {
            prefs.household = null
            prefs.member = null
            prefs.consentAt = null
            prefs.resetServer()
            // Recording off for good, even if it had stopped by itself, so no notification asks to fix it.
            prefs.loggingEnabled = false
            Watchdog.cancel(this, prefs)
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
        }
    }

    private fun export() {
        val member = prefs.member ?: return
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, Exporter.fileName(member, LocalDate.now())),
            REQ_EXPORT,
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_EXPORT || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        dataStatus.text = "Exporting..."
        io.execute {
            val msg = try {
                val (files, bytes) = contentResolver.openOutputStream(uri)?.use { Exporter.write(prefs, CsvStore.of(this), it) }
                    ?: throw IllegalStateException("couldn't open the file")
                "Exported $files files (${bytes / 1024} KB of rows)."
            } catch (e: Exception) {
                "Export failed: ${e.message ?: e.javaClass.simpleName}"
            }
            ui.post { dataStatus.text = msg }
        }
    }

    private fun deleteData() {
        if (Status.running) {
            Ui.toast(this, "Stop recording first.")
            return
        }
        val store = CsvStore.of(this)
        val n = store.files().size
        Ui.confirm(this, "Delete everything recorded?", "This deletes this phone's $n data files " +
            "(${store.totalBytes() / 1024} KB) for good. Export first if you want to keep them. A server's copy " +
            "isn't touched.", "Delete") {
            for (f in store.files()) f.delete()
            prefs.saveOffsets(emptyMap())
            // As when leaving: recording stays off until Start, and nothing asks to fix it.
            prefs.loggingEnabled = false
            Watchdog.cancel(this, prefs)
            dataStatus.text = "Deleted $n files."
        }
    }

    // ---- Rendering ----------------------------------------------------------------------------------------------

    private fun renderEvents() {
        val choices = Status.simChoices
        if (choices != shownSims) {
            val kept = sims.findViewById<RadioButton>(sims.checkedRadioButtonId)?.tag as? Int
            sims.removeAllViews()
            val select = choices.firstOrNull { it.first == kept }?.first ?: choices.singleOrNull()?.first
            for ((subId, name) in choices) {
                val button = RadioButton(this).apply {
                    id = View.generateViewId()
                    text = name
                    tag = subId
                }
                sims.addView(button)
                if (subId == select) sims.check(button.id)
            }
            shownSims = choices
        }
        if (Status.lastEventAtMs != shownEventAt) {
            shownEventAt = Status.lastEventAtMs
            eventStatus.text = Status.lastEvent
        }
        // Undo only soon after a tap, and not twice.
        undo.isEnabled = Status.running && Status.lastEventAtMs > 0 &&
            System.currentTimeMillis() - Status.lastEventAtMs < 10 * 60_000L && !Status.lastEvent.contains("undone")
    }

    private fun render() {
        val h = prefs.household ?: return
        val member = prefs.member ?: return
        val end = h.end.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.getDefault()))
        header.text = "${h.name} · this is $member's phone · records until $end"
        consentText.text = Consent.text(h, Flavor.TEXTS_AUTOMATIC, prefs.speedTests, prefs.dataChecks)
        consentText.visibility = if (consentShown || prefs.consentAt == null) View.VISIBLE else View.GONE
        if (consent.isChecked != (prefs.consentAt != null)) {
            // Setup can clear the agreement (new terms): show that without the listener taking it as a tap.
            settingConsent = true
            consent.isChecked = prefs.consentAt != null
            settingConsent = false
        }
        consent.isEnabled = !Status.running
        consent.text = prefs.consentAt?.let { "Agreed on ${it.take(10)}." } ?: "I've read what this records, and I agree."
        renderEvents()
        renderPlaces()
        textsUi.render()
        // Rebuilt only when a step changes: rebuilding on every refresh lost taps that landed mid-rebuild.
        val visibleSteps = setupSteps.filter { it.visible() }
        val states = visibleSteps.map { it.label to it.done() }
        if (states != shownSteps) {
            shownSteps = states
            steps.removeAllViews()
            for ((s, state) in visibleSteps.zip(states)) {
                val ok = state.second
                steps.addView(Button(this).apply {
                    // A glyph and a word, never colour alone.
                    text = if (ok) "✓  ${s.label}: done" else "✗  ${s.label}: tap to allow"
                    isAllCaps = false
                    isEnabled = !ok
                    setOnClickListener { s.action() }
                })
            }
        }
        startButton.text = if (Status.running) "Stop recording" else "Start recording"
        uploadButton.visibility = if (h.server != null) View.VISIBLE else View.GONE
        val host = h.server?.let { runCatching { Uri.parse(it).host }.getOrNull() ?: it }
        reportButton.text = if (host != null) "Open the live report" else "Open the report page"
        filesReportButton.visibility = if (host != null) View.VISIBLE else View.GONE
        dataText.text = if (host != null) {
            "The phones copy what they record to $host, whose live report shows every phone's latest readings (it " +
                "asks for the report password your household set). Export saves this phone's recordings as one zip " +
                "file, for the report page on the website, which reads them in your browser and uploads nothing."
        } else {
            "Export saves everything this phone recorded as one zip file, wherever you choose. Open the zips from all " +
                "your phones together in the report page (on the website), which reads them in your browser and " +
                "uploads nothing."
        }

        val lastOk = prefs.lastUploadOkMs.takeIf { it > 0 }?.let {
            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
        } ?: "never"
        val store = CsvStore.of(this)
        status.text = buildString {
            appendLine(if (Status.running) "● Recording" else "○ Not recording")
            if (Status.note.isNotBlank()) appendLine("Note: ${Status.note}")
            appendLine("Last sample: ${Status.lastSample}")
            appendLine(Status.sims)
            appendLine("Location: ${Status.location}")
            appendLine("Power: ${Status.power}")
            appendLine("Mobile data today: ${Status.usage}")
            appendLine("Last data check: ${Status.lastCheck}")
            appendLine("Last speed test: ${Status.lastTest}")
            if (h.server != null) {
                appendLine("Last server test: ${Status.lastServer}")
                appendLine("Upload: ${Status.lastUpload} (last good: $lastOk)")
                appendLine("Server: household ${h.id}, device ${prefs.deviceId} (${prefs.deviceStatus})")
            }
            val bytes = store.totalBytes()
            appendLine("Stored: ${store.files().size} files, ${if (bytes in 1 until 1024) "under 1" else bytes / 1024} KB")
            append("App ${BuildConfig.VERSION_NAME}")
        }
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun batteryExempt() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

    companion object {
        private const val REQ_EXPORT = 10
    }
}
