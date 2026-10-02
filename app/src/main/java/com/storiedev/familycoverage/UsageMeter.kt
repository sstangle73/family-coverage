package com.storiedev.familycoverage

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicLong

/**
 * Mobile data use, for "how much does each line really use". For each interval it counts:
 *  - rx/tx: bytes on the phone's cellular internet connection. That's the connection's interface plus the "v4-"
 *    interface Android's 464XLAT adds for IPv4 on IPv6-only networks (Android counts translated traffic on the v4-
 *    interface only, so the sum counts nothing twice). It's what a carrier bills, a VPN's tunnel and cross-SIM
 *    calls included. VoLTE calls ride a separate IMS connection and aren't in it.
 *  - app_rx/app_tx: of which this app's own tests, checks and uploads, measured around each one.
 *  - all_rx/all_tx: Android's total over every cellular interface, VoLTE included: a cross-check.
 * Totals only: telling apps apart would need usage access, which the app doesn't ask for.
 *
 * A dual-SIM phone carries data on one SIM at a time, so an interval belongs to one SIM: LoggerService ends it early
 * when the data SIM changes. Everything except the app counters lives on the logger's handler thread.
 */
class UsageMeter(context: Context, private val handler: Handler) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    /** One cellular internet connection, and its interfaces' counters when last read (rx, tx, v4 rx, v4 tx). */
    private class Link(var iface: String? = null, val last: LongArray = LongArray(4))

    data class Interval(
        val seconds: Double, val subId: Int, val rx: Long, val tx: Long, val appRx: Long, val appTx: Long,
        val allRx: Long?, val allTx: Long?,
    )

    private val links = HashMap<Network, Link>()
    private val ended = LongArray(2) // rx, tx of connections that closed during the interval
    private var allLast: LongArray? = null
    private val appRx = AtomicLong()
    private val appTx = AtomicLong()
    private var registered = false

    /** Whether start() has run: a service stopped within moments of starting has no interval to write. */
    var started = false
        private set

    /** When the current interval began (elapsedRealtime), and the data SIM it belongs to (-1: none). */
    private var startMs = 0L
    private var sub = -1

    /** Whether a cellular internet connection is up, for the data check (read from the network thread). */
    @Volatile var cellularUp = false
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            val link = links.getOrPut(network) { Link() }
            if (lp.interfaceName != link.iface) {
                if (link.iface != null) close(link)
                link.iface = lp.interfaceName
                read(link.iface).copyInto(link.last) // the interface's earlier bytes belong to someone else
            }
            cellularUp = links.isNotEmpty()
        }

        override fun onLost(network: Network) {
            links.remove(network)?.let { close(it) }
            cellularUp = links.isNotEmpty()
        }
    }

    fun start(dataSub: Int) {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(request, callback, handler) }.onSuccess { registered = true }
        allLast = readAll()
        startMs = SystemClock.elapsedRealtime()
        sub = dataSub
        started = true
    }

    fun stop() {
        if (registered) runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
    }

    /** Time for a row: the interval is up, or the data SIM changed. */
    fun due(dataSub: Int): Boolean =
        dataSub != sub || SystemClock.elapsedRealtime() - startMs >= Config.USAGE_INTERVAL_MS

    /** This app's own cellular bytes, from the network thread. */
    fun addApp(rx: Long, tx: Long) {
        appRx.addAndGet(rx.coerceAtLeast(0))
        appTx.addAndGet(tx.coerceAtLeast(0))
    }

    /** Ends the interval and returns its counts; the next one starts now, for [nextSub]. */
    fun take(nextSub: Int): Interval {
        val sum = ended.copyOf()
        ended.fill(0)
        for (link in links.values) {
            val now = read(link.iface)
            for (i in 0 until 4) {
                if (unreadable(link.last[i], now[i])) continue // keep the last good reading
                sum[i % 2] += delta(link.last[i], now[i])
                link.last[i] = now[i]
            }
        }
        val all = readAll()
        val before = allLast
        allLast = all
        val t = SystemClock.elapsedRealtime()
        val interval = Interval(
            seconds = (t - startMs) / 1000.0, subId = sub, rx = sum[0], tx = sum[1],
            appRx = appRx.getAndSet(0), appTx = appTx.getAndSet(0),
            allRx = if (all != null && before != null) deltaTotal(before[0], all[0]) else null,
            allTx = if (all != null && before != null) deltaTotal(before[1], all[1]) else null,
        )
        startMs = t
        sub = nextSub
        return interval
    }

    /** A closing connection: count what it carried since the last read. */
    private fun close(link: Link) {
        val now = read(link.iface)
        for (i in 0 until 4) if (!unreadable(link.last[i], now[i])) ended[i % 2] += delta(link.last[i], now[i])
    }

    companion object {
        /** An interface's bytes since boot, unknown as 0: base rx, tx, then its 464XLAT "v4-" interface's rx, tx. */
        fun read(iface: String?): LongArray {
            if (iface == null) return LongArray(4)
            val v4 = "v4-$iface"
            return longArrayOf(
                TrafficStats.getRxBytes(iface), TrafficStats.getTxBytes(iface),
                TrafficStats.getRxBytes(v4), TrafficStats.getTxBytes(v4),
            ).also { a -> for (i in a.indices) a[i] = a[i].coerceAtLeast(0) }
        }

        /** Android's total over every cellular interface, or null where the device doesn't report it. */
        fun readAll(): LongArray? {
            val rx = TrafficStats.getMobileRxBytes()
            val tx = TrafficStats.getMobileTxBytes()
            return if (rx < 0 || tx < 0) null else longArrayOf(rx, tx)
        }

        /** Growth of a counter; one that went backwards restarted (a new interface), so all of it is new. */
        fun delta(last: Long, now: Long): Long = if (now >= last) now - last else now.coerceAtLeast(0)

        /**
         * A zero after real counts is an interface Android couldn't read for a moment, not a restart: taking it as
         * one would count the whole counter again at the next read.
         */
        fun unreadable(last: Long, now: Long): Boolean = now <= 0L && last > 0L

        /**
         * Growth of Android's all-cellular total. It sums whichever interfaces exist, so it drops when one goes
         * away; counting that as a restart (as delta does) added the whole total again (up to 1 GB a row, seen
         * 2026-09-29). A drop counts as nothing.
         */
        fun deltaTotal(last: Long, now: Long): Long = if (now >= last) now - last else 0L
    }
}
