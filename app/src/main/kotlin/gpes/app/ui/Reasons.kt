package gpes.app.ui

import androidx.annotation.StringRes
import gpes.app.R
import gpes.core.estimator.CompassVerdict
import gpes.core.model.EstimatorMode
import gpes.core.model.TrustReason
import gpes.core.model.TrustState

/** Plain-language explanation of a trust verdict; the raw codes stay in the recordings and the Diagnostics tab. */
@StringRes
fun reasonText(r: TrustReason): Int = when (r) {
    TrustReason.SYNTHETIC_INPUT -> R.string.reason_synthetic_input
    TrustReason.SOURCE_OVERRIDDEN -> R.string.reason_source_overridden
    TrustReason.ECHO_OF_OUR_OUTPUT -> R.string.reason_echo_of_our_output
    TrustReason.STALE -> R.string.reason_stale
    TrustReason.POOR_ACCURACY -> R.string.reason_poor_accuracy
    TrustReason.NO_ACCURACY -> R.string.reason_no_accuracy
    TrustReason.IMPOSSIBLE_VELOCITY -> R.string.reason_impossible_velocity
    TrustReason.IMPOSSIBLE_ACCELERATION -> R.string.reason_impossible_acceleration
    TrustReason.INNOVATION_GATE -> R.string.reason_innovation_gate
    TrustReason.COURSE_GYRO_MISMATCH -> R.string.reason_course_gyro_mismatch
    TrustReason.VELOCITY_POSITION_MISMATCH -> R.string.reason_velocity_position_mismatch
    TrustReason.SPEED_OBD_MISMATCH -> R.string.reason_speed_obd_mismatch
    TrustReason.MOVING_WHILE_STATIONARY -> R.string.reason_moving_while_stationary
    TrustReason.NETWORK_DISAGREEMENT -> R.string.reason_network_disagreement
    TrustReason.GEOGRAPHICALLY_IMPOSSIBLE -> R.string.reason_geographically_impossible
    TrustReason.CN0_UNIFORM -> R.string.reason_cn0_uniform
    TrustReason.FEW_SATELLITES -> R.string.reason_few_satellites
    TrustReason.RECOVERING -> R.string.reason_recovering
    TrustReason.RESET_AFTER_CONSISTENT_STREAM -> R.string.reason_reset_after_consistent_stream
    TrustReason.AGREES_WITH_ESTIMATE -> R.string.reason_agrees_with_estimate
    TrustReason.COARSE_ODOMETRY_MISMATCH -> R.string.reason_coarse_odometry_mismatch
    TrustReason.COARSE_ODOMETRY_DISPUTED -> R.string.reason_coarse_odometry_disputed
}

@StringRes
fun trustLabel(s: TrustState): Int = when (s) {
    TrustState.TRUSTED -> R.string.trust_trusted
    TrustState.QUESTIONABLE -> R.string.trust_questionable
    TrustState.REJECTED -> R.string.trust_rejected
    TrustState.UNAVAILABLE -> R.string.trust_unavailable
}

@StringRes
fun modeLabel(m: EstimatorMode): Int = when (m) {
    EstimatorMode.UNINITIALIZED -> R.string.est_uninitialized
    EstimatorMode.COARSE_ONLY -> R.string.est_coarse_only
    EstimatorMode.GNSS_TRACKING -> R.string.est_gnss_tracking
    EstimatorMode.DEAD_RECKONING -> R.string.est_dead_reckoning
    EstimatorMode.STATIONARY -> R.string.est_stationary
}

@StringRes
fun compassVerdictLabel(v: CompassVerdict): Int = when (v) {
    CompassVerdict.UNKNOWN -> R.string.compass_verdict_unknown
    CompassVerdict.USABLE -> R.string.compass_verdict_usable
    CompassVerdict.MARGINAL -> R.string.compass_verdict_marginal
    CompassVerdict.UNUSABLE -> R.string.compass_verdict_unusable
}
