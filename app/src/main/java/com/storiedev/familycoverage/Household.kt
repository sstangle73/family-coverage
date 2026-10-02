package com.storiedev.familycoverage

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.security.SecureRandom
import java.time.LocalDate
import java.util.Base64
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.Inflater

/** A place the household cares about: a centre and a radius, plus optional fallback times for test texts. */
data class Place(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double = DEFAULT_RADIUS_M,
    /** Fallback test times: days ("MON".."SUN") and times ("08:15"). A test goes out then if none did in the hour. */
    val days: List<String> = emptyList(),
    val times: List<String> = emptyList(),
) {
    companion object {
        const val DEFAULT_RADIUS_M = 150.0
        const val MIN_RADIUS_M = 50.0
        const val MAX_RADIUS_M = 1_000.0
    }
}

/**
 * One household's study: who takes part, until when, where its data may go, and its places. It's made on one phone
 * and copied to the others as a setup code (a QR code, or text). It never holds phone numbers: those stay on each
 * phone.
 */
data class Household(
    val id: String,
    val name: String,
    val members: List<String>,
    val end: LocalDate,
    /** Optional: a server the phones copy their data to. Null means the data stays on each phone. */
    val server: String? = null,
    val places: List<Place> = emptyList(),
) {
    fun toJson(includePlaces: Boolean = true): JSONObject {
        val o = JSONObject()
            .put("v", 1)
            .put("id", id)
            .put("name", name)
            .put("members", JSONArray(members))
            .put("end", end.toString())
        if (server != null) o.put("server", server)
        if (includePlaces && places.isNotEmpty()) {
            o.put("places", JSONArray(places.map { p ->
                JSONObject().put("id", p.id).put("name", p.name)
                    .put("lat", Csv.round(p.lat, 6)).put("lon", Csv.round(p.lon, 6)).put("r", p.radiusM.toInt())
                    .apply {
                        if (p.days.isNotEmpty()) put("days", JSONArray(p.days))
                        if (p.times.isNotEmpty()) put("times", JSONArray(p.times))
                    }
            }))
        }
        return o
    }

    fun withPlace(p: Place): Household = copy(places = places.filter { it.id != p.id } + p)

    fun withoutPlace(id: String): Household = copy(places = places.filter { it.id != id })

    companion object {
        const val CODE_PREFIX = "FC1."
        const val MAX_MEMBERS = 12
        const val MAX_PLACES = 50
        const val MAX_NAME = 40
        const val MAX_MEMBER = 30
        private const val MAX_INFLATED = 64 * 1024
        private val CODE = Regex("FC1\\.[A-Za-z0-9_-]{8,}")
        private val TIME = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
        private val DAYS = setOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
        private val random = SecureRandom()

        fun newId(): String = ByteArray(4).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

        /** A member's name as the app uses it: trimmed, inner spaces collapsed, 1-30 characters, no control characters. */
        fun cleanMember(raw: String?): String? {
            val s = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
            if (s.isEmpty() || s.length > MAX_MEMBER || s.any { it.isISOControl() }) return null
            return s
        }

        fun cleanName(raw: String?): String? {
            val s = raw?.trim()?.replace(Regex("\\s+"), " ") ?: return null
            if (s.isEmpty() || s.length > MAX_NAME || s.any { it.isISOControl() }) return null
            return s
        }

        /**
         * A server address: http or https, a host, and nothing that could carry a secret or confuse an upload (no
         * user, query or fragment). The trailing slash is dropped. Blank means no server. Throws with a reason.
         */
        fun cleanServer(raw: String?): String? {
            val s = raw?.trim()?.trimEnd('/') ?: return null
            if (s.isEmpty()) return null
            val uri = try {
                URI(s)
            } catch (e: Exception) {
                throw IllegalArgumentException("the server address isn't a valid web address")
            }
            val scheme = uri.scheme?.lowercase(Locale.US)
            require(scheme == "https" || scheme == "http") { "the server address must start with https:// or http://" }
            require(!uri.host.isNullOrBlank()) { "the server address has no host name" }
            require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "the server address can't contain a user name, ? or #"
            }
            return s
        }

        /** A stable id for a new place: its name in lower case letters and digits, made unique among [taken]. */
        fun placeId(name: String, taken: Set<String>): String {
            val base = name.lowercase(Locale.US).map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
                .joinToString("").replace(Regex("-+"), "-").trim('-').take(24).ifEmpty { "place" }
            if (base !in taken) return base
            var n = 2
            while ("$base-$n" in taken) n++
            return "$base-$n"
        }

        fun fromJson(o: JSONObject): Household {
            require(o.optInt("v", 1) == 1) { "this setup code is from a newer version of the app: update it first" }
            val members = o.optJSONArray("members")?.let { a -> (0 until a.length()).mapNotNull { cleanMember(a.optString(it)) } }
                .orEmpty().distinct()
            require(members.isNotEmpty()) { "the household has no members" }
            require(members.size <= MAX_MEMBERS) { "a household can have at most $MAX_MEMBERS members" }
            val places = o.optJSONArray("places")?.let { a ->
                (0 until minOf(a.length(), MAX_PLACES)).mapNotNull { i -> parsePlace(a.optJSONObject(i)) }
            }.orEmpty().distinctBy { it.id }
            val end = try {
                LocalDate.parse(o.getString("end"))
            } catch (e: Exception) {
                throw IllegalArgumentException("the household has no valid end date")
            }
            return Household(
                id = o.optString("id").takeIf { it.matches(Regex("^[0-9a-f]{8}$")) } ?: newId(),
                name = cleanName(o.optString("name")) ?: "Our household",
                members = members,
                end = end,
                server = cleanServer(o.optString("server").takeIf { it.isNotBlank() }),
                places = places,
            )
        }

        private fun parsePlace(o: JSONObject?): Place? {
            o ?: return null
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            val id = o.optString("id").takeIf { it.matches(Regex("^[a-z0-9-]{1,32}$")) } ?: return null
            val days = o.optJSONArray("days")?.let { a -> (0 until a.length()).map { a.optString(it).uppercase(Locale.US).take(3) } }
                .orEmpty().filter { it in DAYS }.distinct()
            val times = o.optJSONArray("times")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                .orEmpty().filter { TIME.matches(it) }.distinct()
            return Place(
                id = id,
                name = cleanName(o.optString("name")) ?: id,
                lat = lat,
                lon = lon,
                radiusM = o.optDouble("r", Place.DEFAULT_RADIUS_M).coerceIn(Place.MIN_RADIUS_M, Place.MAX_RADIUS_M),
                days = days,
                times = times,
            )
        }

        /** The setup code: "FC1." and the household's JSON, compressed and base64url-encoded. */
        fun encode(h: Household, includePlaces: Boolean = true): String {
            val raw = h.toJson(includePlaces).toString().toByteArray(Charsets.UTF_8)
            val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
            deflater.setInput(raw)
            deflater.finish()
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            deflater.end()
            return CODE_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        }

        /**
         * A household from a setup code, found anywhere in [text]: a pasted message, a scanned QR code, or a link
         * that carries it. Throws IllegalArgumentException with a reason a person can act on.
         */
        fun decode(text: String): Household {
            val code = CODE.find(text.replace(Regex("\\s"), ""))?.value
                ?: throw IllegalArgumentException("that isn't a Family Coverage setup code")
            val bytes = try {
                Base64.getUrlDecoder().decode(code.removePrefix(CODE_PREFIX))
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("the setup code is damaged: copy all of it")
            }
            val inflater = Inflater(true)
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(4096)
            try {
                while (!inflater.finished()) {
                    val n = inflater.inflate(buf)
                    if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                    out.write(buf, 0, n)
                    require(out.size() <= MAX_INFLATED) { "the setup code is too large" }
                }
            } catch (e: java.util.zip.DataFormatException) {
                throw IllegalArgumentException("the setup code is damaged: copy all of it")
            } finally {
                inflater.end()
            }
            val json = try {
                JSONObject(out.toString("UTF-8"))
            } catch (e: Exception) {
                throw IllegalArgumentException("the setup code is damaged: copy all of it")
            }
            return fromJson(json)
        }
    }
}
