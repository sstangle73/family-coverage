package com.storiedev.familycoverage

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * Copies the daily CSV files to the household's server, if it has one, append-only. Each request says "file X from
 * byte N"; the server appends whatever it doesn't have yet and answers with its size, which becomes the new offset.
 * A lost answer just means the same bytes are offered again and skipped. A new install registers first and uploads
 * nothing until someone approves it on the server.
 */
class Uploader(private val prefs: Prefs, private val store: CsvStore) {

    fun sync(): String {
        val household = prefs.household ?: return "not set up"
        val server = household.server ?: return "no server: the data stays on this phone"
        val member = prefs.member ?: return "no member chosen"
        if (prefs.registeredServer != server) {
            prefs.resetServer()
            prefs.registeredServer = server
        }
        val url = URL(server)
        val addresses = try {
            InetAddress.getAllByName(url.host).toList()
        } catch (e: IOException) {
            return "server not reachable"
        }
        if (!UrlRules.allowed(url.protocol, addresses)) {
            return "refused: plain http only to a home-network address; use https"
        }
        return try {
            if (prefs.deviceStatus != "approved") {
                register(server, member)
                if (prefs.deviceStatus != "approved") return "waiting for approval on the server (device ${prefs.deviceId})"
            }
            val (sent, refused) = pushFiles(server)
            pushManifest(server, household, member)
            prefs.lastUploadOkMs = System.currentTimeMillis()
            prefs.lastUploadError = if (refused.isEmpty()) null else "server refused ${refused.joinToString()}"
            "up to date (${sent / 1024} KB sent)" +
                if (refused.isEmpty()) "" else "; server refused ${refused.joinToString()} (an older server?)"
        } catch (e: HttpStatus) {
            if (e.code == 401) prefs.deviceStatus = "unregistered"
            if (e.code == 403) prefs.deviceStatus = "pending"
            prefs.lastUploadError = "HTTP ${e.code}"
            "server answered HTTP ${e.code}"
        } catch (e: Exception) {
            prefs.lastUploadError = e.javaClass.simpleName
            "upload failed: ${e.javaClass.simpleName}"
        }
    }

    private fun register(server: String, member: String) {
        val body = JSONObject()
            .put("member", member)
            .put("household", prefs.household?.id ?: "")
            .put("key", prefs.deviceKey)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("app_version", BuildConfig.VERSION_NAME)
            .put("consent_at", prefs.consentAt ?: "")
        val resp = post(server, "/api/register", body, auth = false)
        prefs.deviceStatus = resp.optString("status", "pending")
    }

    /** Bytes sent, and the tables the server refused (HTTP 400: a table newer than the server). */
    private fun pushFiles(server: String): Pair<Long, List<String>> {
        val offsets = prefs.offsets()
        var sent = 0L
        val refused = sortedSetOf<String>()
        for (f in store.files()) {
            var offset = offsets[f.name] ?: 0L
            if (offset > f.length()) offset = 0L
            var rounds = 0
            try {
                while (f.length() > offset && rounds < 64) {
                    rounds++
                    val chunk = readLines(f, offset, MAX_CHUNK) ?: break
                    val entry = JSONObject().put("name", f.name).put("offset", offset).put("data", String(chunk, Charsets.UTF_8))
                    val resp = post(server, "/api/upload", JSONObject().put("files", JSONArray().put(entry)), auth = true)
                    val size = resp.getJSONArray("files").getJSONObject(0).getLong("size")
                    if (size < offset) {
                        // The server has less than we thought (a restore, say): resend from its size, a row boundary.
                        offset = size
                        offsets[f.name] = offset
                        prefs.saveOffsets(offsets)
                        continue
                    }
                    if (size == offset) break
                    sent += size - offset
                    offset = size
                    offsets[f.name] = offset
                    prefs.saveOffsets(offsets)
                }
            } catch (e: HttpStatus) {
                // One file refused mustn't hold up the rest; it's offered again next time.
                if (e.code != 400) throw e
                refused += f.name.substringBefore('-')
            }
        }
        return sent to refused.toList()
    }

    /** The export manifest (household, member, places), so the server's export matches the phone's. Sent on change. */
    private fun pushManifest(server: String, household: Household, member: String) {
        val manifest = Exporter.manifest(household, member, prefs.deviceId, emptyList(), null)
        val digest = MessageDigest.getInstance("SHA-256").digest(manifest.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (digest == prefs.manifestSent) return
        try {
            post(server, "/api/manifest", manifest, auth = true)
            prefs.manifestSent = digest
        } catch (e: HttpStatus) {
            if (e.code != 404) throw e // a server without manifests: fine
        }
    }

    private fun post(server: String, path: String, body: JSONObject, auth: Boolean): JSONObject {
        val c = URL(server + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.instanceFollowRedirects = false
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Content-Encoding", "gzip")
            c.setRequestProperty("User-Agent", "FamilyCoverage/${BuildConfig.VERSION_NAME}")
            if (auth) c.setRequestProperty("Authorization", "Bearer ${prefs.deviceKey}")
            GZIPOutputStream(c.outputStream).use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpStatus(code)
            return JSONObject(text)
        } finally {
            c.disconnect()
        }
    }

    private class HttpStatus(val code: Int) : IOException("HTTP $code")

    companion object {
        const val MAX_CHUNK = 512 * 1024

        /** Up to [max] bytes from [offset], cut after the last newline so a row is never split. */
        fun readLines(f: File, offset: Long, max: Int): ByteArray? {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val available = raf.length() - offset
                if (available <= 0) return null
                val want = minOf(available, max.toLong()).toInt()
                val buf = ByteArray(want)
                raf.seek(offset)
                raf.readFully(buf)
                val last = buf.lastIndexOf('\n'.code.toByte())
                return when {
                    last >= 0 -> buf.copyOf(last + 1)
                    available <= max -> null // a row still being written; wait for its newline
                    else -> buf
                }
            }
        }
    }
}

/**
 * Where the app may send a household's data. https goes anywhere the household chose. Plain http only to an address
 * on the home network or a private VPN (10/8, 172.16/12, 192.168/16, 100.64/10, fc00::/7, link-local), where it
 * never crosses the internet unencrypted.
 */
object UrlRules {
    fun allowed(scheme: String, addresses: List<InetAddress>): Boolean = when (scheme.lowercase()) {
        "https" -> true
        "http" -> addresses.isNotEmpty() && addresses.all { isPrivate(it) }
        else -> false
    }

    fun isPrivate(a: InetAddress): Boolean {
        if (a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress) return true
        if (a is Inet4Address) {
            val b = a.address
            val first = b[0].toInt() and 0xff
            val second = b[1].toInt() and 0xff
            if (first == 100 && second in 64..127) return true // carrier-grade NAT space, used by Tailscale
        }
        if (a is Inet6Address && (a.address[0].toInt() and 0xfe) == 0xfc) return true // unique local
        return false
    }
}
