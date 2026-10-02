package com.storiedev.familycoverage

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The carrier test against Cloudflare's speed endpoints: latency, 1 MB down, 250 KB up (about 1.3 MB a run).
 *
 * First choice is sockets bound to the CELLULAR network (requestNetwork(TRANSPORT_CELLULAR)), so a phone on home
 * Wi-Fi still measures its carrier. Inside a VPN that doesn't let apps bypass it (Tailscale, for one), Android refuses
 * those sockets. Then, if Wi-Fi is off, the test retries over the default network. A VPN that routes only its own
 * private addresses leaves the test on the carrier (test_path=default); one that carries everything, such as an exit
 * node, sends it through the tunnel (test_path=exit_node). On Wi-Fi a blocked test stays VPN_BLOCKED: the default path
 * would measure the Wi-Fi.
 */
class SpeedTester(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    data class Result(
        val latencyMs: Double? = null,
        val jitterMs: Double? = null,
        val downMbps: Double? = null,
        val upMbps: Double? = null,
        val downBytes: Long = 0,
        val upBytes: Long = 0,
        val colo: String? = null,
        val asn: Long? = null,
        val org: String? = null,
        val vpnActive: Boolean = false,
        val result: String,
        val testPath: String? = null,
        val cellIpv6: Boolean? = null,
        /** For the status screen only (not a CSV column): the exception behind a failed test. */
        val error: String? = null,
    )

    private class HttpError(code: Int) : IOException("HTTP $code")
    private class Deadline(val bytes: Long, val seconds: Double) : IOException("deadline")

    fun run(wifi: Boolean): Result {
        val active = cm.activeNetwork
        val vpn = cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val exitNode = vpn && NetInfo.hasDefaultRoute(cm.getLinkProperties(active))
        val acquired = acquireCellular() ?: return Result(vpnActive = vpn, result = "NO_DATA_NETWORK")
        val (network, callback) = acquired
        val ipv6 = NetInfo.hasGlobalIpv6(cm.getLinkProperties(network))
        try {
            val bound = measure(vpn, bound = true) { url -> network.openConnection(url) as HttpURLConnection }
            if (bound.result == "VPN_BLOCKED" && !wifi) {
                val viaDefault = measure(vpn, bound = false) { url -> url.openConnection() as HttpURLConnection }
                return viaDefault.copy(testPath = if (exitNode) "exit_node" else "default", cellIpv6 = ipv6)
            }
            return bound.copy(testPath = "cellular_bound", cellIpv6 = ipv6)
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    /** One full run through [open]; classifies a failure into the contract's result codes. */
    private fun measure(vpn: Boolean, bound: Boolean, open: (URL) -> HttpURLConnection): Result {
        var colo: String? = null
        var asn: Long? = null
        var org: String? = null
        var latency: Double? = null
        var jitter: Double? = null
        var downBytes = 0L
        var downMbps: Double? = null
        var reached = false // any request has succeeded on this path
        fun partial(result: String, e: Exception): Result {
            // Inside a VPN that doesn't allow bypass, Android refuses sockets bound to the cellular network before a
            // single request gets through: say so, not ERROR.
            val blocked = bound && vpn && !reached && (e is java.net.SocketException || e is SecurityException)
            return Result(latency, jitter, downMbps, null, downBytes, 0, colo, asn, org, vpn,
                if (blocked) "VPN_BLOCKED" else result,
                error = e.javaClass.simpleName + (e.message?.let { ": " + it.take(80) } ?: ""))
        }
        return try {
            runCatching {
                val meta = JSONObject(getText(open, "$BASE/meta"))
                reached = true
                asn = meta.optLong("asn").takeIf { it > 0 }
                org = meta.optString("asOrganization").takeIf { it.isNotBlank() }
                colo = meta.optJSONObject("colo")?.optString("iata")?.takeIf { it.isNotBlank() }
            }
            val times = mutableListOf<Double>()
            for (i in 0 until 6) {
                val t0 = System.nanoTime()
                val ray = fetchSmall(open, "$BASE/__down?bytes=0")
                reached = true
                if (i > 0) times += (System.nanoTime() - t0) / 1e6
                if (colo == null) colo = ray
            }
            latency = Stats.round1(Stats.median(times))
            jitter = Stats.round1(Stats.meanAbsDiff(times))
            val (dBytes, dSecs) = download(open, "$BASE/__down?bytes=$DOWN_BYTES")
            downBytes = dBytes
            downMbps = Stats.mbps(dBytes, dSecs)
            val upSecs = upload(open, "$BASE/__up", UP_BYTES)
            Result(latency, jitter, downMbps, Stats.mbps(UP_BYTES.toLong(), upSecs), downBytes, UP_BYTES.toLong(),
                colo, asn, org, vpn, "OK")
        } catch (e: Deadline) {
            downBytes = e.bytes
            downMbps = Stats.mbps(e.bytes, e.seconds)
            partial("TIMEOUT", e)
        } catch (e: UnknownHostException) {
            partial("DNS_FAIL", e)
        } catch (e: SocketTimeoutException) {
            partial("TIMEOUT", e)
        } catch (e: ConnectException) {
            partial("CONNECT_FAIL", e)
        } catch (e: HttpError) {
            partial("HTTP_ERROR", e)
        } catch (e: Exception) {
            partial("ERROR", e)
        }
    }

    /** Ask Android for the cellular data network; null when there is none within 15 s. */
    private fun acquireCellular(): Pair<Network, ConnectivityManager.NetworkCallback>? {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val latch = CountDownLatch(1)
        var found: Network? = null
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                found = network
                latch.countDown()
            }

            override fun onUnavailable() {
                latch.countDown()
            }
        }
        try {
            cm.requestNetwork(request, callback, 15_000)
        } catch (e: Exception) {
            return null
        }
        latch.await(17, TimeUnit.SECONDS)
        val network = found
        if (network == null) {
            runCatching { cm.unregisterNetworkCallback(callback) }
            return null
        }
        return network to callback
    }

    private fun prepare(c: HttpURLConnection, timeoutMs: Int): HttpURLConnection {
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.useCaches = false
        // speed.cloudflare.com/meta refuses clients that look like scripts; these two headers are what its page sends.
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) FamilyCoverage/${BuildConfig.VERSION_NAME}")
        c.setRequestProperty("Referer", "https://speed.cloudflare.com/")
        return c
    }

    private fun getText(open: (URL) -> HttpURLConnection, url: String): String {
        val c = prepare(open(URL(url)), 10_000)
        try {
            if (c.responseCode !in 200..299) throw HttpError(c.responseCode)
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** One tiny request; returns the Cloudflare site from the CF-RAY header (e.g. "...-EWR" gives EWR). */
    private fun fetchSmall(open: (URL) -> HttpURLConnection, url: String): String? {
        val c = prepare(open(URL(url)), 10_000)
        try {
            if (c.responseCode !in 200..299) throw HttpError(c.responseCode)
            c.inputStream.use { it.readBytes() }
            return c.getHeaderField("CF-RAY")?.substringAfterLast('-', "")?.takeIf { it.length == 3 }
        } finally {
            c.disconnect()
        }
    }

    /** Download with a 30 s deadline; the clock starts at the first byte, so the handshake isn't counted. */
    private fun download(open: (URL) -> HttpURLConnection, url: String): Pair<Long, Double> {
        val c = prepare(open(URL(url)), 15_000)
        try {
            if (c.responseCode !in 200..299) throw HttpError(c.responseCode)
            return Transfer.readAll(c, DEADLINE_S) { bytes, secs -> Deadline(bytes, secs) }
        } finally {
            c.disconnect()
        }
    }

    private fun upload(open: (URL) -> HttpURLConnection, url: String, size: Int): Double {
        val c = prepare(open(URL(url)), 30_000)
        try {
            return Transfer.post(c, size) { code -> HttpError(code) }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val BASE = "https://speed.cloudflare.com"
        const val SERVER = "speed.cloudflare.com"
        const val DOWN_BYTES = 1_000_000
        const val UP_BYTES = 250_000
        const val DEADLINE_S = 30.0
    }
}

/** Reading a whole response with a deadline, and posting a fixed-size body: shared by both testers. */
object Transfer {
    fun readAll(c: HttpURLConnection, deadlineS: Double, deadline: (Long, Double) -> Exception): Pair<Long, Double> {
        val input = c.inputStream
        val buf = ByteArray(64 * 1024)
        var total = 0L
        val start = System.nanoTime()
        while (true) {
            val n = try {
                input.read(buf)
            } catch (e: SocketTimeoutException) {
                throw deadline(total, (System.nanoTime() - start) / 1e9)
            }
            if (n < 0) break
            total += n
            val elapsed = (System.nanoTime() - start) / 1e9
            if (elapsed > deadlineS) throw deadline(total, elapsed)
        }
        return total to (System.nanoTime() - start) / 1e9
    }

    /** POSTs [size] zero bytes; returns the seconds from the first byte written to the response. */
    fun post(c: HttpURLConnection, size: Int, httpError: (Int) -> Exception): Double {
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(size)
        c.setRequestProperty("Content-Type", "application/octet-stream")
        val start = System.nanoTime()
        c.outputStream.use { it.write(ByteArray(size)) }
        val code = c.responseCode
        val seconds = (System.nanoTime() - start) / 1e9
        if (code !in 200..299) throw httpError(code)
        runCatching { c.inputStream.use { it.readBytes() } }
        return seconds
    }
}

/** Small facts about Android networks, kept free of state. */
object NetInfo {
    /** A VPN with a default route carries all traffic (Tailscale with an exit node, say); one without carries only its own. */
    fun hasDefaultRoute(lp: LinkProperties?): Boolean = lp?.routes?.any { it.isDefaultRoute } == true

    /** A global IPv6 address on the link: not link-local, loopback, site-local or ULA (fc00::/7). Null if unknown. */
    fun hasGlobalIpv6(lp: LinkProperties?): Boolean? {
        val addresses = lp?.linkAddresses ?: return null
        return addresses.any { la ->
            val a = la.address
            a is Inet6Address && !a.isLinkLocalAddress && !a.isLoopbackAddress && !a.isSiteLocalAddress &&
                (a.address[0].toInt() and 0xfe) != 0xfc
        }
    }
}

object Stats {
    fun median(xs: List<Double>): Double? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    fun meanAbsDiff(xs: List<Double>): Double? {
        if (xs.size < 2) return null
        return xs.zipWithNext { a, b -> abs(b - a) }.average()
    }

    fun mbps(bytes: Long, seconds: Double): Double? =
        if (bytes <= 0 || seconds <= 0.0) null else Csv.round(bytes * 8 / seconds / 1e6, 2)

    fun round1(x: Double?): Double? = x?.let { Csv.round(it, 1) }
}
