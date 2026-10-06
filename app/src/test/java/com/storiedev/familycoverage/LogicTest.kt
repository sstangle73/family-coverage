package com.storiedev.familycoverage

import com.storiedev.familycoverage.StopCheck.Action
import com.storiedev.familycoverage.StopCheck.Reason
import com.storiedev.familycoverage.StopCheck.Start
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

class LogicTest {
    private val household = Household(
        id = "0a1b2c3d",
        name = "The Smiths",
        members = listOf("Alex", "Sam", "Jo"),
        end = LocalDate.of(2026, 11, 15),
        server = "https://coverage.example.com",
        places = listOf(
            Place("home", "Home", 42.3601, -71.0589),
            Place("school", "School", 42.37, -71.05, 250.0, listOf("MON", "TUE"), listOf("08:15"), listOf("Sam", "Jo")),
        ),
    )

    // ---- The household and its setup code ----------------------------------------------------------------------

    @Test
    fun setupCodesRoundTripAndSurviveBeingPastedIntoAMessage() {
        val code = Household.encode(household)
        assertTrue(code.startsWith(Household.CODE_PREFIX))
        assertTrue(code.matches(Regex("FC1\\.[A-Za-z0-9_-]+")))
        assertEquals(household, Household.decode(code))
        // Shared as text: wrapped in a sentence, broken over lines by a messaging app.
        val message = "Join our household: paste this.\n\n" + code.chunked(40).joinToString("\n") + "\n"
        assertEquals(household, Household.decode(message))
        // Without places, for a smaller QR code.
        assertEquals(household.copy(places = emptyList()), Household.decode(Household.encode(household, includePlaces = false)))
    }

    @Test
    fun badSetupCodesSayWhatsWrong() {
        fun reason(text: String): String = try {
            Household.decode(text)
            fail("decoded $text")
            ""
        } catch (e: IllegalArgumentException) {
            e.message ?: ""
        }
        assertTrue(reason("hello").contains("isn't a Family Coverage setup code"))
        assertTrue(reason("FC1.AAAAAAAAAAAA").contains("damaged"))
        val code = Household.encode(household)
        assertTrue(reason(code.dropLast(10)).contains("damaged"))
        val newer = JSONObject(household.toJson().toString()).put("v", 2)
        try {
            Household.fromJson(newer)
            fail("a newer format must be refused")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("newer version"))
        }
    }

    @Test
    fun householdJsonIsCleanedOnTheWayIn() {
        val o = JSONObject(household.toJson().toString())
            .put("members", org.json.JSONArray(listOf("  Alex  ", "Alex", "", "Sam\u0000", "Jo  Smith")))
            .put("places", org.json.JSONArray().put(JSONObject().put("id", "x").put("lat", 91.0).put("lon", 0.0))
                .put(JSONObject().put("id", "../etc").put("lat", 1.0).put("lon", 1.0))
                .put(JSONObject().put("id", "ok").put("name", "OK").put("lat", 1.0).put("lon", 1.0).put("r", 5)
                    .put("days", org.json.JSONArray(listOf("mon", "funday"))).put("times", org.json.JSONArray(listOf("08:15", "25:00")))))
        val h = Household.fromJson(o)
        assertEquals(listOf("Alex", "Jo Smith"), h.members) // trimmed, deduplicated, control characters refused
        assertEquals(1, h.places.size) // an impossible latitude and a path-like id are dropped
        val p = h.places.single()
        assertEquals(Place.MIN_RADIUS_M, p.radiusM, 0.0) // clamped
        assertEquals(listOf("MON"), p.days)
        assertEquals(listOf("08:15"), p.times)
        assertEquals(emptyList<String>(), p.members)
        // A place's members must be the household's: others are dropped.
        val m = JSONObject(household.toJson().toString())
        m.getJSONArray("places").getJSONObject(1).put("m", org.json.JSONArray(listOf("Sam", "Pat", " Jo ")))
        assertEquals(listOf("Sam", "Jo"), Household.fromJson(m).places[1].members)
    }

    @Test
    fun placesForSomeMembersAndFallbackTimes() {
        val school = household.places[1]
        assertTrue(school.isFor("Sam"))
        assertFalse(school.isFor("Alex"))
        assertTrue(household.places[0].isFor("Alex")) // no members named: everyone's
        // Members change: a place keeps the ones still there, and with none left it's everyone's.
        assertEquals(listOf("Sam"), household.withMembers(listOf("Alex", "Sam")).places[1].members)
        assertEquals(emptyList<String>(), household.withMembers(listOf("Alex")).places[1].members)
        // Editing a place keeps its spot in the list.
        val renamed = household.withPlace(household.places[0].copy(name = "Our house"))
        assertEquals(listOf("home", "school"), renamed.places.map { it.id })
        assertEquals("Our house", renamed.places[0].name)
        // Times as people type them.
        assertEquals(listOf("08:15", "15:20"), Household.cleanTimes(" 15:20, 8:15 8.15"))
        assertEquals(emptyList<String>(), Household.cleanTimes("  "))
        for (bad in listOf("8", "24:00", "8:60", "quarter past", "1,2,3,4,5,6,7".split(",").joinToString(" ") { "0$it:00" })) {
            try {
                Household.cleanTimes(bad)
                fail("accepted $bad")
            } catch (expected: IllegalArgumentException) {
            }
        }
        fun times(days: List<String>, vararg t: String) = Household.describeTimes(school.copy(days = days, times = t.toList()))
        assertEquals("Mon-Fri 08:15, 15:20", times(listOf("MON", "TUE", "WED", "THU", "FRI"), "08:15", "15:20"))
        assertEquals("Sat, Sun 10:00", times(listOf("SUN", "SAT"), "10:00"))
        assertEquals("every day 07:00", times(Household.DAYS, "07:00"))
        assertEquals("Mon, Wed, Fri 07:00", times(listOf("MON", "WED", "FRI"), "07:00"))
        assertNull(times(listOf("MON")))
    }

    @Test
    fun serverAddressesAreChecked() {
        assertEquals("https://coverage.example.com", Household.cleanServer(" https://coverage.example.com/ "))
        assertEquals("http://192.168.1.20:8745", Household.cleanServer("http://192.168.1.20:8745"))
        assertNull(Household.cleanServer("   "))
        for (bad in listOf("ftp://x.example", "coverage.example.com", "https://user:pw@x.example", "https://x.example/?k=1", "https://")) {
            try {
                Household.cleanServer(bad)
                fail("accepted $bad")
            } catch (expected: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun liveReportIsOnTheHouseholdServer() {
        assertEquals("https://coverage.example.com/report/", Household.liveReportUrl("https://coverage.example.com"))
        assertEquals("http://192.168.1.20:8745/report/", Household.liveReportUrl("http://192.168.1.20:8745/"))
    }

    @Test
    fun placeIdsAreReadableAndUnique() {
        assertEquals("home", Household.placeId("Home", emptySet()))
        assertEquals("grandma-s-house", Household.placeId("Grandma's House", emptySet()))
        assertEquals("home-2", Household.placeId("Home", setOf("home")))
        assertEquals("home-3", Household.placeId("HOME", setOf("home", "home-2")))
        assertEquals("place", Household.placeId("学校", emptySet())) // no ASCII letters: a plain fallback
    }

    @Test
    fun memberNames() {
        assertEquals("Mom", Household.cleanMember("  Mom "))
        assertEquals("Mary Ann", Household.cleanMember("Mary   Ann"))
        assertNull(Household.cleanMember(""))
        assertNull(Household.cleanMember("x".repeat(31)))
        assertEquals("tab here", Household.cleanMember("tab\there")) // whitespace is just a space
        assertNull(Household.cleanMember("bell\u0007"))
    }

    // ---- Where data may go ---------------------------------------------------------------------------------------

    @Test
    fun plainHttpOnlyToTheHomeNetwork() {
        val home = listOf(InetAddress.getByName("192.168.1.20"))
        val tailnet = listOf(InetAddress.getByName("100.101.102.103"))
        val public = listOf(InetAddress.getByName("104.21.93.242"))
        assertTrue(UrlRules.allowed("http", home))
        assertTrue(UrlRules.allowed("http", tailnet))
        assertTrue(UrlRules.allowed("http", listOf(InetAddress.getByName("fd7a:115c:a1e0::1"))))
        assertFalse(UrlRules.allowed("http", public))
        assertFalse(UrlRules.allowed("http", home + public)) // every address must be private
        assertFalse(UrlRules.allowed("http", emptyList()))
        assertTrue(UrlRules.allowed("https", public))
        assertFalse(UrlRules.allowed("ftp", home))
        assertFalse(UrlRules.isPrivate(InetAddress.getByName("100.200.1.1")))
    }

    @Test
    fun serverCheckTellsAFamilyCoverageServerFromAnythingElse() {
        fun verdict(code: Int, body: String = "", ping: Int? = null) = ServerCheck.classify(code, body, ping).verdict
        val v = ServerCheck.Verdict.entries.associateBy { it.name }
        assertEquals(v["OK"], verdict(200, """{"app":"family-coverage","api":1,"household":"any"}"""))
        assertEquals(v["OK"], verdict(200, """{"app":"family-coverage","api":1,"household":"ok"}"""))
        assertEquals(v["OTHER_HOUSEHOLD"], verdict(200, """{"app":"family-coverage","api":1,"household":"other"}"""))
        assertEquals(v["NOT_FAMILY_COVERAGE"], verdict(200, """{"ok":true}""")) // something else that answers
        assertEquals(v["NOT_FAMILY_COVERAGE"], verdict(200, "<html>a web page</html>"))
        assertEquals(v["OLDER_SERVER"], verdict(404, ping = 401)) // before /api/hello: the server test wants a key
        assertEquals(v["NOT_FAMILY_COVERAGE"], verdict(404, ping = 404)) // a Coverage Log server, say
        assertEquals(v["NOT_FAMILY_COVERAGE"], verdict(400))
        assertEquals(v["REFUSED"], verdict(403))
        val moved = ServerCheck.classify(301, "", null, "https://coverage.example.com/")
        assertEquals(v["REDIRECTS"], moved.verdict)
        assertTrue(ServerCheck.describe(moved, "0a1b2c3d").contains("https://coverage.example.com/"))
        assertTrue(ServerCheck.describe(ServerCheck.Result(ServerCheck.Verdict.OTHER_HOUSEHOLD), "0a1b2c3d").contains("0a1b2c3d"))
        // Every verdict reads with a glyph as well as words: ✓ fine, ✗ won't work, ▲ might work from elsewhere.
        for (x in ServerCheck.Verdict.entries) {
            val text = ServerCheck.describe(ServerCheck.Result(x), "0a1b2c3d")
            assertTrue(text, text.startsWith(if (x.ok) "✓" else if (x.name in setOf("REFUSED", "UNREACHABLE")) "▲" else "✗"))
        }
    }

    @Test
    fun consentNamesTheRealEndDateAndServer() {
        val withServer = Consent.text(household, automaticTexts = true, speedTests = true, dataChecks = true)
        assertTrue(withServer.contains("15 November 2026"))
        assertTrue(withServer.contains("coverage.example.com"))
        assertTrue(withServer.contains("The Smiths"))
        assertTrue(withServer.contains("Cloudflare"))
        // Google Play's prominent disclosure for background location needs these words.
        assertTrue(withServer.contains("in the background, even when the app is closed or not in use"))
        val local = Consent.text(household.copy(server = null), automaticTexts = false, speedTests = false, dataChecks = false)
        assertTrue(local.contains("stays on this phone"))
        assertFalse(local.contains("example.com"))
        assertFalse(local.contains("Cloudflare"))
        assertTrue(local.contains("reminds you"))
    }

    // ---- The export ----------------------------------------------------------------------------------------------

    @Test
    fun exportNamesAndManifest() {
        assertEquals("family-coverage-mary-ann-2026-10-01.zip", Exporter.fileName("Mary Ann", LocalDate.of(2026, 10, 1)))
        assertEquals("family-coverage-member-2026-10-01.zip", Exporter.fileName("学校", LocalDate.of(2026, 10, 1)))
        val m = Exporter.manifest(household, "Sam", "0123456789abcdef", listOf("csv/samples-2026-10-01.csv"), null)
        assertEquals(Exporter.FORMAT, m.getString("format"))
        assertEquals("Sam", m.getString("member"))
        assertEquals(2, m.getJSONObject("household").getJSONArray("places").length())
        assertEquals(Tables.SAMPLES.size, m.getJSONObject("tables").getJSONArray("samples").length())
    }

    @Test
    fun exportCutsAPartRow() {
        val dir = Files.createTempDirectory("fc").toFile()
        val f = File(dir, "samples-2026-10-01.csv")
        f.writeText("a,b\n1,2\n3,")
        assertArrayEquals("a,b\n1,2\n".toByteArray(), Exporter.completeRows(f))
        f.writeText("a,b")
        assertNull(Exporter.completeRows(f))
        dir.deleteRecursively()
    }

    // ---- Carried over from the logger ----------------------------------------------------------------------------

    @Test
    fun serviceStateKeepsAirplaneModeApartFromADeadSpot() {
        assertEquals("POWER_OFF", CellMath.serviceState(3, dataRegistered = false))
        assertEquals("OUT_OF_SERVICE", CellMath.serviceState(1, dataRegistered = false))
        assertEquals("IN_SERVICE", CellMath.serviceState(1, dataRegistered = true)) // data-only registration
        assertEquals("EMERGENCY_ONLY", CellMath.serviceState(2, dataRegistered = false))
    }

    @Test
    fun cellServiceIsNotWifiCalling() {
        fun reg(wlan: Boolean, registered: Boolean = true) =
            CellMath.Reg(wlan = wlan, cs = !wlan, ps = true, registered = registered, tech = CellMath.NETWORK_TYPE_LTE)
        // Android says IN_SERVICE for a line on Wi-Fi calling alone; the cell says otherwise.
        assertEquals("OUT_OF_SERVICE", CellMath.cellService(0, listOf(reg(wlan = true), reg(wlan = false, registered = false))))
        assertEquals("OUT_OF_SERVICE", CellMath.cellService(0, listOf(reg(wlan = true))))
        assertEquals("IN_SERVICE", CellMath.cellService(0, listOf(reg(wlan = true), reg(wlan = false))))
        assertEquals("IN_SERVICE", CellMath.cellService(1, listOf(reg(wlan = false)))) // data-only registration
        assertEquals("EMERGENCY_ONLY", CellMath.cellService(2, listOf(reg(wlan = false, registered = false))))
        assertEquals("POWER_OFF", CellMath.cellService(3, listOf(reg(wlan = true))))
        // No registrations listed: ServiceState's own word.
        assertEquals("IN_SERVICE", CellMath.cellService(0, emptyList()))
        assertEquals("OUT_OF_SERVICE", CellMath.cellService(1, emptyList()))
    }

    @Test
    fun ratRules() {
        fun rat(nr: Boolean = false, lte: Boolean = false, leg: Boolean = false, umts: Boolean = false, ps: Int? = null, st: String = "IN_SERVICE") =
            CellMath.rat(st, nr, lte, leg, umts, ps)
        assertEquals("NR_SA", rat(nr = true))
        assertEquals("NR_NSA", rat(lte = true, leg = true))
        assertEquals("LTE", rat(lte = true))
        assertEquals("UMTS", rat(umts = true))
        assertEquals("LTE", rat(ps = CellMath.NETWORK_TYPE_LTE)) // no cell list yet: fall back to the registration
        assertEquals("NONE", rat(lte = true, st = "OUT_OF_SERVICE"))
        assertEquals("NONE", rat(lte = true, st = "POWER_OFF"))
        assertEquals("NONE", rat())
    }

    @Test
    fun voiceTransportPrefersWifiCallingThenTheDataRegistration() {
        val lte = CellMath.Reg(wlan = false, cs = false, ps = true, registered = true, tech = CellMath.NETWORK_TYPE_LTE)
        val wlan = CellMath.Reg(wlan = true, cs = false, ps = true, registered = true, tech = 18)
        assertEquals("IWLAN", CellMath.voiceTransport(listOf(lte, wlan)))
        assertEquals("LTE", CellMath.voiceTransport(listOf(lte, wlan.copy(registered = false))))
        assertEquals("NR", CellMath.voiceTransport(listOf(lte.copy(tech = CellMath.NETWORK_TYPE_NR))))
        assertEquals("CS", CellMath.voiceTransport(listOf(CellMath.Reg(false, true, false, true, 3))))
        assertEquals("NONE", CellMath.voiceTransport(emptyList()))
    }

    @Test
    fun signalRangesDropUnavailableAndOutOfRangeValues() {
        assertEquals(-95, CellMath.rsrp(-95))
        assertNull(CellMath.rsrp(Int.MAX_VALUE))
        assertNull(CellMath.rsrp(-150))
        assertEquals(-3, CellMath.rssnr(-3))
        assertNull(CellMath.ssSinr(99))
    }

    @Test
    fun csvFormatting() {
        assertEquals("\"AT&T, Inc\"", Csv.escape("AT&T, Inc"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.escape("say \"hi\""))
        assertEquals("42.3565123", Csv.number(42.35651234))
        assertEquals("12.5", Csv.number(12.5))
        assertEquals("3", Csv.number(3.0))
        assertEquals("", Csv.number(Double.NaN))
        assertEquals("a,,true,1.5\n", Csv.line(listOf("a", null, true, 1.5)))
        val t = OffsetDateTime.of(2026, 10, 1, 8, 30, 0, 412_000_000, ZoneOffset.ofHours(-4))
        assertEquals("2026-10-01T08:30:00.412-04:00", Csv.ts(t))
    }

    @Test
    fun statsForTheSpeedTest() {
        assertEquals(20.0, Stats.median(listOf(30.0, 10.0, 20.0))!!, 1e-9)
        assertEquals(15.0, Stats.median(listOf(10.0, 20.0))!!, 1e-9)
        assertEquals(10.0, Stats.meanAbsDiff(listOf(10.0, 20.0, 10.0))!!, 1e-9)
        assertEquals(8.0, Stats.mbps(1_000_000, 1.0)!!, 1e-9)
        assertNull(Stats.mbps(0, 1.0))
    }

    @Test
    fun uploaderNeverSplitsARow() {
        val dir = Files.createTempDirectory("fc").toFile()
        val f = File(dir, "samples-2026-10-01.csv")
        f.writeText("a,b\n1,2\n3,4")
        assertArrayEquals("a,b\n1,2\n".toByteArray(), Uploader.readLines(f, 0, 1024))
        assertArrayEquals("1,2\n".toByteArray(), Uploader.readLines(f, 4, 1024))
        assertNull(Uploader.readLines(f, 8, 1024)) // "3,4" has no newline yet
        dir.deleteRecursively()
    }

    @Test
    fun csvStoreWritesTheHeaderOnceAndChecksTheWidth() {
        val dir = Files.createTempDirectory("fc").toFile()
        val store = CsvStore(dir)
        val t = OffsetDateTime.of(2026, 10, 1, 8, 30, 0, 0, ZoneOffset.ofHours(-4))
        val row = listOf<Any?>(Csv.ts(t), "Sam", "RUNNING", 83, false, "0.1.1")
        store.append("heartbeat", t, row)
        store.append("heartbeat", t, row)
        val lines = File(dir, "heartbeat-2026-10-01.csv").readLines()
        assertEquals(Tables.HEARTBEAT.joinToString(","), lines[0])
        assertEquals(3, lines.size)
        assertEquals(listOf("heartbeat-2026-10-01.csv"), store.files().map { it.name })
        try {
            store.append("heartbeat", t, row.dropLast(1))
            fail("a short row must be refused")
        } catch (expected: IllegalArgumentException) {
        }
        dir.deleteRecursively()
    }

    @Test
    fun tablesMatchTheDataFormat() {
        assertEquals(38, Tables.SAMPLES.size)
        assertEquals(25, Tables.TESTS.size)
        assertEquals(25, Tables.SERVER.size)
        assertEquals(14, Tables.USAGE.size)
        assertEquals(18, Tables.CHECKS.size)
        assertEquals(18, Tables.EVENTS.size)
        assertEquals(26, Tables.TEXTS.size)
        for (t in Tables.ALL.keys) assertTrue(t, CsvStore.NAME.matches("$t-2026-09-27.csv"))
        for (cols in Tables.ALL.values) assertEquals(listOf("ts", "member"), cols.take(2))
        for (cols in Tables.ALL.values) assertEquals(cols.size, cols.toSet().size)
    }

    @Test
    fun csvStoreKeepsAnOlderFilesLayout() {
        val dir = Files.createTempDirectory("fc").toFile()
        val t = OffsetDateTime.of(2026, 9, 26, 16, 0, 0, 0, ZoneOffset.ofHours(-4))
        val old = Tables.TESTS.dropLast(2)
        File(dir, "tests-2026-09-26.csv").writeText(old.joinToString(",") + "\n")
        val store = CsvStore(dir)
        val row = Tables.TESTS.map { col -> if (col == "result") "OK" else if (col == "test_path") "default" else null }
        store.append("tests", t, row)
        val lines = File(dir, "tests-2026-09-26.csv").readLines()
        assertEquals(old.size, lines[1].split(",").size) // written in the file's own layout
        assertTrue(lines[1].contains("OK"))
        assertFalse(lines[1].contains("default"))
        store.append("tests", t.plusDays(1), row)
        val next = File(dir, "tests-2026-09-27.csv").readLines()
        assertEquals(Tables.TESTS.joinToString(","), next[0])
        assertTrue(next[1].endsWith("OK,default,"))
        dir.deleteRecursively()
    }

    @Test
    fun displayOverrideAndBandwidth() {
        assertEquals("NONE", CellMath.displayOverride(0))
        assertEquals("NR_ADVANCED", CellMath.displayOverride(5))
        assertNull(CellMath.displayOverride(42))
        assertEquals(2 to 30.0, CellMath.bandwidth(intArrayOf(20_000, 10_000)))
        assertEquals(null to null, CellMath.bandwidth(null))
    }

    @Test
    fun testTextTagsRoundTripAndNothingElsePasses() {
        val body = TextMath.tag("test", "7F3A12", LocalTime.of(13, 30, 5))
        assertEquals("FC test 7F3A12 13:30:05", body)
        assertEquals(TextMath.Tag("test", "7F3A12", LocalTime.of(13, 30, 5)), TextMath.parse(body))
        assertEquals("echo", TextMath.parse("FC echo 00AB9F 09:01:59")?.role)
        assertNull(TextMath.parse("CL test 7F3A12 13:30:05")) // the family logger's tag is not ours
        assertNull(TextMath.parse("FC test 7F3A12 13:30:05 and more"))
        assertNull(TextMath.parse("Your code is 123456"))
        assertNull(TextMath.parse("FC test 7f3a12 13:30:05"))
        assertNull(TextMath.parse(null))
        assertTrue(TextMath.newId().matches(Regex("[0-9A-F]{6}")))
    }

    @Test
    fun testTextNumbersMatchWithOrWithoutTheCountryCode() {
        assertTrue(TextMath.sameNumber("+14135550123", "(413) 555-0123"))
        assertTrue(TextMath.sameNumber("+447700900123", "07700 900123"))
        assertFalse(TextMath.sameNumber("+14135550123", "+14135550124"))
        assertFalse(TextMath.sameNumber("+14135550123", null))
        assertNull(TextMath.number("611"))
    }

    @Test
    fun testTextTimingRules() {
        assertTrue(TextMath.quiet(LocalTime.of(21, 0)))
        assertFalse(TextMath.quiet(LocalTime.of(7, 0)))
        assertEquals(20.0, TextMath.latency(LocalTime.of(23, 59, 50), LocalTime.of(0, 0, 10)), 1e-9)
        val noon = 12 * 3_600_000L
        val t = LocalTime.of(12, 0)
        assertNull(TextMath.mayStart(noon, t, 0, 0L, 8, manual = false))
        assertEquals("quiet hours", TextMath.mayStart(noon, LocalTime.of(22, 0), 0, 0L, 8, manual = false))
        assertEquals("too soon after the last test", TextMath.mayStart(noon, t, 1, noon - 19 * 60_000L, 8, manual = false))
        assertEquals("today's limit reached", TextMath.mayStart(noon, t, 8, 0L, 8, manual = true))
        val tue = LocalDateTime.of(2026, 9, 29, 9, 35)
        val days = setOf(DayOfWeek.TUESDAY)
        assertTrue(TextMath.slotDue(tue, days, LocalTime.of(9, 30)))
        assertFalse(TextMath.slotDue(tue.withMinute(50), days, LocalTime.of(9, 30)))
        assertEquals(DayOfWeek.TUESDAY, TextMath.day("TUE"))
    }

    @Test
    fun testTextsRouteSilentOnlyWhereBothEndsCarryIt() {
        val visible = "4135550100"
        val att = "5185550100"
        assertEquals("data" to visible, TextMath.route(true, att, false, visible, true))
        assertEquals("text" to visible, TextMath.route(false, att, false, visible, true))
        assertEquals("text" to att, TextMath.route(true, att, false, null, false))
        assertNull(TextMath.route(true, null, true, "12", true))
        assertTrue(17 in TextMath.SILENT_UNSUPPORTED)
        assertFalse(4 in TextMath.SILENT_UNSUPPORTED)
        assertTrue("310410" in TextMath.NO_SILENT_PLMNS)
    }

    @Test
    fun placeMembership() {
        assertEquals(TextMath.Where.INSIDE, TextMath.where(140.0, 20.0, 150.0))
        assertEquals(TextMath.Where.UNKNOWN, TextMath.where(215.0, 20.0, 150.0))
        assertEquals(TextMath.Where.OUTSIDE, TextMath.where(260.0, 20.0, 150.0))
    }

    @Test
    fun batteryPaceSleepsWhileStillAndEveryHouseholdEnds() {
        assertEquals(10_000L, Cadence.tickMs(moving = true))
        assertEquals(120_000L, Cadence.tickMs(moving = false))
        assertEquals(60 * 60_000L, Cadence.serverMs(moving = true))
        assertTrue(Cadence.skipSpeedTest(wifi = true, vpn = true))
        assertFalse(Cadence.skipSpeedTest(wifi = false, vpn = true))
        assertTrue(Moves.left(150.0, 20.0))
        assertFalse(Moves.left(80.0, 20.0))
        val end = LocalDate.of(2026, 11, 15)
        assertFalse(Config.ended(end, OffsetDateTime.of(2026, 11, 15, 23, 59, 0, 0, ZoneOffset.UTC)))
        assertTrue(Config.ended(end, OffsetDateTime.of(2026, 11, 16, 0, 0, 1, 0, ZoneOffset.UTC)))
        assertFalse(Config.ended(null))
    }

    @Test
    fun usageCounters() {
        assertEquals(2_000L, UsageMeter.deltaTotal(1_000_000, 1_002_000))
        assertEquals(0L, UsageMeter.deltaTotal(1_000_000, 400_000))
        assertTrue(UsageMeter.unreadable(5_000, 0))
        assertFalse(UsageMeter.unreadable(5_000, 300))
        assertEquals(300L, UsageMeter.delta(1_000, 300))
    }

    @Test
    fun dataCheckReadsCloudflaresAnswer() {
        val a = CheckMath.parse("HTTP/1.1 301 Moved Permanently\r\nServer: cloudflare\r\nCF-RAY: a415d99e2caec0f3-ORD\r\n\r\n")
        assertEquals("OK", CheckMath.result(a))
        assertEquals("ORD", a.colo)
        assertEquals("HTTP_ERROR", CheckMath.result(CheckMath.parse("HTTP/1.1 302 Found\r\nLocation: http://topup.example/\r\n\r\n")))
        assertEquals("NO_DATA_NETWORK", CheckMath.classify("connect failed: ENETUNREACH (Network is unreachable)", true))
        assertNotNull(CheckMath.parse("").status ?: "none")
    }

    // ---- When recording stops by itself --------------------------------------------------------------------------

    /** The watchdog's decision for a phone that should be recording and isn't, with only what differs named. */
    private fun stopCheck(
        missing: Set<Reason> = emptySet(),
        exempt: Boolean = true,
        start: Start = Start.NOT_TRIED,
        last: Reason? = null,
        lastMs: Long = 0L,
        now: Long = 30 * StopCheck.ALERT_GAP_MS,
        running: Boolean = false,
        enabled: Boolean = true,
        agreed: Boolean = true,
        setUp: Boolean = true,
        ended: Boolean = false,
    ) = StopCheck.decide(setUp, agreed, enabled, ended, running, missing, exempt, start, last, lastMs, now)

    @Test
    fun stoppedRecordingSaysWhyAndWhatToTap() {
        // A permission taken back: the most basic one missing names it (no location at all, before its parts).
        assertEquals(Action.Alert(Reason.LOCATION), stopCheck(missing = setOf(Reason.LOCATION, Reason.BACKGROUND)))
        assertEquals(Action.Alert(Reason.PRECISE), stopCheck(missing = setOf(Reason.PRECISE)))
        assertEquals(Action.Alert(Reason.BACKGROUND), stopCheck(missing = setOf(Reason.BACKGROUND)))
        assertEquals(Action.Alert(Reason.PHONE), stopCheck(missing = setOf(Reason.PHONE)))
        assertEquals(Action.Alert(Reason.LOCATION), stopCheck(missing = setOf(Reason.PHONE, Reason.LOCATION)))
        // Everything there, but the start refused: without the battery exemption, that's the fix to ask for.
        assertEquals(Action.Alert(Reason.BATTERY), stopCheck(exempt = false, start = Start.FAILED))
        assertEquals(Action.Alert(Reason.OTHER), stopCheck(exempt = true, start = Start.FAILED))
        // The logger's own failed start names a permission that's off before the battery.
        assertEquals(Action.Alert(Reason.PHONE), stopCheck(missing = setOf(Reason.PHONE), exempt = false, start = Start.FAILED))
        // Each says what happened and what to tap, in words of its own.
        for (reason in Reason.entries) assertTrue(reason.text, reason.text.contains(". Tap"))
        assertEquals(Reason.entries.size, Reason.entries.map { it.text }.toSet().size)
        assertEquals("Location access was turned off. Tap to allow it again.", Reason.LOCATION.text)
        assertEquals("Android didn't let it restart. Tap, then set Battery to Unrestricted.", Reason.BATTERY.text)
    }

    @Test
    fun theWatchdogRestartsRecordingWhenEverythingIsThere() {
        assertEquals(Action.Restart, stopCheck())
        assertEquals(Action.Restart, stopCheck(exempt = false)) // worth trying: the app may be on screen
        assertEquals(Action.None, stopCheck(start = Start.STARTED)) // the logger clears any notice once it runs
        assertEquals(Action.None, stopCheck(running = true))
        // A permission that's off: no restart (it would fail), the notice instead.
        assertEquals(Action.Alert(Reason.BACKGROUND), stopCheck(missing = setOf(Reason.BACKGROUND)))
        // The daily limit never holds back a restart.
        assertEquals(Action.Restart, stopCheck(last = Reason.BATTERY, lastMs = 30 * StopCheck.ALERT_GAP_MS - 60_000L))
    }

    @Test
    fun noStopNoticeWhenThePersonStoppedItHasntAgreedOrTheEndDatePassed() {
        val off = setOf(Reason.LOCATION, Reason.BACKGROUND)
        for (start in Start.entries) {
            assertEquals(Action.None, stopCheck(missing = off, start = start, enabled = false)) // they stopped it
            assertEquals(Action.None, stopCheck(missing = off, start = start, agreed = false))
            assertEquals(Action.None, stopCheck(missing = off, start = start, setUp = false))
            assertEquals(Action.None, stopCheck(missing = off, start = start, ended = true)) // the end notice says it
            assertEquals(Action.None, stopCheck(exempt = false, start = start, enabled = false))
        }
    }

    @Test
    fun aStopNoticeAtMostOnceADayForEachReason() {
        val day = StopCheck.ALERT_GAP_MS
        val now = 30 * day
        val off = setOf(Reason.LOCATION)
        fun again(lastMs: Long, last: Reason = Reason.LOCATION) =
            stopCheck(missing = off, last = last, lastMs = lastMs, now = now)
        assertEquals(Action.None, again(now - 60 * 60_000L))
        assertEquals(Action.None, again(now - day + 1))
        assertEquals(Action.Alert(Reason.LOCATION), again(now - day))
        // A different reason shows at once.
        assertEquals(Action.Alert(Reason.LOCATION), again(now - 60_000L, last = Reason.PHONE))
        val refused = stopCheck(exempt = false, start = Start.FAILED, last = Reason.LOCATION, lastMs = now, now = now)
        assertEquals(Action.Alert(Reason.BATTERY), refused)
        // A clock set back doesn't keep it quiet until the clock catches up.
        assertEquals(Action.Alert(Reason.LOCATION), again(now + day))
    }

    @Test
    fun theEndNoticeSaysWhichDatePassedAndWhereTheDataIs() {
        val end = LocalDate.of(2026, 11, 15)
        assertEquals(
            "Recording has ended because the household's end date (15 November 2026) passed. Export the data from the app.",
            StopCheck.endText(end, server = false),
        )
        assertTrue(StopCheck.endText(end, server = true).endsWith("passed. The data is on your household's server."))
    }
}
