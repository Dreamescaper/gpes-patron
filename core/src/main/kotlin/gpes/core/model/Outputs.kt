package gpes.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// Trust
// ---------------------------------------------------------------------------------------------

@Serializable
enum class TrustState { TRUSTED, QUESTIONABLE, REJECTED, UNAVAILABLE }

@Serializable
enum class TrustReason {
    SYNTHETIC_INPUT,
    SOURCE_OVERRIDDEN,
    /** A Google Fused fix while (or shortly after) we replace a platform provider: Fused mixes our mock in (D-086). */
    ECHO_OF_OUR_OUTPUT,
    STALE,
    POOR_ACCURACY,
    NO_ACCURACY,
    IMPOSSIBLE_VELOCITY,
    IMPOSSIBLE_ACCELERATION,
    INNOVATION_GATE,
    COURSE_GYRO_MISMATCH,
    VELOCITY_POSITION_MISMATCH,
    SPEED_OBD_MISMATCH,
    MOVING_WHILE_STATIONARY,
    NETWORK_DISAGREEMENT,
    GEOGRAPHICALLY_IMPOSSIBLE,
    CN0_UNIFORM,
    FEW_SATELLITES,
    RECOVERING,
    RESET_AFTER_CONSISTENT_STREAM,
    /** The fix is close to the estimator's own position, so softer checks were overridden (D-069). */
    AGREES_WITH_ESTIMATE,
    /** Coarse fix displacement disagrees with the distance driven (OBD + gyro chord). */
    COARSE_ODOMETRY_MISMATCH,
    /** Coarse fix disagrees with the distance driven, but only fixes that odometry never confirmed say so (D-085). */
    COARSE_ODOMETRY_DISPUTED,
}

/** The trust verdict for one location measurement. Recorded for every fix, including rejected ones. */
@Serializable
@SerialName("trust")
data class TrustAssessment(
    override val tNs: Long,
    val source: LocSource,
    val provider: String,
    val state: TrustState,
    /** 0..1. A soft score; [state] is what the estimator acts on. */
    val confidence: Double,
    val reasons: Set<TrustReason>,
    /** Normalized innovation squared against the estimator prediction, when available. */
    val innovationNis: Double? = null,
    /** Implied speed from the last trusted fix (m/s), when available. */
    val impliedSpeedMps: Double? = null,
) : DriveRecord

// ---------------------------------------------------------------------------------------------
// Estimate
// ---------------------------------------------------------------------------------------------

/** 2×2 horizontal covariance in local East/North metres². */
@Serializable
data class Cov2(val ee: Double, val en: Double, val nn: Double) {
    /** Mean per-axis standard deviation. */
    val sigma: Double get() = sqrt(((ee + nn) / 2).coerceAtLeast(0.0))

    /** Radius of 68% confidence, the semantics of Android `Location.getAccuracy()`. */
    val r68: Double get() = sigma * R68_PER_SIGMA

    val r95: Double get() = sigma * R95_PER_SIGMA

    companion object {
        /** For an isotropic 2-D Gaussian, P(r < k·σ) = 1 − exp(−k²/2). */
        const val R68_PER_SIGMA = 1.5096
        const val R95_PER_SIGMA = 2.4477

        fun isotropic(sigma: Double) = Cov2(sigma * sigma, 0.0, sigma * sigma)
        fun fromR68(r68: Double) = isotropic(r68 / R68_PER_SIGMA)
    }
}

@Serializable
enum class EstimatorMode { UNINITIALIZED, COARSE_ONLY, GNSS_TRACKING, DEAD_RECKONING, STATIONARY }

/** Position expressed on the road network (road matcher, Phase 2). Null when no road data. */
@Serializable
data class RoadState(
    val segmentId: Long,
    val distanceAlongM: Double,
    val directionForward: Boolean,
    /** Probability of this road state among all hypotheses, including off-road. */
    val probability: Double = 1.0,
    /** Probability of being off the mapped roads (car park, unmapped road). */
    val pOffRoad: Double = 0.0,
    /** Distance driven continuously on this road with high confidence (m). */
    val confidentM: Double = 0.0,
    val roadName: String = "",
)

/** One candidate position. Phase 1 estimators produce exactly one. */
@Serializable
data class Hypothesis(
    val weight: Double,
    val lat: Double,
    val lon: Double,
    val cov: Cov2,
    val road: RoadState? = null,
)

@Serializable
@SerialName("estimate")
data class PositionEstimate(
    override val tNs: Long,
    val estimator: String,
    val lat: Double,
    val lon: Double,
    val cov: Cov2,
    /** Course over ground, radians clockwise from true north. Null when unknown. */
    val headingRad: Double? = null,
    val headingStdRad: Double? = null,
    val speedMps: Double? = null,
    val speedStdMps: Double? = null,
    val mode: EstimatorMode,
    /** 0..1 */
    val confidence: Double,
    val hypotheses: List<Hypothesis> = emptyList(),
    val road: RoadState? = null,
) : DriveRecord {
    val accuracyM: Double get() = cov.r68
}
