package com.storiedev.familycoverage

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Timing and fixed limits. Everything about one household (people, end date, server, places) is in [Household]. */
object Config {
    /** How long a sample waits for the modem's fresh cell list before writing with what it has. */
    const val CELL_WAIT_MS = 3_000L
    /** The server test: smaller than the carrier test (pace: Cadence). */
    const val SERVER_DOWN_BYTES = 500_000
    const val SERVER_UP_BYTES = 125_000
    /** Mobile data use: one row per interval, and a row whenever the data SIM changes. */
    const val USAGE_INTERVAL_MS = 120_000L
    /** The data check: a tiny request off Wi-Fi, to catch places with bars but no data (pace: Cadence). */
    const val CHECK_TIMEOUT_MS = 5_000
    /** Test texts: started after this long at a place, this far apart, at most this many per visit. */
    const val TEXT_DWELL_MS = 10 * 60_000L
    const val TEXT_SPACING_MS = 20 * 60_000L
    const val TEXT_VISIT_MAX = 2
    /** Exchanges a day; each sends one test from every chosen SIM. Halved when the texts are visible. */
    const val TEXT_DAY_MAX = 8
    const val TEXT_DAY_MAX_VISIBLE = 4
    /** Replies a day: the other phone's 8 exchanges from 2 SIMs, and room for a few tapped tests. */
    const val TEXT_ECHO_DAY_MAX = 20
    /** A fallback time fires only if no test went out in the hour before it. */
    const val TEXT_FALLBACK_GAP_MS = 60 * 60_000L
    const val TEXT_VISIT_END_MS = 5 * 60_000L
    const val TEXT_QUIET_START = 21
    const val TEXT_QUIET_END = 7
    /** A household always has an end date: the logs are location history. The longest it may run, and the default. */
    const val MAX_DAYS = 366L
    const val DEFAULT_DAYS = 42L

    /** Logging stops after the end date (local time); it starts again only if someone moves the date. */
    fun ended(end: LocalDate?, now: OffsetDateTime = OffsetDateTime.now()): Boolean =
        end != null && now.toLocalDate().isAfter(end)
}

/**
 * How often each job runs. Moving means GPS is on: samples every 10 s. Still, the phone sleeps between readings,
 * woken about every 2 minutes (longer when Android has it deeply idle). Measurements that can't say anything new
 * while the phone sits in one spot run less often; nothing measures during a call.
 */
object Cadence {
    fun tickMs(moving: Boolean) = if (moving) 10_000L else 120_000L
    fun sampleMs(moving: Boolean) = tickMs(moving)
    const val HEARTBEAT_MS = 120_000L
    /** Off Wi-Fi only. */
    fun checkMs(moving: Boolean) = if (moving) 120_000L else 600_000L
    fun testMs(moving: Boolean) = if (moving) 30 * 60_000L else 60 * 60_000L
    /** Off Wi-Fi only, and only with a server set. */
    fun serverMs(moving: Boolean) = if (moving) 60 * 60_000L else 3 * 3_600_000L
    fun uploadMs(wifi: Boolean) = if (wifi) 15 * 60_000L else 60 * 60_000L
    fun textMs(moving: Boolean) = if (moving) 60_000L else 120_000L
    const val FIRST_CHECK_MS = 45_000L
    const val FIRST_TEST_MS = 2 * 60_000L
    const val FIRST_SERVER_MS = 7 * 60_000L
    const val FIRST_UPLOAD_MS = 30_000L

    /** On Wi-Fi inside a VPN that allows no bypass, a carrier test can't reach the carrier: don't run it (no row). */
    fun skipSpeedTest(wifi: Boolean, vpn: Boolean) = wifi && vpn

    /** A job is due when its interval has passed since it last ran. */
    fun due(lastMs: Long, intervalMs: Long, nowMs: Long) = nowMs - lastMs >= intervalMs
}

/** Column lists: the export's data format (docs/data-format.md). Columns are only ever added at the end. */
object Tables {
    val SAMPLES = listOf(
        "ts", "member", "sub_id", "sub_label", "carrier", "mcc_mnc", "data_sim", "service_state", "voice_transport",
        "roaming", "wifi_connected", "rat", "band", "arfcn", "pci", "cell_id", "tac", "rsrp", "rsrq", "sinr",
        "nr_band", "nr_arfcn", "nr_pci", "nr_rsrp", "nr_rsrq", "nr_sinr", "cell_age_s",
        "lat", "lon", "accuracy_m", "altitude_m", "speed_mps", "fix_age_s",
        "display_override", "cc_count", "bw_mhz",
        // cell_service: the cellular registration itself (CellMath.cellService); mode: MOVING or STILL when written.
        "cell_service", "mode",
    )
    val TRACK = listOf("ts", "member", "lat", "lon", "accuracy_m", "altitude_m", "speed_mps", "bearing_deg", "provider")
    // CsvStore writes each row in its file's own header order, so a file begun by an older build stays consistent
    // and new columns start with the next day's file.
    val TESTS = listOf(
        "ts", "member", "sub_id", "sub_label", "carrier", "mcc_mnc", "rat", "wifi_connected", "lat", "lon",
        "accuracy_m", "latency_ms", "jitter_ms", "down_mbps", "up_mbps", "down_bytes", "up_bytes", "server", "colo",
        "egress_asn", "egress_org", "vpn_active", "result", "test_path", "cell_ipv6",
    )
    val HEARTBEAT = listOf("ts", "member", "logger_state", "battery_pct", "charging", "app_version")
    /** The household's own server, reached over the carrier: latency and a small transfer each way. */
    val SERVER = listOf(
        "ts", "member", "sub_id", "sub_label", "carrier", "mcc_mnc", "rat", "wifi_connected", "vpn_active",
        "cell_ipv6", "lat", "lon", "accuracy_m", "latency_ms", "jitter_ms", "down_mbps", "up_mbps", "down_bytes",
        "up_bytes", "result",
        // How the server reached the phone at the start and end of the test, when it can tell (a server on Tailscale).
        "path_start", "path_end", "derp_region", "direct_family", "direct_lan",
    )
    /** Mobile data per interval, attributed to the SIM carrying data. */
    val USAGE = listOf(
        "ts", "member", "interval_s", "sub_id", "sub_label", "carrier", "mcc_mnc", "wifi_connected",
        "rx_bytes", "tx_bytes", "app_rx_bytes", "app_tx_bytes", "all_rx_bytes", "all_tx_bytes",
    )
    /** The data check, every 2 to 10 minutes off Wi-Fi. */
    val CHECKS = listOf(
        "ts", "member", "sub_id", "sub_label", "carrier", "mcc_mnc", "service_state", "rat", "rsrp",
        "lat", "lon", "accuracy_m", "vpn_active", "test_path", "connect_ms", "total_ms", "colo", "result",
    )
    /** Call and text tests, tapped in the app: one row per SIM per tap. */
    val EVENTS = listOf(
        "ts", "member", "kind", "outcome", "target_sub_id", "sub_id", "sub_label", "carrier", "mcc_mnc", "data_sim",
        "service_state", "voice_transport", "rat", "rsrp", "wifi_connected", "lat", "lon", "accuracy_m",
    )
    /** Test texts between two household phones: one row per message event per side. */
    val TEXTS = listOf(
        "ts", "member", "event", "test_id", "exchange_id", "role", "trigger", "transport", "sub_id", "sub_label",
        "carrier", "mcc_mnc", "peer", "result", "latency_s", "rtt_s", "service_state", "voice_transport", "rat",
        "rsrp", "wifi_connected", "data_sim", "place_id", "lat", "lon", "accuracy_m",
    )
    val ALL: Map<String, List<String>> = mapOf(
        "samples" to SAMPLES, "track" to TRACK, "tests" to TESTS, "heartbeat" to HEARTBEAT, "server" to SERVER,
        "usage" to USAGE, "checks" to CHECKS, "events" to EVENTS, "texts" to TEXTS,
    )
}

object Csv {
    /** RFC 3339 with the phone's offset, millisecond precision. */
    fun ts(t: OffsetDateTime): String = t.truncatedTo(ChronoUnit.MILLIS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    fun line(values: List<Any?>): String = values.joinToString(",") { field(it) } + "\n"

    fun field(v: Any?): String = when (v) {
        null -> ""
        is Boolean -> if (v) "true" else "false"
        is Double -> number(v)
        is Float -> number(v.toDouble())
        else -> escape(v.toString())
    }

    fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    /** Up to 7 decimals, trailing zeros dropped: 42.3565123, 12.5, 3. Callers round to the precision they mean. */
    fun number(d: Double): String {
        if (d.isNaN() || d.isInfinite()) return ""
        var s = String.format(Locale.US, "%.7f", d)
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        return if (s == "-0") "0" else s
    }

    fun round(d: Double, places: Int): Double {
        val f = Math.pow(10.0, places.toDouble())
        return Math.round(d * f) / f
    }
}

/** Pure decisions, kept free of Android types so the unit tests can run them on the JVM. */
object CellMath {
    const val UNAVAILABLE = Int.MAX_VALUE

    fun inRange(v: Int, range: IntRange): Int? = if (v != UNAVAILABLE && v in range) v else null
    fun rsrp(v: Int) = inRange(v, -140..-43)
    fun rsrq(v: Int) = inRange(v, -34..3)
    fun rssnr(v: Int) = inRange(v, -20..30)
    fun ssRsrp(v: Int) = inRange(v, -156..-31)
    fun ssRsrq(v: Int) = inRange(v, -43..20)
    fun ssSinr(v: Int) = inRange(v, -23..40)

    /** ServiceState.getState(): airplane mode is POWER_OFF, which is not a dead spot. */
    fun serviceState(state: Int, dataRegistered: Boolean): String = when (state) {
        0 -> "IN_SERVICE"
        1 -> if (dataRegistered) "IN_SERVICE" else "OUT_OF_SERVICE"
        2 -> "EMERGENCY_ONLY"
        3 -> "POWER_OFF"
        else -> if (dataRegistered) "IN_SERVICE" else "OUT_OF_SERVICE"
    }

    /**
     * rat: NR_SA when an NR cell is the registered primary; NR_NSA when LTE is primary and a 5G leg is connected
     * (an NR cell marked SECONDARY_SERVING, or an NR signal in SignalStrength); otherwise the registered technology.
     */
    fun rat(
        serviceState: String,
        primaryNr: Boolean,
        primaryLte: Boolean,
        nrLeg: Boolean,
        primaryUmts: Boolean,
        psTech: Int?,
    ): String {
        if (serviceState != "IN_SERVICE") return "NONE"
        return when {
            primaryNr && !primaryLte -> "NR_SA"
            primaryLte && nrLeg -> "NR_NSA"
            primaryLte -> "LTE"
            primaryUmts -> "UMTS"
            psTech == NETWORK_TYPE_NR -> "NR_SA"
            psTech == NETWORK_TYPE_LTE -> "LTE"
            psTech != null && psTech in UMTS_TYPES -> "UMTS"
            else -> "NONE"
        }
    }

    /** One NetworkRegistrationInfo, reduced to what voice_transport needs. */
    data class Reg(val wlan: Boolean, val cs: Boolean, val ps: Boolean, val registered: Boolean, val tech: Int)

    /**
     * voice_transport, derived: Android's IMS registration API needs READ_PRECISE_PHONE_STATE (system apps only),
     * so this reads the SIM's registrations instead. A registered WLAN (IWLAN) transport means calls can ride Wi-Fi
     * calling, or, with Wi-Fi off, cross-SIM calling over the other SIM's data.
     */
    fun voiceTransport(regs: List<Reg>): String {
        if (regs.any { it.wlan && it.registered }) return "IWLAN"
        val wwan = regs.filter { !it.wlan && it.registered }
        val ps = wwan.firstOrNull { it.ps }
        return when {
            ps?.tech == NETWORK_TYPE_NR -> "NR"
            ps?.tech == NETWORK_TYPE_LTE -> "LTE"
            wwan.any { it.cs } -> "CS"
            else -> "NONE"
        }
    }

    /**
     * cell_service: the cellular registration alone. Android's ServiceState says IN_SERVICE for a line registered only
     * for Wi-Fi calling (IWLAN), with no cell at all. This says IN_SERVICE only while a cellular (WWAN) registration is
     * in place, for voice or data, at home or roaming; EMERGENCY_ONLY and POWER_OFF come from ServiceState, and anything
     * else, Wi-Fi calling alone included, is OUT_OF_SERVICE. A device that lists no registrations falls back to
     * ServiceState's own state.
     */
    fun cellService(state: Int, regs: List<Reg>): String = when {
        state == 3 -> "POWER_OFF"
        regs.isEmpty() -> serviceState(state, dataRegistered = false)
        regs.any { !it.wlan && it.registered } -> "IN_SERVICE"
        state == 2 -> "EMERGENCY_ONLY"
        else -> "OUT_OF_SERVICE"
    }

    /**
     * TelephonyDisplayInfo's override: what the status bar is told to show beyond the registered technology.
     * NR_NSA on an LTE row is the 5G icon while no 5G leg is up; NR_ADVANCED is 5G+ / 5G UW / 5G UC.
     */
    fun displayOverride(type: Int): String? = when (type) {
        0 -> "NONE"
        1 -> "LTE_CA"
        2 -> "LTE_ADVANCED_PRO"
        3 -> "NR_NSA"
        4 -> "NR_NSA_MMWAVE"
        5 -> "NR_ADVANCED"
        else -> null
    }

    /** ServiceState.getCellBandwidths() (kHz, one per serving carrier) as (carriers, total MHz); nulls if unknown. */
    fun bandwidth(khz: IntArray?): Pair<Int?, Double?> {
        val valid = khz?.filter { it in 1..1_000_000 }.orEmpty()
        if (valid.isEmpty()) return null to null
        return valid.size to Csv.round(valid.sum() / 1000.0, 1)
    }

    // TelephonyManager.NETWORK_TYPE_* values, repeated here so the pure code needs no Android classes.
    const val NETWORK_TYPE_LTE = 13
    const val NETWORK_TYPE_NR = 20
    val UMTS_TYPES = setOf(3, 8, 9, 10, 15) // UMTS, HSDPA, HSUPA, HSPA, HSPA+
}
