package com.storiedev.familycoverage

import android.location.Location
import android.os.SystemClock
import android.widget.LinearLayout
import java.time.LocalTime
import java.time.OffsetDateTime

/**
 * Test texts, as each build does them. The full build (F-Droid, GitHub) sends and answers them itself; the Google
 * Play build has no SMS permission, so it prompts at a place and opens the phone's Messages app with the test
 * filled in. Everything runs on the logger's handler thread.
 */
interface TextTests {
    fun start() {}
    /** Every text tick: follow visits to the household's places, and start a test when one is due. */
    fun tick()
    /** The owner's "Send a test now". */
    fun manual()
    /** The Play build: the owner opened Messages with test [testId] (written by the compose screen at [sentAt]). */
    fun composed(testId: String, trigger: String, placeId: String?, sentAt: LocalTime) {}
    fun stop() {}
}

/** The flavor's part of the main screen: the test-text settings and status. */
interface TextsUi {
    fun build(root: LinearLayout)
    fun render()
    fun pause() {}
}

/** Visits to the household's places, judged from the logger's fixes (handler thread only). */
class PlaceVisits {
    class Visit(val placeId: String, val enteredMs: Long) {
        var outsideSinceMs: Long? = null
        var exchanges = 0
    }

    /** All the household's places: a visit to any of them is noted, and texts name the place they were at. */
    var places: List<Place> = emptyList()
        private set
    /** The places whose tests are this member's: arriving at one starts a test, and its fallback times apply. */
    var mine: List<Place> = emptyList()
        private set
    var visit: Visit? = null
        private set

    fun refresh(household: Household?, member: String?) {
        places = household?.places.orEmpty()
        mine = places.filter { it.isFor(member) }
    }

    fun isMine(placeId: String): Boolean = mine.any { it.id == placeId }

    fun placeAt(fix: Location): Place? = places.firstOrNull { where(fix, it) == TextMath.Where.INSIDE }

    fun where(fix: Location, p: Place): TextMath.Where {
        val d = FloatArray(1)
        Location.distanceBetween(fix.latitude, fix.longitude, p.lat, p.lon, d)
        return TextMath.where(d[0].toDouble(), if (fix.hasAccuracy()) fix.accuracy.toDouble() else null, p.radiusM)
    }

    /** Starts a visit inside a place; ends it after 5 minutes clearly outside. A wobbly fix changes nothing. */
    fun update(here: Place?, fix: Location?) {
        val now = SystemClock.elapsedRealtime()
        val v = visit
        if (here != null) {
            if (v == null || v.placeId != here.id) visit = Visit(here.id, now) else v.outsideSinceMs = null
            return
        }
        if (v == null || fix == null) return
        val p = places.firstOrNull { it.id == v.placeId }
        if (p == null || where(fix, p) == TextMath.Where.OUTSIDE) {
            val since = v.outsideSinceMs ?: now.also { v.outsideSinceMs = it }
            if (now - since >= Config.TEXT_VISIT_END_MS) visit = null
        }
    }

    /** The current visit, if this phone has been inside it for [dwellMs]. */
    fun dwelled(dwellMs: Long): Visit? =
        visit?.takeIf { it.outsideSinceMs == null && SystemClock.elapsedRealtime() - it.enteredMs >= dwellMs }

    fun name(id: String?): String? = places.firstOrNull { it.id == id }?.name
}

/** A texts row's state columns, taken when the text was sent or arrived. */
class TextSnap(
    val subLabel: String?, val carrier: String?, val mccMnc: String?, val serviceState: String?,
    val voiceTransport: String?, val rat: String?, val rsrp: Int?, val wifi: Boolean, val dataSim: Boolean?,
    val placeId: String?, val lat: Double?, val lon: Double?, val accuracy: Double?,
    /** The cellular registration (CellMath.cellService): not written to texts, used to judge a refused silent text. */
    val cellService: String? = null,
)

/** Writes texts rows for either build: the SIM's state and the place, from the logger's own readings. */
class TextLog(
    private val prefs: Prefs,
    private val sampler: TelephonySampler,
    private val tracker: LocationTracker,
    private val store: CsvStore,
    private val wifi: () -> Boolean,
    private val visits: PlaceVisits,
) {
    fun snapshot(subId: Int, placeId: String?): TextSnap {
        val r = sampler.subscriptions.firstOrNull { it.subId == subId }?.let { sampler.read(it) }
        val fix = tracker.rowFix()
        return TextSnap(
            r?.subLabel, r?.carrier, r?.mccMnc, r?.serviceState, r?.voiceTransport, r?.rat, r?.rsrp, wifi(), r?.dataSim,
            placeId ?: fix?.let { visits.placeAt(it) }?.id,
            fix?.let { Csv.round(it.latitude, 7) }, fix?.let { Csv.round(it.longitude, 7) },
            fix?.takeIf { it.hasAccuracy() }?.let { Csv.round(it.accuracy.toDouble(), 1) },
            r?.cellService,
        )
    }

    /** The fix to judge places by: while still, the still period's best fix, however old; while moving, a fresh one. */
    fun currentFix(): Location? =
        tracker.rowFix()?.takeIf { !tracker.isMoving || LocationTracker.fixAgeS(it) < 120 }

    fun write(
        t: OffsetDateTime, event: String, testId: String, exchangeId: String?, role: String, trigger: String?,
        transport: String, subId: Int, result: String?, latency: Double?, rtt: Double?, s: TextSnap, peer: String,
        state: Boolean = true,
    ) {
        val member = prefs.member ?: return
        fun <T> st(v: T) = if (state) v else null
        store.append(
            "texts", t, listOf(
                Csv.ts(t), member, event, testId, exchangeId, role, trigger, transport, subId.takeIf { it >= 0 },
                s.subLabel, s.carrier, s.mccMnc, peer, result, latency, rtt, st(s.serviceState),
                st(s.voiceTransport), st(s.rat), st(s.rsrp), st(s.wifi), st(s.dataSim), st(s.placeId), st(s.lat),
                st(s.lon), st(s.accuracy),
            ),
        )
    }

    /** Resets the daily counts at the first tick of a new day. */
    fun rollDay(day: String) {
        if (prefs.textDay == day) return
        prefs.textDay = day
        prefs.textExchanges = 0
        prefs.textEchoes = 0
        prefs.textSlotsDone = emptySet()
    }

    fun partner(): String? = prefs.validPartner()
}

/** The partner chosen on this phone, if it's still a member of the household and isn't this phone's own member. */
fun Prefs.validPartner(): String? = textPartner?.takeIf { p -> p != member && household?.members?.contains(p) == true }
