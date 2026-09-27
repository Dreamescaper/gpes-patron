package gpes.core.estimator

import gpes.core.model.Cov2
import gpes.core.model.EstimatorMode
import gpes.core.model.Hypothesis
import gpes.core.model.LocSource
import gpes.core.model.LocationMeasurement
import gpes.core.model.Measurement
import gpes.core.model.PositionEstimate
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustState
import gpes.core.motion.MotionUpdate
import kotlin.math.max

/**
 * Reference baseline for replay comparisons: "hold the last trusted GNSS fix". Uncertainty grows
 * with a worst-case speed while GNSS is absent. Any real estimator must beat this.
 */
class GnssPassthroughEstimator(private val growthMps: Double = 20.0) : PositionEstimator {
    override val name = "passthrough"
    private var last: LocationMeasurement? = null

    override fun onMeasurement(m: Measurement, trust: TrustAssessment?) {
        if (m is LocationMeasurement && m.source == LocSource.GNSS && trust?.state == TrustState.TRUSTED) last = m
    }

    override fun onMotion(u: MotionUpdate) = Unit

    override fun estimate(tNs: Long): PositionEstimate? {
        val l = last ?: return null
        val ageS = max(0.0, (tNs - l.tNs) / 1e9)
        val r68 = (l.hAccM ?: 10.0) + growthMps * ageS
        val cov = Cov2.fromR68(r68)
        return PositionEstimate(
            tNs = tNs, estimator = name, lat = l.lat, lon = l.lon, cov = cov,
            headingRad = l.bearingDeg?.let { Math.toRadians(it) }, speedMps = l.speedMps,
            mode = if (ageS <= 3) EstimatorMode.GNSS_TRACKING else EstimatorMode.COARSE_ONLY,
            confidence = 1.0 / (1.0 + r68 / 50.0),
            hypotheses = listOf(Hypothesis(1.0, l.lat, l.lon, cov)),
        )
    }

    override fun snapshot(): Any = listOfNotNull(last)
    override fun restore(snapshot: Any) { last = (snapshot as List<*>).firstOrNull() as LocationMeasurement? }
}
