package com.storiedev.familycoverage

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The data check: does mobile data work right now, and how fast does it answer? Every 2 minutes off Wi-Fi it opens a
 * TCP connection to Cloudflare's 1.1.1.1, port 80, and sends HEAD /. Cloudflare answers with a tiny redirect to
 * HTTPS, and that answer, with its "Server: cloudflare" header, is the proof. It uses about 0.8 KB a check: no TLS,
 * and no DNS lookup, which could fail on its own (through a VPN's resolver, say).
 *
 * It takes the phone's default network, as any app would. Off Wi-Fi that's the data SIM; inside a VPN such as Tailscale without an
 * exit node the request still leaves through the carrier (test_path=default), and with one it rides the tunnel
 * through home (test_path=exit_node).
 */
class DataCheck(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    data class Result(
        val connectMs: Double? = null,
        val totalMs: Double? = null,
        val colo: String? = null,
        val vpnActive: Boolean = false,
        val testPath: String? = null,
        val result: String,
        /** For the status screen only (not a CSV column): the exception behind a failed check. */
        val error: String? = null,
    )

    fun run(cellularUp: Boolean): Result {
        val active = cm.activeNetwork
        val vpn = cm.getNetworkCapabilities(active)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val path = if (vpn && NetInfo.hasDefaultRoute(cm.getLinkProperties(active))) "exit_node" else "default"
        if (active == null || !cellularUp) return Result(vpnActive = vpn, testPath = path, result = "NO_DATA_NETWORK")
        var connectMs: Double? = null
        fun failed(result: String, e: Exception) = Result(connectMs, null, null, vpn, path, result,
            e.javaClass.simpleName + (e.message?.let { ": " + it.take(80) } ?: ""))
        val socket = Socket()
        return try {
            socket.soTimeout = Config.CHECK_TIMEOUT_MS
            val t0 = System.nanoTime()
            socket.connect(InetSocketAddress(InetAddress.getByAddress(HOST), 80), Config.CHECK_TIMEOUT_MS)
            connectMs = Stats.round1((System.nanoTime() - t0) / 1e6)
            socket.getOutputStream().apply {
                write(request())
                flush()
            }
            val answer = CheckMath.parse(readHead(socket.getInputStream()))
            Result(connectMs, Stats.round1((System.nanoTime() - t0) / 1e6), answer.colo, vpn, path, CheckMath.result(answer))
        } catch (e: SocketTimeoutException) {
            failed("TIMEOUT", e)
        } catch (e: IOException) {
            failed(CheckMath.classify(e.message, e is ConnectException || e is NoRouteToHostException), e)
        } finally {
            runCatching { socket.close() }
        }
    }

    /** The response head: up to the blank line, at most 4 KB. */
    private fun readHead(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (out.size() < 4096) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (String(out.toByteArray(), Charsets.ISO_8859_1).contains("\r\n\r\n")) break
        }
        return String(out.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun request(): ByteArray =
        ("HEAD / HTTP/1.1\r\nHost: 1.1.1.1\r\nUser-Agent: FamilyCoverage/${BuildConfig.VERSION_NAME}\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)

    companion object {
        private val HOST = byteArrayOf(1, 1, 1, 1)
    }
}

/** The check's decisions, free of Android types for the unit tests. */
object CheckMath {
    data class Answer(val status: Int?, val cloudflare: Boolean, val colo: String?)

    /** A response head: its status, whether Cloudflare sent it, and the Cloudflare site from CF-RAY ("...-ORD"). */
    fun parse(head: String): Answer {
        val lines = head.split("\r\n")
        val status = Regex("^HTTP/\\d(?:\\.\\d)? (\\d{3})").find(lines.firstOrNull() ?: "")?.groupValues?.get(1)?.toInt()
        var server: String? = null
        var ray: String? = null
        for (line in lines.drop(1)) {
            val i = line.indexOf(':')
            if (i <= 0) continue
            val value = line.substring(i + 1).trim()
            when (line.substring(0, i).trim().lowercase()) {
                "server" -> server = value
                "cf-ray" -> ray = value
            }
        }
        val colo = ray?.substringAfterLast('-', "")?.takeIf { it.length == 3 && it.all(Char::isLetter) }
        return Answer(status, server.equals("cloudflare", ignoreCase = true), colo)
    }

    /** OK only for Cloudflare's own answer: anything else (a carrier's block page) is HTTP_ERROR, and no answer ERROR. */
    fun result(a: Answer): String = when {
        a.status == null -> "ERROR"
        a.cloudflare && a.status in 200..399 -> "OK"
        else -> "HTTP_ERROR"
    }

    /** A failed connection: no network at all reads NO_DATA_NETWORK, a refusal or unreachable host CONNECT_FAIL. */
    fun classify(message: String?, connectFailure: Boolean): String = when {
        message?.contains("ENETUNREACH") == true -> "NO_DATA_NETWORK"
        connectFailure -> "CONNECT_FAIL"
        else -> "ERROR"
    }
}
