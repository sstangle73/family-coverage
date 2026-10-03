package com.storiedev.familycoverage

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * The server test: can this phone reach the household's own server from where it is, and how well? Latency, 500 KB
 * down and 125 KB up against the server's /api/test endpoints, over the phone's default network (off Wi-Fi: the
 * carrier, or a VPN the household runs). It answers questions a carrier speed test can't, such as whether a home
 * camera or file server is usable from the school car park.
 *
 * A server that is itself a Tailscale node also answers "begin" and "end" with how its tailscaled reaches this phone
 * at that moment, direct or relayed (DERP), because Tailscale often starts a flow relayed and moves to direct within
 * seconds. The phone can't see its own path; the server can.
 */
class ServerTester(context: Context, private val prefs: Prefs) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    /** The server's answer to begin or end: path is direct, derp, peer_relay, idle, unknown or not_tailscale. */
    data class Path(val path: String?, val derpRegion: String?, val directFamily: String?, val directLan: Boolean?)

    data class Result(
        val latencyMs: Double? = null,
        val jitterMs: Double? = null,
        val downMbps: Double? = null,
        val upMbps: Double? = null,
        val downBytes: Long = 0,
        val upBytes: Long = 0,
        val vpnActive: Boolean = false,
        val cellIpv6: Boolean? = null,
        val result: String,
        /** Null when the server doesn't answer begin or end (one from before the path test). */
        val start: Path? = null,
        val end: Path? = null,
        /** For the status screen only (not a CSV column). */
        val error: String? = null,
    )

    private class HttpError(val code: Int) : IOException("HTTP $code")
    private class Deadline(val bytes: Long, val seconds: Double) : IOException("deadline")

    fun run(server: String): Result {
        val vpn = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        @Suppress("DEPRECATION")
        val cellular = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        }
        val ipv6 = cellular?.let { NetInfo.hasGlobalIpv6(cm.getLinkProperties(it)) }
        var start: Path? = null
        var latency: Double? = null
        var jitter: Double? = null
        var downBytes = 0L
        var downMbps: Double? = null
        fun failed(result: String, e: Exception) = Result(
            latencyMs = latency, jitterMs = jitter, downMbps = downMbps, downBytes = downBytes, vpnActive = vpn,
            cellIpv6 = ipv6, result = result, start = start,
            error = e.javaClass.simpleName + (e.message?.let { ": " + it.take(80) } ?: ""),
        )
        return try {
            val url = URL(server)
            if (!UrlRules.allowed(url.protocol, InetAddress.getAllByName(url.host).toList())) {
                return Result(vpnActive = vpn, cellIpv6 = ipv6, result = "REFUSED", error = "plain http to a public address")
            }
            start = path(server, "/api/test/begin")
            val times = mutableListOf<Double>()
            for (i in 0 until 6) {
                val t0 = System.nanoTime()
                val c = open(server, "/api/test/ping", 10_000)
                try {
                    if (c.responseCode !in 200..299) throw HttpError(c.responseCode)
                    c.inputStream.use { it.readBytes() }
                } finally {
                    c.disconnect()
                }
                if (i > 0) times += (System.nanoTime() - t0) / 1e6
            }
            latency = Stats.round1(Stats.median(times))
            jitter = Stats.round1(Stats.meanAbsDiff(times))
            val d = open(server, "/api/test/down?bytes=${Config.SERVER_DOWN_BYTES}", 15_000)
            val (dBytes, dSecs) = try {
                if (d.responseCode !in 200..299) throw HttpError(d.responseCode)
                Transfer.readAll(d, 30.0) { bytes, secs -> Deadline(bytes, secs) }
            } finally {
                d.disconnect()
            }
            downBytes = dBytes
            downMbps = Stats.mbps(dBytes, dSecs)
            val u = open(server, "/api/test/up", 30_000)
            val upSecs = try {
                Transfer.post(u, Config.SERVER_UP_BYTES) { code -> HttpError(code) }
            } finally {
                u.disconnect()
            }
            val end = path(server, "/api/test/end")
            Result(
                latencyMs = latency, jitterMs = jitter, downMbps = downMbps,
                upMbps = Stats.mbps(Config.SERVER_UP_BYTES.toLong(), upSecs), downBytes = downBytes,
                upBytes = Config.SERVER_UP_BYTES.toLong(), vpnActive = vpn, cellIpv6 = ipv6, result = "OK",
                start = start, end = end,
            )
        } catch (e: Deadline) {
            downBytes = e.bytes
            downMbps = Stats.mbps(e.bytes, e.seconds)
            failed("TIMEOUT", e)
        } catch (e: UnknownHostException) {
            failed("DNS_FAIL", e)
        } catch (e: SocketTimeoutException) {
            failed("TIMEOUT", e)
        } catch (e: ConnectException) {
            failed("CONNECT_FAIL", e)
        } catch (e: HttpError) {
            failed(if (e.code == 401 || e.code == 403) "NOT_APPROVED" else "HTTP_ERROR", e)
        } catch (e: Exception) {
            failed("ERROR", e)
        }
    }

    /** begin or end: how the server reaches this phone now. Null from a server without them (it answers 404). */
    private fun path(server: String, endpoint: String): Path? {
        val c = open(server, endpoint, 15_000)
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setFixedLengthStreamingMode(0)
            c.outputStream.close()
            if (c.responseCode == 404) return null
            if (c.responseCode !in 200..299) throw HttpError(c.responseCode)
            val j = try {
                JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            } catch (e: JSONException) {
                return null
            }
            return Path(
                path = j.optString("path").takeIf { it.isNotBlank() },
                derpRegion = j.optString("derp_region").takeIf { it.isNotBlank() },
                directFamily = j.optString("direct_family").takeIf { it.isNotBlank() },
                directLan = if (!j.has("direct_lan") || j.isNull("direct_lan")) null else j.optBoolean("direct_lan"),
            )
        } finally {
            c.disconnect()
        }
    }

    private fun open(server: String, path: String, timeoutMs: Int): HttpURLConnection {
        val c = URL(server + path).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.useCaches = false
        c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", "FamilyCoverage/${BuildConfig.VERSION_NAME}")
        c.setRequestProperty("Authorization", "Bearer ${prefs.deviceKey}")
        return c
    }
}
