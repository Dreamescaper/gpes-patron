package gpes.core.replay

import gpes.core.estimator.Compass
import gpes.core.estimator.CompassConfig
import gpes.core.estimator.CompassQuality
import gpes.core.geo.Geo
import gpes.core.model.GeomagneticReference
import gpes.core.model.ImuKind
import gpes.core.model.ImuSample
import gpes.core.model.Measurement
import gpes.core.model.PowerState
import gpes.core.model.SensorInfo
import gpes.core.motion.MotionTracker
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

@Serializable
data class HeadingStats(val n: Int, val p50Deg: Double?, val p95Deg: Double?, val within2SigmaFraction: Double?)

@Serializable
data class CompassReportSummary(
    val quality: CompassQuality,
    /** Mount epochs seen (re-mounts + 1). The quality refers to the last epoch. */
    val mountEpochs: Int,
    val movingSeconds: Double,
    /** Fraction of moving seconds (with GNSS truth) that had a compass reading. */
    val availability: Double?,
    /** Cross-validated: aligned on trusted GNSS in the first half, evaluated on the second half. By compass mode. */
    val heldOutByMode: Map<String, HeadingStats>,
    val heldOutAll: HeadingStats,
    val rawNormMinUt: Double?,
    val rawNormMedianUt: Double?,
    val rawNormMaxUt: Double?,
    val rawMaxAbsComponentUt: Double?,
    /** From the recorded sensor_info (android maximumRange), when present. */
    val sensorMaxRangeUt: Double?,
    val nearFullScale: Boolean,
    val wirelessChargingSeconds: Double,
)

@Serializable
data class CompassTimelineRow(
    val tS: Double,
    val verdict: String,
    val mode: String?,
    val bearingDeg: Double?,
    val sigmaDeg: Double?,
    val truthCourseDeg: Double?,
    val errDeg: Double?,
    val tiltRateRms: Double,
    val horizontalAccel: Double,
    val rawNormUt: Double?,
    val plug: String?,
)

/**
 * Offline magnetometer assessment of one drive: is the magnetometer on this mount useful, can the
 * holder be calibrated out, and how accurate is the resulting heading against trusted GNSS course?
 */
object CompassReport {
    fun analyze(
        measurements: List<Measurement>,
        truth: TruthTrack,
        sensorInfo: List<SensorInfo> = emptyList(),
        cfg: CompassConfig = CompassConfig(),
    ): Pair<CompassReportSummary, List<CompassTimelineRow>> {
        require(measurements.isNotEmpty())
        val t0 = measurements.first().tNs
        val half = t0 + (measurements.last().tNs - t0) / 2
        val tracker = MotionTracker()
        val compass = Compass(cfg)
        var biasSum = 0.0; var biasN = 0
        var nextRow = t0
        var lastRaw: ImuSample? = null
        var plug: String? = null
        var wirelessS = 0.0
        var lastPowerT = Long.MIN_VALUE
        var moving = 0.0
        var truthMoving = 0
        var withReading = 0
        val norms = ArrayList<Double>()
        var maxComp = 0.0
        val rows = ArrayList<CompassTimelineRow>()
        val errs = HashMap<String, MutableList<Pair<Double, Double>>>()
        var epochs = 0

        for (m in measurements) {
            when (m) {
                is ImuSample -> {
                    compass.onMag(m)
                    if (m.kind == ImuKind.MAG_UNCAL || (m.kind == ImuKind.MAG && lastRaw?.kind != ImuKind.MAG_UNCAL)) {
                        lastRaw = m
                        val n = kotlin.math.sqrt(m.x * m.x + m.y * m.y + m.z * m.z)
                        if (norms.size < 2_000_000) norms += n
                        maxComp = max(maxComp, max(abs(m.x), max(abs(m.y), abs(m.z))))
                    }
                }
                is GeomagneticReference -> compass.onReference(m)
                is PowerState -> {
                    compass.onPower(m)
                    if (plug == "WIRELESS" && lastPowerT != Long.MIN_VALUE) wirelessS += (m.tNs - lastPowerT) / 1e9
                    plug = m.plug; lastPowerT = m.tNs
                }
                else -> Unit
            }
            val u = tracker.onMeasurement(m) ?: continue
            epochs = max(epochs, u.mountEpoch + 1)
            if (u.stationary) { biasSum += u.yawRateUp; biasN++ } else moving += u.dtS
            compass.onMotion(u, if (biasN > 0) biasSum / biasN else 0.0)
            val tr = truth.at(u.tNs)
            val course = tr?.takeIf { !it.bearingDeg.isNaN() && it.speedMps > 3 }?.bearingDeg
            if (u.tNs < half && tr != null && course != null && tr.speedMps > cfg.alignMinSpeedMps) {
                compass.addCalibration(u.tNs, Math.toRadians(course), tr.speedMps, u.yawRateUp)
            }
            if (u.tNs < nextRow) continue
            nextRow = u.tNs + 1_000_000_000
            val r = compass.heading(u.tNs)
            val q = compass.quality()
            val err = if (r != null && course != null) abs(Geo.wrapDeg(Math.toDegrees(r.bearingRad) - course)) else null
            if (course != null && !u.stationary && u.tNs >= half) {
                truthMoving++
                if (r != null) withReading++
            }
            if (err != null && u.tNs >= half) errs.getOrPut(r!!.mode.name) { ArrayList() } += err to Math.toDegrees(r.sigmaRad)
            val raw = lastRaw
            rows += CompassTimelineRow(
                (u.tNs - t0) / 1e9, q.verdict.name, r?.mode?.name, r?.let { Math.toDegrees(it.bearingRad) }, r?.let { Math.toDegrees(it.sigmaRad) },
                course, err, u.tiltRateRms, u.horizontalAccel, raw?.let { kotlin.math.sqrt(it.x * it.x + it.y * it.y + it.z * it.z) }, plug,
            )
        }

        fun stats(l: List<Pair<Double, Double>>) = HeadingStats(
            l.size, Metrics.pct(l.map { it.first }, 0.5), Metrics.pct(l.map { it.first }, 0.95),
            if (l.isEmpty()) null else l.count { it.first <= 2 * it.second }.toDouble() / l.size,
        )
        val range = sensorInfo.filter { it.type == 2 || it.type == 14 }.maxOfOrNull { it.maxRange }
        val summary = CompassReportSummary(
            quality = compass.quality(),
            mountEpochs = epochs,
            movingSeconds = moving,
            availability = if (truthMoving > 0) withReading.toDouble() / truthMoving else null,
            heldOutByMode = errs.mapValues { stats(it.value) },
            heldOutAll = stats(errs.values.flatten()),
            rawNormMinUt = norms.minOrNull(),
            rawNormMedianUt = Metrics.pct(norms, 0.5),
            rawNormMaxUt = norms.maxOrNull(),
            rawMaxAbsComponentUt = if (norms.isEmpty()) null else maxComp,
            sensorMaxRangeUt = range,
            nearFullScale = range != null && range > 0 && maxComp >= 0.98 * range,
            wirelessChargingSeconds = wirelessS,
        )
        return summary to rows
    }
}
