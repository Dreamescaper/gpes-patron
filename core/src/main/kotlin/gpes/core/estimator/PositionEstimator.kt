package gpes.core.estimator

import gpes.core.model.Measurement
import gpes.core.model.PositionEstimate
import gpes.core.model.TrustAssessment
import gpes.core.motion.MotionUpdate

/**
 * An estimator that is deterministic and driven by measurement time. It must never read the wall
 * clock, so that replaying a recording gives identical results.
 *
 * Contract:
 *  - Measurements arrive in (approximately) non-decreasing `tNs` order.
 *  - Location measurements come with their [TrustAssessment]. Estimators decide how to use each
 *    trust state, but must never fuse `REJECTED` / `UNAVAILABLE` fixes as position evidence.
 *  - [estimate] is a *prediction* to `tNs`: it must not mutate state.
 */
interface PositionEstimator {
    val name: String

    fun onMeasurement(m: Measurement, trust: TrustAssessment?)

    fun onMotion(u: MotionUpdate)

    /** Current belief predicted to [tNs], or null if nothing is known yet. */
    fun estimate(tNs: Long): PositionEstimate?

    fun snapshot(): Any

    fun restore(snapshot: Any)
}

fun interface EstimatorFactory {
    fun create(): PositionEstimator
}
