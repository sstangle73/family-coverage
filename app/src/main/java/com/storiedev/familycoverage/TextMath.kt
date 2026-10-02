package com.storiedev.familycoverage

import java.security.SecureRandom
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/** The test texts' pure decisions, free of Android types for the unit tests. */
object TextMath {
    /** The app port silent test texts go to (3GPP leaves 16000-16999 to applications). */
    const val PORT: Short = 16474

    private val TAG = Regex("^FC (test|echo) ([0-9A-F]{6}) (\\d{2}):(\\d{2}):(\\d{2})$")
    private val random = SecureRandom()

    /** A test text: its role, its id, and the time the sender wrote into it (the sender's clock). */
    data class Tag(val role: String, val testId: String, val sentAt: LocalTime)

    /** "FC test 7F3A12 13:30:05": short, and recognisable in a Messages app when it shows up there. */
    fun tag(role: String, testId: String, at: LocalTime): String =
        String.format(Locale.US, "FC %s %s %02d:%02d:%02d", role, testId, at.hour, at.minute, at.second)

    fun parse(body: String?): Tag? {
        val m = TAG.matchEntire(body?.trim() ?: return null) ?: return null
        val (role, id, h, min, s) = m.destructured
        val at = runCatching { LocalTime.of(h.toInt(), min.toInt(), s.toInt()) }.getOrNull() ?: return null
        return Tag(role, id, at)
    }

    fun newId(): String = ByteArray(3).also { random.nextBytes(it) }.joinToString("") { "%02X".format(it) }

    /**
     * A phone number reduced to the digits that identify it: the last 10 (a North American number with or without
     * +1, or a national number elsewhere), or all of them when shorter; null when it's too short to be a phone.
     */
    fun number(raw: String?): String? {
        val d = raw?.filter(Char::isDigit) ?: return null
        return when {
            d.length >= 10 -> d.takeLast(10)
            d.length >= 7 -> d
            else -> null
        }
    }

    fun sameNumber(a: String?, b: String?): Boolean {
        val x = number(a)
        return x != null && x == number(b)
    }

    fun quiet(t: LocalTime): Boolean = t.hour >= Config.TEXT_QUIET_START || t.hour < Config.TEXT_QUIET_END

    /** Seconds from the time written in a text to [received]; clocks either side of midnight come out right. */
    fun latency(sentAt: LocalTime, received: LocalTime): Double {
        var s = Duration.between(sentAt, received).toMillis() / 1000.0
        if (s < -43_200) s += 86_400
        if (s > 43_200) s -= 86_400
        return Csv.round(s, 1)
    }

    /** A send result (the sent broadcast's result code) by name. */
    fun sendResult(code: Int): String = when (code) {
        -1 -> "OK" // Activity.RESULT_OK
        1 -> "GENERIC_FAILURE"
        2 -> "RADIO_OFF"
        3 -> "NULL_PDU"
        4 -> "NO_SERVICE"
        5 -> "LIMIT_EXCEEDED"
        6 -> "FDN_CHECK_FAILURE"
        7, 8 -> "SHORT_CODE_NOT_ALLOWED"
        9 -> "RADIO_NOT_AVAILABLE"
        10 -> "NETWORK_REJECT"
        16 -> "MODEM_ERROR"
        17 -> "NETWORK_ERROR"
        20 -> "OPERATION_NOT_ALLOWED"
        else -> "ERROR_$code"
    }

    /** A delivery report's TP-Status: 0-31 delivered, 32-63 the network is still trying, 64 and up failed. */
    fun deliveryStatus(status: Int?): String = when {
        status == null || status < 0 -> "UNKNOWN"
        status <= 0x1F -> "DELIVERED"
        status <= 0x3F -> "PENDING"
        else -> "FAILED"
    }

    /**
     * Send results that mean the SIM's network won't carry silent (data) texts at all, not a coverage failure: AT&T
     * in the US answers NETWORK_ERROR. The app then resends as a visible text.
     */
    val SILENT_UNSUPPORTED = setOf(10, 14, 17, 18, 20, 24)

    /**
     * Networks known to neither send nor deliver silent texts, by network code: AT&T in the US (Cricket and other
     * AT&T resellers report its codes too). Elsewhere the app learns from a refused send.
     */
    val NO_SILENT_PLMNS = setOf("310410", "310280", "310030", "311180", "313100", "310150")

    /**
     * How one SIM's test goes: silent, to a partner number that silent texts reach, if the SIM can send them;
     * otherwise visible, to the partner's second number, so the replies land outside the normal conversation (or to
     * the main number if there's no second). Null when no number is set. Returns (transport, destination).
     */
    fun route(simSilent: Boolean, main: String?, mainSilent: Boolean, second: String?, secondSilent: Boolean): Pair<String, String>? {
        val m = main?.takeIf { number(it) != null }
        val s = second?.takeIf { number(it) != null }
        if (simSilent) {
            if (m != null && mainSilent) return "data" to m
            if (s != null && secondSilent) return "data" to s
        }
        return "text" to (s ?: m ?: return null)
    }

    enum class Where { INSIDE, OUTSIDE, UNKNOWN }

    /**
     * A fix against a place: inside with some slack (indoor fixes wander), outside only when clearly beyond the
     * radius plus a margin, and unknown in between, so a wobbly fix neither starts nor ends a visit.
     */
    fun where(distanceM: Double, accuracyM: Double?, radiusM: Double): Where {
        val acc = accuracyM ?: 50.0
        return when {
            distanceM <= radiusM + minOf(acc, 50.0) -> Where.INSIDE
            distanceM - minOf(acc, 100.0) > radiusM + 50.0 -> Where.OUTSIDE
            else -> Where.UNKNOWN
        }
    }

    /** A fallback time is due within its 20-minute window, on one of its days. */
    fun slotDue(now: LocalDateTime, days: Set<DayOfWeek>, slot: LocalTime): Boolean {
        val t = now.toLocalTime()
        return now.dayOfWeek in days && !t.isBefore(slot) && t.isBefore(slot.plusMinutes(20))
    }

    fun day(name: String): DayOfWeek? {
        val prefix = name.trim().uppercase(Locale.US).take(3)
        return if (prefix.length < 3) null else DayOfWeek.values().firstOrNull { it.name.startsWith(prefix) }
    }

    /**
     * Whether an exchange may start now: null if so, else why not. Automatic ones keep the daily cap, the quiet
     * hours and the spacing; one the owner taps keeps the cap and a minute's gap.
     */
    fun mayStart(nowMs: Long, time: LocalTime, exchangesToday: Int, lastExchangeMs: Long, cap: Int, manual: Boolean): String? =
        when {
            exchangesToday >= cap -> "today's limit reached"
            manual -> if (nowMs - lastExchangeMs < 60_000L) "wait a minute" else null
            quiet(time) -> "quiet hours"
            nowMs - lastExchangeMs < Config.TEXT_SPACING_MS -> "too soon after the last test"
            else -> null
        }
}
