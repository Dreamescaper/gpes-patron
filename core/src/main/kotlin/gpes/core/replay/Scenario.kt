package gpes.core.replay

import gpes.core.geo.Geo
import gpes.core.geo.LocalFrame
import gpes.core.model.Cov2
import gpes.core.model.GnssMeasurementBatch
import gpes.core.model.GnssStatusSnapshot
import gpes.core.model.ImuKind
import gpes.core.model.ImuSample
import gpes.core.model.LocSource
import gpes.core.model.LocationMeasurement
import gpes.core.model.Measurement
import gpes.core.model.NmeaSentence
import gpes.core.model.OrientationSample
import gpes.core.model.VehicleSpeedMeasurement
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A replay scenario: an ordered list of perturbations applied to a recorded drive. Times are
 * seconds relative to the first measurement of the drive. A null `durationS` means "until the end".
 */
@Serializable
data class Scenario(
    val name: String,
    val description: String = "",
    val steps: List<ScenarioStep> = emptyList(),
    val seed: Long = 42,
)

@Serializable
sealed interface ScenarioStep {
    val startS: Double
    val durationS: Double?

    fun active(relS: Double): Boolean = relS >= startS && (durationS == null || relS < startS + durationS!!)

    /** Remove a location source. For GNSS this optionally also removes raw GNSS (status/measurements/NMEA), like real jamming. */
    @Serializable @SerialName("drop_source")
    data class DropSource(
        val source: LocSource,
        override val startS: Double = 0.0,
        override val durationS: Double? = null,
        val dropRawGnss: Boolean = true,
    ) : ScenarioStep

    /** Constant offset (a jump), optionally ramped in over [rampS] (gradual capture by a spoofer). */
    @Serializable @SerialName("offset")
    data class Offset(
        override val startS: Double,
        override val durationS: Double? = null,
        val dEastM: Double,
        val dNorthM: Double,
        val rampS: Double = 0.0,
        val sources: Set<LocSource> = setOf(LocSource.GNSS, LocSource.FUSED),
        /** Also rewrite reported speed/bearing so Doppler velocity matches the displaced track (competent spoofer). */
        val consistentVelocity: Boolean = false,
    ) : ScenarioStep

    /** Offset growing linearly with time along [bearingDeg]. */
    @Serializable @SerialName("drift")
    data class Drift(
        override val startS: Double,
        override val durationS: Double? = null,
        val rateMps: Double,
        val bearingDeg: Double,
        val sources: Set<LocSource> = setOf(LocSource.GNSS, LocSource.FUSED),
        /** Also rewrite reported speed/bearing so Doppler velocity matches the drifting track (competent spoofer). */
        val consistentVelocity: Boolean = false,
    ) : ScenarioStep

    /**
     * Translate the track so it starts at ([lat], [lon]) and keeps its real motion shape. That is a
     * consistent spoof in another place. With [frozen], the position stays fixed there.
     */
    @Serializable @SerialName("teleport")
    data class Teleport(
        override val startS: Double,
        override val durationS: Double? = null,
        val lat: Double,
        val lon: Double,
        val frozen: Boolean = false,
        val sources: Set<LocSource> = setOf(LocSource.GNSS, LocSource.FUSED),
    ) : ScenarioStep

    /** Add position noise. Unless [reportHonestly], the reported accuracy is left unchanged, so it looks overconfident. */
    @Serializable @SerialName("inflate_noise")
    data class InflateNoise(
        val source: LocSource = LocSource.GNSS,
        override val startS: Double = 0.0,
        override val durationS: Double? = null,
        val sigmaM: Double,
        val reportHonestly: Boolean = false,
    ) : ScenarioStep

    @Serializable @SerialName("drop_sensor")
    data class DropSensor(
        val kind: ImuKind? = null,
        val orientation: Boolean = false,
        override val startS: Double = 0.0,
        override val durationS: Double? = null,
    ) : ScenarioStep

    /** Emulate a coarse Cell/Wi-Fi location from truth, to measure its value before real data exists. */
    @Serializable @SerialName("synthetic_network")
    data class SyntheticNetwork(
        val sigmaM: Double = 500.0,
        val periodS: Double = 20.0,
        override val startS: Double = 0.0,
        override val durationS: Double? = null,
    ) : ScenarioStep

    /** Emulate OBD vehicle speed from truth, to measure the value of OBD before the hardware exists. */
    @Serializable @SerialName("synthetic_vehicle_speed")
    data class SyntheticVehicleSpeed(
        val sigmaMps: Double = 0.3,
        val periodS: Double = 0.2,
        /** Multiplicative scale error, e.g. 0.02 for a speedometer that reads 2% high. */
        val scaleError: Double = 0.0,
        override val startS: Double = 0.0,
        override val durationS: Double? = null,
        /** Mimic an ELM327: integer km/h and a reporting delay (s) not reflected in the timestamp. */
        val quantizeKmh: Boolean = false,
        val latencyS: Double = 0.0,
    ) : ScenarioStep
}

/** Applies a [Scenario] to a measurement stream. Stateful (teleport anchors, RNG); use one per run. */
class ScenarioApplier(private val scenario: Scenario, private val t0Ns: Long) {
    private val rnd = Random(scenario.seed)
    private val teleportAnchor = HashMap<Int, Pair<Double, Double>>()

    private fun rel(tNs: Long) = (tNs - t0Ns) / 1e9

    /** Returns the transformed measurement, or null if it is dropped. */
    fun apply(m: Measurement): Measurement? {
        var cur: Measurement = m
        val relS = rel(m.tNs)
        scenario.steps.forEachIndexed { idx, step ->
            if (!step.active(relS)) return@forEachIndexed
            cur = when (step) {
                is ScenarioStep.DropSource -> when {
                    cur is LocationMeasurement && (cur as LocationMeasurement).source == step.source -> return null
                    step.source == LocSource.GNSS && step.dropRawGnss &&
                        (cur is GnssStatusSnapshot || cur is GnssMeasurementBatch || cur is NmeaSentence) -> return null
                    else -> cur
                }
                is ScenarioStep.Offset -> shift(cur, step.sources) { lm ->
                    val ramping = step.rampS > 0 && relS - step.startS < step.rampS
                    val f = if (step.rampS > 0) ((relS - step.startS) / step.rampS).coerceIn(0.0, 1.0) else 1.0
                    val o = offsetBy(lm, step.dEastM * f, step.dNorthM * f)
                    if (step.consistentVelocity && ramping) addVelocity(o, step.dEastM / step.rampS, step.dNorthM / step.rampS) else o
                }
                is ScenarioStep.Drift -> shift(cur, step.sources) { lm ->
                    val d = step.rateMps * (relS - step.startS)
                    val b = Math.toRadians(step.bearingDeg)
                    val o = offsetBy(lm, d * kotlin.math.sin(b), d * cos(b))
                    if (step.consistentVelocity) addVelocity(o, step.rateMps * kotlin.math.sin(b), step.rateMps * cos(b)) else o
                }
                is ScenarioStep.Teleport -> shift(cur, step.sources) { lm ->
                    val anchor = teleportAnchor.getOrPut(idx) { lm.lat to lm.lon }
                    if (step.frozen) lm.copy(lat = step.lat, lon = step.lon, speedMps = 0.0)
                    else lm.copy(lat = step.lat + (lm.lat - anchor.first), lon = Geo.wrapDeg(step.lon + (lm.lon - anchor.second)))
                }
                is ScenarioStep.InflateNoise -> {
                    val lm = cur as? LocationMeasurement
                    if (lm == null || lm.source != step.source) cur else {
                        val o = offsetBy(lm, gauss() * step.sigmaM, gauss() * step.sigmaM)
                        if (step.reportHonestly) {
                            val acc = lm.hAccM ?: 0.0
                            o.copy(hAccM = sqrt(acc * acc + (step.sigmaM * Cov2.R68_PER_SIGMA).let { it * it }))
                        } else o
                    }
                }
                is ScenarioStep.DropSensor -> when {
                    cur is ImuSample && (step.kind == null || (cur as ImuSample).kind == step.kind) -> return null
                    cur is OrientationSample && step.orientation -> return null
                    else -> cur
                }
                is ScenarioStep.SyntheticNetwork, is ScenarioStep.SyntheticVehicleSpeed -> cur
            }
        }
        return cur
    }

    /** Extra measurements generated from truth (synthetic network, synthetic vehicle speed). */
    fun generate(truth: TruthTrack, endNs: Long): List<Measurement> {
        val out = ArrayList<Measurement>()
        for (step in scenario.steps) {
            val period = when (step) {
                is ScenarioStep.SyntheticNetwork -> step.periodS
                is ScenarioStep.SyntheticVehicleSpeed -> step.periodS
                else -> continue
            }
            var t = t0Ns + (step.startS * 1e9).toLong()
            val end = step.durationS?.let { minOf(endNs, t + (it * 1e9).toLong()) } ?: endNs
            while (t < end) {
                val tr = truth.at(t)
                if (tr != null) when (step) {
                    is ScenarioStep.SyntheticNetwork -> {
                        val fr = LocalFrame(tr.lat, tr.lon)
                        val ll = fr.toLatLon(gauss() * step.sigmaM, gauss() * step.sigmaM)
                        out += LocationMeasurement(
                            tNs = t, receivedNs = t, source = LocSource.NETWORK, provider = "network",
                            lat = ll.lat, lon = ll.lon, hAccM = step.sigmaM * Cov2.R68_PER_SIGMA,
                            extras = mapOf("scenario.synthetic" to "network"),
                        )
                    }
                    is ScenarioStep.SyntheticVehicleSpeed -> {
                        // Value measured `latencyS` earlier than its timestamp says (stale reading).
                        val src = if (step.latencyS > 0) truth.at(t - (step.latencyS * 1e9).toLong()) ?: tr else tr
                        var v = (src.speedMps * (1 + step.scaleError) + gauss() * step.sigmaMps).coerceAtLeast(0.0)
                        if (step.quantizeKmh) v = Math.round(v * 3.6) / 3.6
                        out += VehicleSpeedMeasurement(t, v, step.sigmaMps, "synthetic")
                    }
                    else -> Unit
                }
                t += (period * 1e9).toLong()
            }
        }
        return out
    }

    /** Windows in which GNSS is absent or manipulated, as absolute [start, end) nanoseconds, for metrics. */
    fun degradedWindows(endNs: Long): List<DegradedWindow> = scenario.steps.mapNotNull { s ->
        val kind = when (s) {
            is ScenarioStep.DropSource -> if (s.source == LocSource.GNSS) "gnss_outage" else return@mapNotNull null
            is ScenarioStep.Offset -> "gnss_offset"
            is ScenarioStep.Drift -> "gnss_drift"
            is ScenarioStep.Teleport -> "gnss_teleport"
            is ScenarioStep.InflateNoise -> "gnss_noise"
            else -> return@mapNotNull null
        }
        val start = t0Ns + (s.startS * 1e9).toLong()
        val end = s.durationS?.let { start + (it * 1e9).toLong() } ?: endNs
        DegradedWindow(kind, start, minOf(end, endNs))
    }

    private inline fun shift(m: Measurement, sources: Set<LocSource>, f: (LocationMeasurement) -> LocationMeasurement): Measurement =
        if (m is LocationMeasurement && m.source in sources) f(m) else m

    private fun offsetBy(lm: LocationMeasurement, de: Double, dn: Double): LocationMeasurement {
        val ll = LocalFrame(lm.lat, lm.lon).toLatLon(de, dn)
        return lm.copy(lat = ll.lat, lon = ll.lon)
    }

    /** Add a velocity vector (m/s, east/north) to the reported speed and bearing. */
    private fun addVelocity(lm: LocationMeasurement, ve: Double, vn: Double): LocationMeasurement {
        val speed = lm.speedMps ?: return lm
        val b = Math.toRadians(lm.bearingDeg ?: 0.0)
        val e = speed * kotlin.math.sin(b) + ve
        val n = speed * cos(b) + vn
        val newSpeed = kotlin.math.hypot(e, n)
        val newBearing = (Math.toDegrees(kotlin.math.atan2(e, n)) + 360) % 360
        return lm.copy(speedMps = newSpeed, bearingDeg = if (newSpeed > 0.5) newBearing else lm.bearingDeg)
    }

    private fun gauss(): Double {
        val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
        return sqrt(-2 * ln(u1)) * cos(2 * PI * rnd.nextDouble())
    }
}

data class DegradedWindow(val kind: String, val startNs: Long, val endNs: Long)
