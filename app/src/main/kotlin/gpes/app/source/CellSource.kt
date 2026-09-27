package gpes.app.source

import android.annotation.SuppressLint
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.telephony.CellIdentityNr
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoTdscdma
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.TelephonyManager
import gpes.core.model.CellObs
import gpes.core.model.CellScan
import gpes.core.pipeline.MeasurementSink
import gpes.core.pipeline.MeasurementSource
import java.util.concurrent.Executor

/**
 * Raw cellular environment: serving and neighbour cells with identity, signal and timing advance.
 * It polls `requestCellInfoUpdate` (the modem may answer from a cache; `measuredNs` tells the real
 * age on API 30+). This needs ACCESS_FINE_LOCATION only. It works without internet and without
 * Google, which is the point: an offline cell database can turn it into a coarse position (roadmap).
 */
class CellSource(
    private val tm: TelephonyManager,
    private val handler: Handler,
    private val periodMs: Long = 2000,
) : MeasurementSource {
    private var sink: MeasurementSink? = null
    private val executor = Executor { handler.post(it) }
    private var lastCells: List<CellObs>? = null

    private val poll = object : Runnable {
        @SuppressLint("MissingPermission")
        override fun run() {
            if (sink == null) return
            try {
                tm.requestCellInfoUpdate(executor, object : TelephonyManager.CellInfoCallback() {
                    override fun onCellInfo(cellInfo: MutableList<CellInfo>) = emit(cellInfo)
                })
            } catch (_: SecurityException) {
            } catch (_: IllegalStateException) {
            }
            handler.postDelayed(this, periodMs)
        }
    }

    private fun emit(infos: List<CellInfo>) {
        val cells = infos.mapNotNull { runCatching { it.toObs() }.getOrNull() }
        // Skip exact repeats of a cached answer; a changed signal or a new cell is always recorded.
        if (cells.isEmpty() || cells == lastCells) return
        lastCells = cells
        sink?.emit(CellScan(SystemClock.elapsedRealtimeNanos(), cells))
    }

    override fun start(sink: MeasurementSink) {
        this.sink = sink
        handler.post(poll)
    }

    override fun stop() {
        sink = null
        handler.removeCallbacks(poll)
    }
}

private fun Int.v(): Int? = if (this == CellInfo.UNAVAILABLE || this == Int.MIN_VALUE) null else this
private fun Long.v(): Long? = if (this == CellInfo.UNAVAILABLE_LONG || this == Long.MAX_VALUE || this == Int.MAX_VALUE.toLong()) null else this

private fun CellInfo.toObs(): CellObs {
    val measured = measuredNs()
    val conn = cellConnectionStatus.v()
    return when (this) {
        is CellInfoLte -> {
            val id = cellIdentity
            val s = cellSignalStrength
            CellObs(
                "LTE", isRegistered, id.mccString?.toIntOrNull(), id.mncString?.toIntOrNull(), id.tac.v()?.toLong(), id.ci.v()?.toLong(),
                id.pci.v(), id.earfcn.v(), id.bandwidth.v(), s.rssi.v(), s.rsrp.v(), s.rsrq.v(), s.rssnr.v(), s.timingAdvance.v(),
                s.asuLevel.v(), measured, conn,
            )
        }
        is CellInfoNr -> {
            val id = cellIdentity as CellIdentityNr
            val s = cellSignalStrength as CellSignalStrengthNr
            CellObs(
                "NR", isRegistered, id.mccString?.toIntOrNull(), id.mncString?.toIntOrNull(), id.tac.v()?.toLong(), id.nci.v(),
                id.pci.v(), id.nrarfcn.v(), null, null, s.ssRsrp.v(), s.ssRsrq.v(), s.ssSinr.v(), null, s.asuLevel.v(), measured, conn,
            )
        }
        is CellInfoWcdma -> {
            val id = cellIdentity
            val s = cellSignalStrength
            CellObs(
                "WCDMA", isRegistered, id.mccString?.toIntOrNull(), id.mncString?.toIntOrNull(), id.lac.v()?.toLong(), id.cid.v()?.toLong(),
                id.psc.v(), id.uarfcn.v(), null, s.dbm.v(), null, null, null, null, s.asuLevel.v(), measured, conn,
            )
        }
        is CellInfoGsm -> {
            val id = cellIdentity
            val s = cellSignalStrength
            CellObs(
                "GSM", isRegistered, id.mccString?.toIntOrNull(), id.mncString?.toIntOrNull(), id.lac.v()?.toLong(), id.cid.v()?.toLong(),
                id.bsic.v(), id.arfcn.v(), null, s.dbm.v(), null, null, null, s.timingAdvance.v(), s.asuLevel.v(), measured, conn,
            )
        }
        is CellInfoTdscdma -> {
            val id = cellIdentity
            val s = cellSignalStrength
            CellObs(
                "TDSCDMA", isRegistered, id.mccString?.toIntOrNull(), id.mncString?.toIntOrNull(), id.lac.v()?.toLong(), id.cid.v()?.toLong(),
                id.cpid.v(), id.uarfcn.v(), null, s.dbm.v(), s.rscp.v(), null, null, null, s.asuLevel.v(), measured, conn,
            )
        }
        is CellInfoCdma -> {
            val id = cellIdentity
            CellObs(
                "CDMA", isRegistered, null, null, id.networkId.v()?.toLong(), id.basestationId.v()?.toLong(),
                null, null, null, cellSignalStrength.dbm.v(), null, null, null, null, cellSignalStrength.asuLevel.v(), measured, conn,
            )
        }
        else -> CellObs("UNKNOWN", isRegistered, measuredNs = measured, connectionStatus = conn)
    }
}

/** When the modem measured the cell, in the elapsedRealtimeNanos base (both APIs count since boot). */
@Suppress("DEPRECATION")
private fun CellInfo.measuredNs(): Long? =
    if (Build.VERSION.SDK_INT >= 30) timestampMillis.takeIf { it > 0 }?.times(1_000_000) else timeStamp.takeIf { it > 0 }
