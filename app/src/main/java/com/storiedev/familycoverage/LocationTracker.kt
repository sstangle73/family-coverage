package com.storiedev.familycoverage

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Handler
import android.os.SystemClock
import java.util.concurrent.Executor

/**
 * Precise location for every row, plus a track, at as little battery as that allows.
 *
 * Moving: the fused provider at high accuracy every 10 s (GPS outdoors, Wi-Fi and cell indoors). After 3 minutes
 * within 25 m (or the fixes' own error, if larger) it counts as still: GPS sleeps and a low-power fix arrives every
 * 5 minutes. While still, rows carry the best fix of the still period, so a phone parked in a library keeps its good
 * outdoor fix rather than a Wi-Fi guess.
 *
 * Waking from still is two steps (1.0.17): the significant-motion sensor (or a low-power fix) starts "checking", which
 * asks for low-power fixes every 15 s; only a fix 100 m from the still spot turns GPS back on. Walking round the house
 * or the office never does, which was the big drain before.
 */
class LocationTracker(
    context: Context,
    private val handler: Handler,
    private val onTrackPoint: (Location) -> Unit,
) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private val sm: SensorManager? = context.getSystemService(SensorManager::class.java)
    private val executor = Executor { handler.post(it) }
    private val fused = lm.hasProvider(LocationManager.FUSED_PROVIDER)
    private val motionSensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)

    private enum class Mode { MOVING, CHECKING, STILL }

    private var state = Mode.MOVING
    private var started = false
    private val window = ArrayDeque<Location>()
    private var lastTrack: Location? = null

    /** Called on the handler thread whenever moving starts or stops. */
    var onMovingChanged: ((Boolean) -> Unit)? = null

    @Volatile var latest: Location? = null
        private set
    @Volatile var anchor: Location? = null
        private set

    /** Moving means GPS is on and the logger samples every 10 s; checking and still both count as not moving. */
    val isMoving: Boolean get() = state == Mode.MOVING

    val mode: String
        get() = when (state) {
            Mode.MOVING -> "moving"
            Mode.CHECKING -> "checking for movement"
            Mode.STILL -> "still"
        }

    private val listener = LocationListener { onLocation(it) }
    private val motion = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            handler.post { if (started && state == Mode.STILL) setChecking() }
        }
    }
    private val checkTimeout = Runnable { if (state == Mode.CHECKING) setStill(fromChecking = true) }

    fun start() {
        if (started) return
        started = true
        setMoving("start")
    }

    fun stop() {
        started = false
        handler.removeCallbacks(checkTimeout)
        lm.removeUpdates(listener)
        motionSensor?.let { sm?.cancelTriggerSensor(motion, it) }
    }

    /** The fix a row carries: the latest while moving, the best of the still period otherwise. */
    fun rowFix(): Location? = if (state == Mode.MOVING) latest else (anchor ?: latest)

    @SuppressLint("MissingPermission")
    private fun request(quality: Int, intervalMs: Long, minMs: Long) {
        lm.removeUpdates(listener)
        val request = LocationRequest.Builder(intervalMs)
            .setQuality(quality)
            .setMinUpdateIntervalMillis(minMs)
            .build()
        val provider = when {
            fused -> LocationManager.FUSED_PROVIDER
            quality == LocationRequest.QUALITY_HIGH_ACCURACY -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        try {
            lm.requestLocationUpdates(provider, request, executor, listener)
        } catch (e: SecurityException) {
            Status.location = "no location permission"
        } catch (e: IllegalArgumentException) {
            Status.location = "no $provider provider"
        }
    }

    private fun setMoving(why: String) {
        val was = state
        state = Mode.MOVING
        handler.removeCallbacks(checkTimeout)
        anchor = null
        window.clear()
        motionSensor?.let { sm?.cancelTriggerSensor(motion, it) }
        request(LocationRequest.QUALITY_HIGH_ACCURACY, MOVING_INTERVAL_MS, MOVING_MIN_MS)
        Status.location = "moving ($why)"
        if (was != Mode.MOVING) onMovingChanged?.invoke(true)
    }

    /** Motion felt while still: look with low-power fixes for a while before turning GPS on. */
    private fun setChecking() {
        state = Mode.CHECKING
        request(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY, CHECK_INTERVAL_MS, CHECK_MIN_MS)
        handler.removeCallbacks(checkTimeout)
        handler.postDelayed(checkTimeout, CHECK_WINDOW_MS)
        Status.location = "checking for movement"
    }

    private fun setStill(fromChecking: Boolean = false) {
        val was = state
        state = Mode.STILL
        handler.removeCallbacks(checkTimeout)
        if (!fromChecking) {
            anchor = window.minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
            anchor?.let { writeTrack(it, force = true) }
        }
        window.clear()
        request(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY, STILL_INTERVAL_MS, STILL_MIN_MS)
        motionSensor?.let { sm?.requestTriggerSensor(motion, it) }
        Status.location = "still"
        if (was == Mode.MOVING) onMovingChanged?.invoke(false)
    }

    private fun onLocation(loc: Location) {
        latest = loc
        Status.lastFix = loc
        when (state) {
            Mode.MOVING -> {
                window.addLast(loc)
                while (window.size > 1 && ageMs(window.first(), loc) > STILL_WINDOW_MS) window.removeFirst()
                writeTrack(loc, force = false)
                val first = window.first()
                val settled = window.all { it.distanceTo(first) <= stillRadius(first, it) }
                if (ageMs(first, loc) >= STILL_WINDOW_MS - 10_000L && settled) setStill()
            }
            Mode.CHECKING, Mode.STILL -> {
                val a = anchor
                when {
                    a == null -> anchor = loc
                    moved(a, loc) -> setMoving("moved ${loc.distanceTo(a).toInt()} m")
                    // A low-power fix that's merely wandering: keep the better one as the still spot.
                    state == Mode.STILL && loc.hasAccuracy() && a.hasAccuracy() && loc.accuracy < a.accuracy -> anchor = loc
                    // Still without a motion sensor: a far-off fix is the only way to notice leaving.
                    state == Mode.STILL && motionSensor == null && loc.distanceTo(a) > WAKE_DISTANCE_M / 2 -> setChecking()
                }
            }
        }
    }

    private fun writeTrack(loc: Location, force: Boolean) {
        val last = lastTrack
        if (force || last == null || loc.distanceTo(last) >= TRACK_MIN_M || ageMs(last, loc) >= TRACK_MAX_GAP_MS) {
            lastTrack = loc
            onTrackPoint(loc)
        }
    }

    companion object {
        const val MOVING_INTERVAL_MS = 10_000L
        const val MOVING_MIN_MS = 5_000L
        const val STILL_INTERVAL_MS = 300_000L
        const val STILL_MIN_MS = 120_000L
        const val CHECK_INTERVAL_MS = 15_000L
        const val CHECK_MIN_MS = 10_000L
        const val CHECK_WINDOW_MS = 150_000L
        const val STILL_WINDOW_MS = 180_000L
        const val STILL_RADIUS_M = 25f
        const val WAKE_DISTANCE_M = 100f
        const val TRACK_MIN_M = 10f
        const val TRACK_MAX_GAP_MS = 60_000L

        fun ageMs(a: Location, b: Location): Long = (b.elapsedRealtimeNanos - a.elapsedRealtimeNanos) / 1_000_000L

        fun fixAgeS(loc: Location): Double =
            (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1e9

        /** A fix far enough from the still spot, and sure enough of itself, to mean the phone has left. */
        fun moved(anchor: Location, loc: Location): Boolean =
            Moves.left(loc.distanceTo(anchor).toDouble(), if (loc.hasAccuracy()) loc.accuracy.toDouble() else null)

        private fun stillRadius(a: Location, b: Location): Float {
            val err = (if (a.hasAccuracy()) a.accuracy else 0f) + (if (b.hasAccuracy()) b.accuracy else 0f)
            return err.coerceIn(STILL_RADIUS_M, WAKE_DISTANCE_M)
        }
    }
}

/** The movement decisions, free of Android types for the unit tests. */
object Moves {
    /** Left the still spot: over 100 m away, by a fix whose own error is under 100 m. */
    fun left(distanceM: Double, accuracyM: Double?): Boolean =
        distanceM > LocationTracker.WAKE_DISTANCE_M && (accuracyM == null || accuracyM < LocationTracker.WAKE_DISTANCE_M)
}
