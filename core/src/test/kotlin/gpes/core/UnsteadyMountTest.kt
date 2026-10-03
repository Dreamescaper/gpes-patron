package gpes.core

import gpes.core.TestSupport.fix
import gpes.core.estimator.AccelSpeedConfig
import gpes.core.estimator.BaselineConfig
import gpes.core.estimator.BaselineDrEstimator
import gpes.core.estimator.CompassConfig
import gpes.core.model.LocSource
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustState
import gpes.core.motion.MotionUpdate
import gpes.core.motion.Vec3
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * D-073. Drive 20261003-140822 (phone in hand, no OBD), 1252–1440 s: after a GPS probe window taught the accelerometer
 * bias (−1.4 m/s²), the hand moved the phone and the apparent bias wandered to +1.0 m/s² within 3 min. The model's bias
 * random walk (0.05 m/s²/√s) kept σ_b ≈ 0.3, so the accelerometer kept driving the speed: 67 m/s at a true ~17. In
 * miniature: a car at 17 m/s due north, 20 s of GNSS, then network fixes (hAcc 60 m) every 20 s on the true track while
 * the longitudinal bias walks −1.5 → +1.0 m/s² over 180 s, with the hand-held tilt rate (0.13 rad/s RMS).
 */
class UnsteadyMountTest {
    private val lat0 = 50.40
    private val lon0 = 30.50
    private val v = 17.0
    private fun north(m: Double) = lat0 + m / 111_195.0
    private fun trusted(tS: Double, source: LocSource) =
        TrustAssessment((tS * 1e9).toLong(), source, "x", TrustState.TRUSTED, 1.0, emptySet())

    /** Max speed error after GNSS is gone (m/s). */
    private fun maxSpeedError(cfg: AccelSpeedConfig, tiltRate: Double): Double {
        val est = BaselineDrEstimator(BaselineConfig(compass = CompassConfig(enabled = false), accelSpeed = cfg))
        val dt = 0.05
        var worst = 0.0
        for (i in 0..(200 / dt).toInt()) {
            val t = i * dt
            val bias = if (t < 20) -1.5 else -1.5 + 2.5 * minOf(1.0, (t - 20) / 180)
            est.onMotion(
                MotionUpdate(
                    (t * 1e9).toLong(), dt, 0.0, false, 0.0, 0.0, 0.5,
                    up = Vec3(0.0, 0.0, 1.0), forward = Vec3(0.0, 1.0, 0.0), tiltRateRms = tiltRate,
                    longitudinalAccel = bias, lateralAccel = 0.0,
                ),
            )
            if (i % 20 == 0) {
                val s = t
                if (s <= 20) {
                    val f = fix(s, north(v * s), lon0, acc = 5.0, speed = v, bearing = 0.0)
                    est.onMeasurement(f, trusted(s, LocSource.GNSS))
                } else if (i % 400 == 0) {
                    val f = fix(s, north(v * s), lon0, acc = 60.0, speed = null, bearing = null, source = LocSource.NETWORK)
                    est.onMeasurement(f, trusted(s, LocSource.NETWORK))
                }
            }
            if (t > 21 && i % 20 == 0) est.estimate((t * 1e9).toLong())?.speedMps?.let { worst = max(worst, abs(it - v)) }
        }
        return worst
    }

    @Test
    fun `a hand-held phone's wandering bias does not run the speed away`() {
        val hand = maxSpeedError(AccelSpeedConfig(), 0.13)
        val before = maxSpeedError(AccelSpeedConfig(unsteadyTiltRate = null), 0.13)
        assertTrue(hand < 5.0, "max speed error with the unsteady-mount model: $hand m/s (before: $before)")
        assertTrue(before > 1.5 * hand, "the old model should be clearly worse: $before vs $hand m/s")
    }

    @Test
    fun `a steady holder keeps the old bias model`() {
        assertTrue(maxSpeedError(AccelSpeedConfig(), 0.05) == maxSpeedError(AccelSpeedConfig(unsteadyTiltRate = null), 0.05))
    }
}
