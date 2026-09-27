package gpes.app.source

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssClock
import android.location.GnssMeasurement
import android.location.GnssMeasurementRequest
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import gpes.core.model.AgcInfo
import gpes.core.model.GnssClockInfo
import gpes.core.model.GnssMeasurementBatch
import gpes.core.model.GnssRawMeas
import gpes.core.model.GnssStatusSnapshot
import gpes.core.model.NmeaSentence
import gpes.core.model.SatInfo
import gpes.core.pipeline.MeasurementSink
import gpes.core.pipeline.MeasurementSource
import java.util.concurrent.Executor

/**
 * Raw GNSS internals: satellite status (C/N0, used-in-fix), raw measurements (with AGC), and NMEA.
 * These are the strongest on-device jamming/spoofing indicators. They are recorded for offline
 * analysis and only lightly used by the Phase 1 trust evaluator.
 */
class GnssRawSource(
    context: Context,
    private val handler: Handler,
    private val recordNmea: Boolean = true,
    private val fullTracking: Boolean = true,
) : MeasurementSource {
    private val lm = context.getSystemService(LocationManager::class.java)
    private var sink: MeasurementSink? = null
    private val executor = Executor { handler.post(it) }

    /** Capability flags discovered at runtime, for the UI and the session notes. */
    @Volatile var measurementsStatus: String = "unknown"
        private set

    private var lastStatusT = 0L
    private var lastSats: List<SatInfo>? = null

    private val statusCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val t = SystemClock.elapsedRealtimeNanos()
            // Some devices/emulators fire this hundreds of times per second; cap the rate at 5 Hz.
            if (t - lastStatusT < 200_000_000L) return
            val sats = List(status.satelliteCount) { i ->
                SatInfo(
                    svid = status.getSvid(i),
                    constellation = status.getConstellationType(i),
                    cn0DbHz = status.getCn0DbHz(i).toDouble(),
                    elevDeg = status.getElevationDegrees(i).toDouble(),
                    azDeg = status.getAzimuthDegrees(i).toDouble(),
                    usedInFix = status.usedInFix(i),
                    hasEphemeris = status.hasEphemerisData(i),
                    hasAlmanac = status.hasAlmanacData(i),
                    carrierHz = if (status.hasCarrierFrequencyHz(i)) status.getCarrierFrequencyHz(i).toDouble() else null,
                    basebandCn0DbHz = if (Build.VERSION.SDK_INT >= 30 && status.hasBasebandCn0DbHz(i)) status.getBasebandCn0DbHz(i).toDouble() else null,
                )
            }
            if (sats == lastSats && t - lastStatusT < 1_000_000_000L) return
            lastStatusT = t
            lastSats = sats
            sink?.emit(GnssStatusSnapshot(t, sats))
        }
    }

    private val measCallback = object : GnssMeasurementsEvent.Callback() {
        override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
            val c = event.clock
            val t = if (c.hasElapsedRealtimeNanos()) c.elapsedRealtimeNanos else SystemClock.elapsedRealtimeNanos()
            val agc = if (Build.VERSION.SDK_INT >= 34) {
                event.gnssAutomaticGainControls.map { AgcInfo(it.constellationType, it.carrierFrequencyHz.toDouble(), it.levelDb) }
            } else emptyList()
            sink?.emit(GnssMeasurementBatch(t, c.toModel(), event.measurements.map { it.toModel() }, agc))
        }

        override fun onStatusChanged(status: Int) {
            measurementsStatus = when (status) {
                STATUS_READY -> "ready"
                STATUS_NOT_SUPPORTED -> "not supported"
                STATUS_LOCATION_DISABLED -> "location disabled"
                STATUS_NOT_ALLOWED -> "not allowed"
                else -> "status $status"
            }
        }
    }

    private val nmeaListener = OnNmeaMessageListener { message, _ ->
        sink?.emit(NmeaSentence(SystemClock.elapsedRealtimeNanos(), message.trim()))
    }

    @SuppressLint("MissingPermission")
    override fun start(sink: MeasurementSink) {
        this.sink = sink
        if (Build.VERSION.SDK_INT >= 30) {
            lm.registerGnssStatusCallback(executor, statusCallback)
            if (recordNmea) lm.addNmeaListener(executor, nmeaListener)
        } else {
            @Suppress("DEPRECATION")
            lm.registerGnssStatusCallback(statusCallback, handler)
            if (recordNmea) lm.addNmeaListener(nmeaListener, handler)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val req = GnssMeasurementRequest.Builder().setFullTracking(fullTracking).build()
            lm.registerGnssMeasurementsCallback(req, executor, measCallback)
        } else {
            @Suppress("DEPRECATION")
            lm.registerGnssMeasurementsCallback(measCallback, handler)
        }
    }

    override fun stop() {
        lm.unregisterGnssStatusCallback(statusCallback)
        lm.unregisterGnssMeasurementsCallback(measCallback)
        lm.removeNmeaListener(nmeaListener)
        sink = null
    }
}

private fun GnssClock.toModel() = GnssClockInfo(
    timeNanos = timeNanos,
    leapSecond = if (hasLeapSecond()) leapSecond else null,
    timeUncNanos = if (hasTimeUncertaintyNanos()) timeUncertaintyNanos else null,
    fullBiasNanos = if (hasFullBiasNanos()) fullBiasNanos else null,
    biasNanos = if (hasBiasNanos()) biasNanos else null,
    biasUncNanos = if (hasBiasUncertaintyNanos()) biasUncertaintyNanos else null,
    driftNsps = if (hasDriftNanosPerSecond()) driftNanosPerSecond else null,
    driftUncNsps = if (hasDriftUncertaintyNanosPerSecond()) driftUncertaintyNanosPerSecond else null,
    hwClockDiscontinuityCount = hardwareClockDiscontinuityCount,
)

@Suppress("DEPRECATION")
private fun GnssMeasurement.toModel() = GnssRawMeas(
    svid = svid,
    constellation = constellationType,
    timeOffsetNanos = timeOffsetNanos,
    state = state,
    receivedSvTimeNanos = receivedSvTimeNanos,
    receivedSvTimeUncNanos = receivedSvTimeUncertaintyNanos,
    cn0DbHz = cn0DbHz,
    pseudorangeRateMps = pseudorangeRateMetersPerSecond,
    pseudorangeRateUncMps = pseudorangeRateUncertaintyMetersPerSecond,
    adrState = accumulatedDeltaRangeState,
    adrM = accumulatedDeltaRangeMeters,
    adrUncM = accumulatedDeltaRangeUncertaintyMeters,
    carrierHz = if (hasCarrierFrequencyHz()) carrierFrequencyHz.toDouble() else null,
    multipath = multipathIndicator,
    snrDb = if (hasSnrInDb()) snrInDb else null,
    agcDb = if (hasAutomaticGainControlLevelDb()) automaticGainControlLevelDb else null,
    basebandCn0DbHz = if (Build.VERSION.SDK_INT >= 30 && hasBasebandCn0DbHz()) basebandCn0DbHz else null,
    codeType = if (hasCodeType()) codeType else null,
)
