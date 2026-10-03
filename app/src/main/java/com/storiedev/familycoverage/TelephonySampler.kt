package com.storiedev.familycoverage

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Handler
import android.os.SystemClock
import android.telephony.AccessNetworkConstants
import android.telephony.CellIdentityLte
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.NetworkRegistrationInfo
import android.telephony.ServiceState
import android.telephony.SignalStrength
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import java.time.OffsetDateTime
import java.util.concurrent.Executor

/**
 * Reads every active SIM separately (dual-SIM standby keeps the non-data SIM registered, so its readings count).
 * All state lives on the logger's handler thread.
 */
class TelephonySampler(context: Context, private val handler: Handler) {
    private val subMgr = context.getSystemService(SubscriptionManager::class.java)
    private val baseTm = context.getSystemService(TelephonyManager::class.java)
    private val executor = Executor { handler.post(it) }
    private val subs = linkedMapOf<Int, SubState>()

    @Volatile var dataSubId: Int = SubscriptionManager.getActiveDataSubscriptionId()
        private set

    inner class SubState(var info: SubscriptionInfo) {
        val subId: Int = info.subscriptionId
        val tm: TelephonyManager = baseTm.createForSubscriptionId(subId)
        @Volatile var serviceState: ServiceState? = null
        @Volatile var signal: SignalStrength? = null
        @Volatile var cells: List<CellInfo> = emptyList()
        @Volatile var display: TelephonyDisplayInfo? = null
        @Volatile var lastRow: Row? = null
        /** The network code of the SIM's last real cellular registration (rows leave mcc_mnc blank without one). */
        @Volatile var knownMccMnc: String? = null

        val callback: TelephonyCallback = object : TelephonyCallback(),
            TelephonyCallback.ServiceStateListener,
            TelephonyCallback.SignalStrengthsListener,
            TelephonyCallback.CellInfoListener,
            TelephonyCallback.DisplayInfoListener {
            override fun onServiceStateChanged(serviceState: ServiceState) {
                this@SubState.serviceState = serviceState
            }

            override fun onSignalStrengthsChanged(signalStrength: SignalStrength) {
                signal = signalStrength
            }

            override fun onCellInfoChanged(cellInfo: MutableList<CellInfo>) {
                cells = cellInfo.toList()
            }

            override fun onDisplayInfoChanged(telephonyDisplayInfo: TelephonyDisplayInfo) {
                display = telephonyDisplayInfo
            }
        }
    }

    /** One SIM's reading, as written to the samples table (minus time, member and location). */
    data class Row(
        val subId: Int, val subLabel: String?, val carrier: String?, val mccMnc: String?, val dataSim: Boolean,
        val serviceState: String, val voiceTransport: String, val roaming: Boolean?, val rat: String,
        val band: Int?, val arfcn: Int?, val pci: Int?, val cellId: Long?, val tac: Int?,
        val rsrp: Int?, val rsrq: Int?, val sinr: Int?,
        val nrBand: Int?, val nrArfcn: Int?, val nrPci: Int?, val nrRsrp: Int?, val nrRsrq: Int?, val nrSinr: Int?,
        val cellAgeS: Double?,
        val displayOverride: String? = null, val ccCount: Int? = null, val bwMhz: Double? = null,
        /** The cellular registration itself (CellMath.cellService): unlike serviceState, never IN_SERVICE on Wi-Fi calling alone. */
        val cellService: String = serviceState,
    ) {
        /** How the app names this SIM to people: its carrier, plus the SIM's own label when that says more. */
        val displayName: String
            get() {
                val c = carrier ?: subLabel ?: return "SIM $subId"
                return if (subLabel != null && !subLabel.equals(c, ignoreCase = true)) "$c ($subLabel)" else c
            }
    }

    private val subsListener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            handler.post { refreshSubs() }
        }
    }

    /** Called on the handler thread when the SIM carrying data changes (automatic data switching, say). */
    var onDataSubChanged: (() -> Unit)? = null

    private val dataCallback = object : TelephonyCallback(), TelephonyCallback.ActiveDataSubscriptionIdListener {
        override fun onActiveDataSubscriptionIdChanged(subId: Int) {
            val changed = subId != dataSubId
            dataSubId = subId
            if (changed) onDataSubChanged?.invoke()
        }
    }

    fun start() {
        subMgr.addOnSubscriptionsChangedListener(executor, subsListener)
        try {
            baseTm.registerTelephonyCallback(executor, dataCallback)
        } catch (e: SecurityException) {
            // dataSubId is re-read on every sample anyway
        }
        refreshSubs()
    }

    fun stop() {
        subMgr.removeOnSubscriptionsChangedListener(subsListener)
        runCatching { baseTm.unregisterTelephonyCallback(dataCallback) }
        for (s in subs.values) runCatching { s.tm.unregisterTelephonyCallback(s.callback) }
        subs.clear()
    }

    val subscriptions: Collection<SubState> get() = subs.values

    @SuppressLint("MissingPermission")
    private fun refreshSubs() {
        val infos = try {
            subMgr.activeSubscriptionInfoList ?: emptyList()
        } catch (e: SecurityException) {
            emptyList()
        }
        val ids = infos.map { it.subscriptionId }.toSet()
        for (gone in subs.keys.filter { it !in ids }) {
            subs.remove(gone)?.let { runCatching { it.tm.unregisterTelephonyCallback(it.callback) } }
        }
        for (info in infos) {
            val existing = subs[info.subscriptionId]
            if (existing != null) {
                existing.info = info
                continue
            }
            val s = SubState(info)
            try {
                s.tm.registerTelephonyCallback(executor, s.callback)
            } catch (e: SecurityException) {
                Status.note = "phone or location permission missing"
            }
            subs[info.subscriptionId] = s
        }
    }

    /** Ask each SIM's modem for a fresh cell list; the rows are built a few seconds later from what arrived. */
    @SuppressLint("MissingPermission")
    fun requestUpdates() {
        dataSubId = SubscriptionManager.getActiveDataSubscriptionId()
        for (s in subs.values) {
            try {
                s.tm.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                        s.cells = cellInfo.toList()
                    }

                    override fun onError(errorCode: Int, detail: Throwable?) {
                        // keep the last list; cell_age_s shows how old it is
                    }
                })
            } catch (e: SecurityException) {
                Status.note = "location permission missing"
            }
        }
    }

    // Lint takes ServiceState.getOperatorNumeric() for a permission-guarded call; it reads a delivered ServiceState.
    @SuppressLint("MissingPermission")
    fun read(s: SubState): Row {
        val ss = s.serviceState
        val regs = ss?.networkRegistrationInfoList.orEmpty().map { nri ->
            CellMath.Reg(
                wlan = nri.transportType == AccessNetworkConstants.TRANSPORT_TYPE_WLAN,
                cs = (nri.domain and NetworkRegistrationInfo.DOMAIN_CS) != 0,
                ps = (nri.domain and NetworkRegistrationInfo.DOMAIN_PS) != 0,
                registered = nri.isRegistered,
                tech = nri.accessNetworkTechnology,
            )
        }
        val psWwan = regs.firstOrNull { !it.wlan && it.ps && it.registered }
        val androidState = ss?.state ?: ServiceState.STATE_OUT_OF_SERVICE
        val state = CellMath.serviceState(androidState, psWwan != null)
        val cellService = CellMath.cellService(androidState, regs)
        // Cell details only with a real cellular registration. On Wi-Fi calling alone the modem can still list a cell
        // it camps on, even another network's, and that isn't this SIM's service (seen at a dead-zone venue).
        val inService = cellService == "IN_SERVICE"

        val cells = s.cells
        val lte = cells.filterIsInstance<CellInfoLte>().firstOrNull { it.isRegistered }
        val nrPrimary = cells.filterIsInstance<CellInfoNr>().firstOrNull { it.isRegistered }
        val nrSecondary = cells.filterIsInstance<CellInfoNr>().firstOrNull {
            !it.isRegistered && it.cellConnectionStatus == CellInfo.CONNECTION_SECONDARY_SERVING
        }
        val umts = cells.filterIsInstance<CellInfoWcdma>().firstOrNull { it.isRegistered }
        val nrSignal = s.signal?.getCellSignalStrengths(CellSignalStrengthNr::class.java)
            ?.firstOrNull { CellMath.ssRsrp(it.ssRsrp) != null }

        val rat = CellMath.rat(
            serviceState = cellService,
            primaryNr = nrPrimary != null,
            primaryLte = lte != null,
            nrLeg = nrSecondary != null || nrSignal != null,
            primaryUmts = umts != null,
            psTech = psWwan?.tech,
        )

        var band: Int? = null; var arfcn: Int? = null; var pci: Int? = null; var cellId: Long? = null; var tac: Int? = null
        var rsrp: Int? = null; var rsrq: Int? = null; var sinr: Int? = null
        var nrBand: Int? = null; var nrArfcn: Int? = null; var nrPci: Int? = null
        var nrRsrp: Int? = null; var nrRsrq: Int? = null; var nrSinr: Int? = null
        var primary: CellInfo? = null
        var mccMnc: String? = ss?.operatorNumeric?.takeIf { it.isNotBlank() }

        if (inService) {
            if (rat == "NR_SA" && nrPrimary != null) {
                primary = nrPrimary
                val id = nrPrimary.cellIdentity as CellIdentityNr
                val sig = nrPrimary.cellSignalStrength as CellSignalStrengthNr
                band = id.bands.firstOrNull(); arfcn = CellMath.inRange(id.nrarfcn, 0..3_279_165)
                pci = CellMath.inRange(id.pci, 0..1007); tac = CellMath.inRange(id.tac, 0..16_777_215)
                cellId = id.nci.takeIf { it != CellInfo.UNAVAILABLE_LONG && it >= 0 }
                rsrp = CellMath.ssRsrp(sig.ssRsrp); rsrq = CellMath.ssRsrq(sig.ssRsrq); sinr = CellMath.ssSinr(sig.ssSinr)
                if (mccMnc == null) mccMnc = plmn(id.mccString, id.mncString)
            } else if (lte != null) {
                primary = lte
                val id: CellIdentityLte = lte.cellIdentity
                val sig = lte.cellSignalStrength
                band = id.bands.firstOrNull(); arfcn = CellMath.inRange(id.earfcn, 0..262_143)
                pci = CellMath.inRange(id.pci, 0..503); tac = CellMath.inRange(id.tac, 0..65_535)
                cellId = CellMath.inRange(id.ci, 0..268_435_455)?.toLong()
                rsrp = CellMath.rsrp(sig.rsrp); rsrq = CellMath.rsrq(sig.rsrq); sinr = CellMath.rssnr(sig.rssnr)
                if (mccMnc == null) mccMnc = plmn(id.mccString, id.mncString)
                if (rat == "NR_NSA") {
                    if (nrSecondary != null) {
                        val nid = nrSecondary.cellIdentity as CellIdentityNr
                        val nsig = nrSecondary.cellSignalStrength as CellSignalStrengthNr
                        nrBand = nid.bands.firstOrNull(); nrArfcn = CellMath.inRange(nid.nrarfcn, 0..3_279_165)
                        nrPci = CellMath.inRange(nid.pci, 0..1007)
                        nrRsrp = CellMath.ssRsrp(nsig.ssRsrp); nrRsrq = CellMath.ssRsrq(nsig.ssRsrq); nrSinr = CellMath.ssSinr(nsig.ssSinr)
                    }
                    if (nrRsrp == null && nrSignal != null) {
                        nrRsrp = CellMath.ssRsrp(nrSignal.ssRsrp); nrRsrq = CellMath.ssRsrq(nrSignal.ssRsrq); nrSinr = CellMath.ssSinr(nrSignal.ssSinr)
                    }
                }
            } else if (umts != null) {
                primary = umts
                val id = umts.cellIdentity
                arfcn = CellMath.inRange(id.uarfcn, 0..16_383); pci = CellMath.inRange(id.psc, 0..511)
                cellId = CellMath.inRange(id.cid, 0..268_435_455)?.toLong(); tac = CellMath.inRange(id.lac, 0..65_535)
                if (mccMnc == null) mccMnc = plmn(id.mccString, id.mncString)
            }
        }
        val cellAgeS = primary?.let { (SystemClock.elapsedRealtime() - it.timestampMillis) / 1000.0 }
        // The icon override and the serving carriers' bandwidths; only meaningful while in service.
        val displayOverride = if (inService) s.display?.let { CellMath.displayOverride(it.overrideNetworkType) } else null
        val (ccCount, bwMhz) = CellMath.bandwidth(if (inService) ss?.cellBandwidths else null)

        // The network code only for a real cellular registration: blank otherwise, so a camped-on neighbour or the
        // last network can't be mistaken for service. knownMccMnc keeps the SIM's last real one for the test texts.
        val servingCode = if (inService) mccMnc ?: s.tm.networkOperator?.takeIf { it.isNotBlank() } else null
        if (servingCode != null) s.knownMccMnc = servingCode
        return Row(
            subId = s.subId,
            subLabel = s.info.displayName?.toString()?.trim()?.takeIf { it.isNotEmpty() },
            carrier = stableCarrier(s),
            mccMnc = servingCode,
            dataSim = s.subId == dataSubId,
            serviceState = state,
            voiceTransport = CellMath.voiceTransport(regs),
            roaming = ss?.roaming,
            rat = rat,
            band = band, arfcn = arfcn, pci = pci, cellId = cellId, tac = tac,
            rsrp = rsrp, rsrq = rsrq, sinr = sinr,
            nrBand = nrBand, nrArfcn = nrArfcn, nrPci = nrPci, nrRsrp = nrRsrp, nrRsrq = nrRsrq, nrSinr = nrSinr,
            cellAgeS = cellAgeS?.let { Csv.round(it, 1) },
            displayOverride = displayOverride, ccCount = ccCount, bwMhz = bwMhz,
            cellService = cellService,
        ).also { s.lastRow = it }
    }

    /**
     * The SIM's carrier, stable across the day. SubscriptionInfo.getCarrierName() is the status-bar label, which a
     * Pixel changes to "Wi-Fi Calling" while calls ride Wi-Fi (seen 2026-09-26), so it can't name a network. The
     * carrier-id database name ("Verizon Wireless", "AT&T") and the SIM's SPN don't move.
     */
    private fun stableCarrier(s: SubState): String? =
        listOf(s.tm.simCarrierIdName?.toString(), s.tm.simOperatorName, s.info.carrierName?.toString())
            .firstNotNullOfOrNull { name -> name?.trim()?.takeIf { it.isNotEmpty() } }

    companion object {
        private fun plmn(mcc: String?, mnc: String?): String? =
            if (!mcc.isNullOrBlank() && !mnc.isNullOrBlank()) mcc + mnc else null

        /** The samples row, in Tables.SAMPLES order. [moving] is the tracker's mode when the row is written. */
        fun values(t: OffsetDateTime, member: String, r: Row, wifi: Boolean, fix: Location?, moving: Boolean): List<Any?> = listOf(
            Csv.ts(t), member, r.subId, r.subLabel, r.carrier, r.mccMnc, r.dataSim, r.serviceState, r.voiceTransport,
            r.roaming, wifi, r.rat, r.band, r.arfcn, r.pci, r.cellId, r.tac, r.rsrp, r.rsrq, r.sinr,
            r.nrBand, r.nrArfcn, r.nrPci, r.nrRsrp, r.nrRsrq, r.nrSinr, r.cellAgeS,
            fix?.let { Csv.round(it.latitude, 7) }, fix?.let { Csv.round(it.longitude, 7) },
            fix?.takeIf { it.hasAccuracy() }?.let { Csv.round(it.accuracy.toDouble(), 1) },
            fix?.takeIf { it.hasAltitude() }?.let { Csv.round(it.altitude, 1) },
            fix?.takeIf { it.hasSpeed() }?.let { Csv.round(it.speed.toDouble(), 2) },
            fix?.let { Csv.round(LocationTracker.fixAgeS(it), 1) },
            r.displayOverride, r.ccCount, r.bwMhz,
            r.cellService, if (moving) "MOVING" else "STILL",
        )
    }
}
