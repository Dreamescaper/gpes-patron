package gpes.core.replay

import gpes.core.estimator.BaselineConfig
import gpes.core.estimator.BaselineDrEstimator
import gpes.core.estimator.EstimatorFactory
import gpes.core.estimator.GnssPassthroughEstimator
import gpes.core.geo.Geo
import gpes.core.model.LocSource
import gpes.core.model.LocationMeasurement
import gpes.core.model.Measurement
import gpes.core.model.PositionEstimate
import gpes.core.model.RecordOrder
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustState
import gpes.core.pipeline.MeasurementPipeline
import gpes.core.pipeline.PipelineConfig
import gpes.core.pipeline.PipelineListener
import gpes.core.sim.TruthSample
import gpes.core.trust.DefaultTrustEvaluator
import gpes.core.trust.TrustConfig
import kotlinx.serialization.Serializable

/** Ground-truth track with interpolation. */
class TruthTrack(samples: List<TruthSample>, private val maxGapNs: Long = 3_000_000_000) {
    private val s = samples.sortedBy { it.tNs }
    private val ts = LongArray(s.size) { s[it].tNs }

    val isEmpty get() = s.isEmpty()
    val size get() = s.size

    fun at(tNs: Long): TruthSample? {
        if (s.isEmpty()) return null
        var i = ts.binarySearch(tNs)
        if (i >= 0) return s[i]
        i = -i - 1
        if (i == 0 || i == s.size) return null
        val a = s[i - 1]
        val b = s[i]
        if (b.tNs - a.tNs > maxGapNs) return null
        val f = (tNs - a.tNs).toDouble() / (b.tNs - a.tNs)
        val db = Geo.wrapDeg(b.bearingDeg - a.bearingDeg)
        return TruthSample(
            tNs, a.lat + f * (b.lat - a.lat), a.lon + f * Geo.wrapDeg(b.lon - a.lon),
            (a.bearingDeg + f * db + 360) % 360, a.speedMps + f * (b.speedMps - a.speedMps),
        )
    }
}

/** A named estimator configuration plus extra scenario steps. This is the unit of the ablation study. */
@Serializable
data class Variant(
    val name: String,
    val estimator: String = "baseline",
    val extraSteps: List<ScenarioStep> = emptyList(),
    val baseline: BaselineConfig = BaselineConfig(),
    val trust: TrustConfig = TrustConfig(),
) {
    fun factory(): EstimatorFactory = when (estimator) {
        "baseline" -> EstimatorFactory { BaselineDrEstimator(baseline) }
        "passthrough" -> EstimatorFactory { GnssPassthroughEstimator() }
        else -> error("unknown estimator '$estimator'")
    }

    companion object {
        /** The standard ablation ladder. OSM and route variants join here in later phases. */
        fun standard(): List<Variant> = listOf(
            Variant("hold-last-fix", estimator = "passthrough"),
            Variant(
                "phone-only",
                extraSteps = listOf(ScenarioStep.DropSource(LocSource.NETWORK, dropRawGnss = false), ScenarioStep.DropSource(LocSource.FUSED, dropRawGnss = false)),
            ),
            Variant("phone+network", extraSteps = listOf(ScenarioStep.DropSource(LocSource.FUSED, dropRawGnss = false))),
            Variant(
                "phone+synthNetwork",
                extraSteps = listOf(
                    ScenarioStep.DropSource(LocSource.NETWORK, dropRawGnss = false), ScenarioStep.DropSource(LocSource.FUSED, dropRawGnss = false),
                    ScenarioStep.SyntheticNetwork(sigmaM = 500.0, periodS = 20.0),
                ),
            ),
            Variant(
                "phone+synthNetwork+synthObd",
                extraSteps = listOf(
                    ScenarioStep.DropSource(LocSource.NETWORK, dropRawGnss = false), ScenarioStep.DropSource(LocSource.FUSED, dropRawGnss = false),
                    ScenarioStep.SyntheticNetwork(sigmaM = 500.0, periodS = 20.0),
                    ScenarioStep.SyntheticVehicleSpeed(sigmaMps = 0.3, scaleError = 0.01),
                ),
            ),
        )
    }
}

data class GnssLabel(val tNs: Long, val provider: String, val offsetM: Double)

data class ReplayResult(
    val scenario: Scenario,
    val variant: Variant,
    val t0Ns: Long,
    val endNs: Long,
    val estimates: List<PositionEstimate>,
    val trust: List<TrustAssessment>,
    val labels: List<GnssLabel>,
    val windows: List<DegradedWindow>,
    val truth: TruthTrack,
    val stats: MeasurementPipeline.Stats,
)

/**
 * Replays a drive offline through the same pipeline as the app:
 *  1. Clean pass: truth = GNSS fixes the trust evaluator accepts with hAcc ≤ [truthMaxAccM] (unless
 *     truth is supplied, e.g. from the simulator).
 *  2. Apply the scenario plus variant steps, and add synthetic measurements generated from truth.
 *  3. Run the pipeline with the variant's estimator; collect ticks, trust and labels.
 */
class ReplayRunner(
    private val pipelineConfig: PipelineConfig = PipelineConfig(reorderWindowNs = 0),
    private val truthMaxAccM: Double = 10.0,
) {
    fun truthFrom(measurements: List<Measurement>, trustCfg: TrustConfig = TrustConfig()): TruthTrack {
        val samples = ArrayList<TruthSample>()
        val byT = HashMap<Long, LocationMeasurement>()
        val p = MeasurementPipeline(pipelineConfig, DefaultTrustEvaluator(trustCfg), GnssPassthroughEstimator())
        p.listener = object : PipelineListener {
            override fun onTrust(a: TrustAssessment) {
                val m = byT[a.tNs] ?: return
                if (a.source == LocSource.GNSS && a.state == TrustState.TRUSTED && (m.hAccM ?: 1e9) <= truthMaxAccM) {
                    samples += TruthSample(m.tNs, m.lat, m.lon, m.bearingDeg ?: Double.NaN, m.speedMps ?: 0.0)
                }
            }
        }
        for (m in measurements) {
            if (m is LocationMeasurement && m.source == LocSource.GNSS) byT[m.tNs] = m
            p.emit(m)
        }
        p.flush()
        return TruthTrack(samples)
    }

    fun run(
        measurements: List<Measurement>,
        scenario: Scenario,
        variant: Variant,
        truthOverride: TruthTrack? = null,
    ): ReplayResult {
        require(measurements.isNotEmpty()) { "empty drive" }
        val t0 = measurements.first().tNs
        val end = measurements.last().tNs
        val truth = truthOverride ?: truthFrom(measurements, variant.trust)

        val combined = scenario.copy(steps = scenario.steps + variant.extraSteps)
        val applier = ScenarioApplier(combined, t0)
        val labels = ArrayList<GnssLabel>()
        val stream = ArrayList<Measurement>(measurements.size)
        for (m in measurements) {
            val out = applier.apply(m) ?: continue
            if (m is LocationMeasurement && out is LocationMeasurement && (m.source == LocSource.GNSS || m.source == LocSource.FUSED)) {
                labels += GnssLabel(m.tNs, m.provider, Geo.haversineM(m.lat, m.lon, out.lat, out.lon))
            }
            stream += out
        }
        stream += applier.generate(truth, end)
        stream.sortWith(RecordOrder.comparator)

        val estimates = ArrayList<PositionEstimate>()
        val trust = ArrayList<TrustAssessment>()
        val pipeline = MeasurementPipeline(pipelineConfig, DefaultTrustEvaluator(variant.trust), variant.factory().create())
        pipeline.listener = object : PipelineListener {
            override fun onTrust(a: TrustAssessment) { trust += a }
            override fun onEstimate(e: PositionEstimate) { estimates += e }
        }
        for (m in stream) pipeline.emit(m)
        pipeline.flush()
        return ReplayResult(combined, variant, t0, end, estimates, trust, labels, applier.degradedWindows(end), truth, pipeline.stats)
    }
}
