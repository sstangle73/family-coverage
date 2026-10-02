package com.storiedev.familycoverage

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The export: one zip per phone, holding manifest.json and every daily CSV file under csv/. The report page
 * (docs/report/) opens several of them at once, in the browser, without uploading anything.
 */
object Exporter {
    const val FORMAT = "family-coverage-export"
    const val VERSION = 1

    fun fileName(member: String, day: LocalDate): String {
        val slug = member.lowercase(Locale.US).map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }
            .joinToString("").replace(Regex("-+"), "-").trim('-').ifEmpty { "member" }
        return "family-coverage-$slug-$day.zip"
    }

    /** What the report needs besides the rows: who, which household, its places, and which files are inside. */
    fun manifest(household: Household, member: String, deviceId: String, files: List<String>, exportedAt: OffsetDateTime?): JSONObject {
        val h = household.toJson(includePlaces = true)
        h.remove("v")
        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("flavor", BuildConfig.FLAVOR)
            .put("member", member)
            .put("device_id", deviceId)
            .put("household", h)
            .put("tables", JSONObject(Tables.ALL.mapValues { JSONArray(it.value) }))
            .apply {
                if (exportedAt != null) put("exported_at", Csv.ts(exportedAt))
                if (files.isNotEmpty()) put("files", JSONArray(files))
            }
    }

    /** Writes the zip to [out]; returns (files, bytes of CSV). Each file is cut after its last complete row. */
    fun write(prefs: Prefs, store: CsvStore, out: OutputStream): Pair<Int, Long> {
        val household = prefs.household ?: throw IllegalStateException("not set up")
        val member = prefs.member ?: throw IllegalStateException("no member chosen")
        val files = store.files()
        var bytes = 0L
        ZipOutputStream(out.buffered()).use { zip ->
            val manifest = manifest(household, member, prefs.deviceId, files.map { "csv/${it.name}" }, OffsetDateTime.now())
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (f in files) {
                val data = completeRows(f) ?: continue
                zip.putNextEntry(ZipEntry("csv/${f.name}").apply { time = f.lastModified() })
                zip.write(data)
                zip.closeEntry()
                bytes += data.size
            }
        }
        return files.size to bytes
    }

    /** The file up to its last newline: a row being written right now waits for the next export. */
    fun completeRows(f: File): ByteArray? {
        val data = f.readBytes()
        val last = data.lastIndexOf('\n'.code.toByte())
        return if (last < 0) null else data.copyOf(last + 1)
    }
}
