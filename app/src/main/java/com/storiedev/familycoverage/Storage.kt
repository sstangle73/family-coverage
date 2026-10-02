package com.storiedev.familycoverage

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime

/** Daily CSV files, one per table, in the app's own storage. Export and the optional server copy them as they are. */
class CsvStore(val dir: File) {
    private val lock = Any()
    private val headers = HashMap<String, List<String>>() // file name -> the header it was started with

    init {
        dir.mkdirs()
    }

    /**
     * Appends one row. A new file gets the current header; an existing file keeps ITS header, and the row is written
     * in that column order (columns the file doesn't have are dropped, ones it has but we no longer write are blank).
     * So a file started by an older build never mixes layouts, and new columns start with the next day's file.
     */
    fun append(table: String, t: OffsetDateTime, values: List<Any?>) {
        val header = Tables.ALL.getValue(table)
        require(values.size == header.size) { "$table row has ${values.size} values for ${header.size} columns" }
        val f = File(dir, "$table-${t.toLocalDate()}.csv")
        synchronized(lock) {
            val existing = if (f.exists() && f.length() > 0L) {
                headers.getOrPut(f.name) { f.bufferedReader().use { it.readLine() ?: "" }.split(",") }
            } else {
                null
            }
            val byName = header.zip(values).toMap()
            FileOutputStream(f, true).use { out ->
                if (existing == null) {
                    out.write(Csv.line(header).toByteArray(Charsets.UTF_8))
                    headers[f.name] = header
                }
                out.write(Csv.line((existing ?: header).map { byName[it] }).toByteArray(Charsets.UTF_8))
            }
        }
    }

    fun files(): List<File> =
        (dir.listFiles { f -> NAME.matches(f.name) } ?: emptyArray()).sortedBy { it.name }

    /** Bytes written today to one table, for the status screen. */
    fun sizeToday(table: String, t: OffsetDateTime = OffsetDateTime.now()): Long =
        File(dir, "$table-${t.toLocalDate()}.csv").let { if (it.exists()) it.length() else 0L }

    fun totalBytes(): Long = files().sumOf { it.length() }

    companion object {
        val NAME = Regex("^(samples|track|tests|heartbeat|server|usage|checks|events|texts)-\\d{4}-\\d{2}-\\d{2}\\.csv$")

        fun of(context: Context) = CsvStore(File(context.getExternalFilesDir(null), "csv"))
    }
}

/** Settings and state, in private SharedPreferences. Phone numbers stay here: never exported or uploaded. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("family-coverage", Context.MODE_PRIVATE)

    /** This phone's copy of the household, from setup or the last code it took. */
    var household: Household?
        get() = sp.getString("household", null)?.let { runCatching { Household.fromJson(JSONObject(it)) }.getOrNull() }
        set(v) = sp.edit().putString("household", v?.toJson()?.toString()).apply()

    /** Which member of the household this phone belongs to: the "member" column of every row. */
    var member: String?
        get() = sp.getString("member", null)
        set(v) = sp.edit().putString("member", v).apply()

    /** When this phone's owner agreed to what the app records (RFC 3339). Logging can't start without it. */
    var consentAt: String?
        get() = sp.getString("consent_at", null)
        set(v) = sp.edit().putString("consent_at", v).apply()

    var loggingEnabled: Boolean
        get() = sp.getBoolean("logging", false)
        set(v) = sp.edit().putBoolean("logging", v).apply()

    /** Carrier speed tests (Cloudflare's speed test service). On by default; about 1.3 MB a test. */
    var speedTests: Boolean
        get() = sp.getBoolean("speed_tests", true)
        set(v) = sp.edit().putBoolean("speed_tests", v).apply()

    /** The tiny data check (one request to Cloudflare's 1.1.1.1). On by default; under 1 KB a check. */
    var dataChecks: Boolean
        get() = sp.getBoolean("data_checks", true)
        set(v) = sp.edit().putBoolean("data_checks", v).apply()

    /** This install's upload key: 32 random bytes, made once. A server stores only its hash. */
    val deviceKey: String
        get() {
            sp.getString("device_key", null)?.let { return it }
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val key = bytes.joinToString("") { "%02x".format(it) }
            sp.edit().putString("device_key", key).commit()
            return key
        }

    /** This install's id: the first 16 hex characters of sha256(key). It names the install in exports. */
    val deviceId: String
        get() = MessageDigest.getInstance("SHA-256").digest(deviceKey.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)

    var deviceStatus: String
        get() = sp.getString("device_status", "unregistered") ?: "unregistered"
        set(v) = sp.edit().putString("device_status", v).apply()

    /** The server this install registered with: a new server means registering again. */
    var registeredServer: String?
        get() = sp.getString("registered_server", null)
        set(v) = sp.edit().putString("registered_server", v).apply()

    var lastUploadOkMs: Long
        get() = sp.getLong("last_upload_ok", 0L)
        set(v) = sp.edit().putLong("last_upload_ok", v).apply()

    var lastUploadError: String?
        get() = sp.getString("last_upload_error", null)
        set(v) = sp.edit().putString("last_upload_error", v).apply()

    /** sha256 of the manifest the server last accepted, so it's sent again only when it changes. */
    var manifestSent: String?
        get() = sp.getString("manifest_sent", null)
        set(v) = sp.edit().putString("manifest_sent", v).apply()

    // ---- Test texts. The numbers stay on this phone: never exported, uploaded or logged. ----

    var textsEnabled: Boolean
        get() = sp.getBoolean("texts_enabled", false)
        set(v) = sp.edit().putBoolean("texts_enabled", v).apply()

    /** The household member this phone tests with (the "peer" column). */
    var textPartner: String?
        get() = sp.getString("text_partner", null)
        set(v) = sp.edit().putString("text_partner", v).apply()

    /** The partner's main number (tests go to it) and second line's (accepted as a sender). */
    var textPartnerMain: String?
        get() = sp.getString("text_partner_main", null)
        set(v) = sp.edit().putString("text_partner_main", v).apply()

    var textPartnerSecond: String?
        get() = sp.getString("text_partner_second", null)
        set(v) = sp.edit().putString("text_partner_second", v).apply()

    /** Whether silent (data) texts reach the partner's main and second numbers. Off until the owner says so. */
    var textMainSilent: Boolean
        get() = sp.getBoolean("text_main_silent", false)
        set(v) = sp.edit().putBoolean("text_main_silent", v).apply()

    var textSecondSilent: Boolean
        get() = sp.getBoolean("text_second_silent", false)
        set(v) = sp.edit().putBoolean("text_second_silent", v).apply()

    /** Whether any ticked SIM here sends visible tests (so visible replies come back): kept by the tester. */
    var textOwnVisible: Boolean
        get() = sp.getBoolean("text_own_visible", false)
        set(v) = sp.edit().putBoolean("text_own_visible", v).apply()

    /** This phone's SIMs whose network refused a silent text: they send visible texts from then on. */
    var textNoSilentSubs: Set<Int>
        get() = sp.getStringSet("text_no_silent_subs", emptySet())?.mapNotNull { it.toIntOrNull() }?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("text_no_silent_subs", v.map { it.toString() }.toSet()).apply()

    /** The SIMs to test from (subscription ids); null until chosen, which means every active SIM. */
    var textSubs: Set<Int>?
        get() = sp.getStringSet("text_subs", null)?.mapNotNull { it.toIntOrNull() }?.toSet()
        set(v) = sp.edit().putStringSet("text_subs", v?.map { it.toString() }?.toSet()).apply()

    // Today's counts, so a restart keeps the limits.
    var textDay: String?
        get() = sp.getString("text_day", null)
        set(v) = sp.edit().putString("text_day", v).apply()

    var textExchanges: Int
        get() = sp.getInt("text_exchanges", 0)
        set(v) = sp.edit().putInt("text_exchanges", v).apply()

    var textEchoes: Int
        get() = sp.getInt("text_echoes", 0)
        set(v) = sp.edit().putInt("text_echoes", v).apply()

    var textLastExchangeMs: Long
        get() = sp.getLong("text_last_exchange", 0L)
        set(v) = sp.edit().putLong("text_last_exchange", v).apply()

    var textSlotsDone: Set<String>
        get() = sp.getStringSet("text_slots_done", emptySet()) ?: emptySet()
        set(v) = sp.edit().putStringSet("text_slots_done", v).apply()

    /** Bytes of each local file the server has confirmed. */
    fun offsets(): MutableMap<String, Long> {
        val out = mutableMapOf<String, Long>()
        val json = JSONObject(sp.getString("offsets", "{}") ?: "{}")
        for (k in json.keys()) out[k] = json.getLong(k)
        return out
    }

    fun saveOffsets(map: Map<String, Long>) {
        val json = JSONObject()
        for ((k, v) in map) json.put(k, v)
        sp.edit().putString("offsets", json.toString()).commit()
    }

    /** Forget the server's state: a new server, or leaving the household. The data files stay. */
    fun resetServer() {
        sp.edit().remove("device_status").remove("registered_server").remove("offsets").remove("manifest_sent")
            .remove("last_upload_ok").remove("last_upload_error").apply()
    }
}

/** What the service is doing, for the status screen (same process, so plain volatile fields are enough). */
object Status {
    @Volatile var running = false
    @Volatile var lastSample: String = "-"
    @Volatile var sims: String = "-"
    @Volatile var location: String = "-"
    @Volatile var lastTest: String = "-"
    @Volatile var lastServer: String = "-"
    @Volatile var lastCheck: String = "-"
    @Volatile var usage: String = "-"
    @Volatile var lastUpload: String = "-"
    @Volatile var lastEvent: String = ""
    @Volatile var lastEventAtMs: Long = 0L
    /** The active SIMs, for the call and text test buttons: sub id to a name like "Verizon". */
    @Volatile var simChoices: List<Pair<Int, String>> = emptyList()
    @Volatile var lastText: String = "-"
    @Volatile var textsToday: String = ""
    @Volatile var power: String = "-"
    @Volatile var note: String = ""
    /** The latest fix, for "add this spot" (written by the logger). */
    @Volatile var lastFix: android.location.Location? = null
}
