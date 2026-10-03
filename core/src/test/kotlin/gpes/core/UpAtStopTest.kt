package gpes.core

import gpes.core.model.ImuKind
import gpes.core.model.ImuSample
import gpes.core.motion.MotionConfig
import gpes.core.motion.MotionTracker
import gpes.core.motion.Vec3
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * D-072. Drive 20261003-140822 (phone in the hand, no OBD): at stops the gyro-carried "up" was 4–15° away from the
 * accelerometer (which at rest is exactly gravity), leaking 0.7–2.5 m/s² of gravity into the longitudinal acceleration;
 * the 30-s correction was too slow for a stop of 10–40 s. Built in miniature: a static phone whose true gravity is
 * [tiltDeg] away from the tracker's up (the hand tilted it without the gyro integration keeping up).
 */
class UpAtStopTest {
    private fun g(tiltDeg: Double): Vec3 {
        val t = Math.toRadians(tiltDeg)
        return Vec3(9.81 * sin(t), 0.0, 9.81 * cos(t))
    }

    private fun angleDeg(a: Vec3, b: Vec3) = Math.toDegrees(acos((a.unit()!! dot b.unit()!!).coerceIn(-1.0, 1.0)))

    /** 100 Hz IMU for [fromS, toS): specific force [f] plus a small vibration, zero gyro. */
    private fun feed(tr: MotionTracker, fromS: Double, toS: Double, f: (Double) -> Vec3) {
        var t = fromS
        var i = 0
        while (t < toS) {
            val v = f(t)
            val n = 0.05 * sin(i * 1.7)
            tr.onMeasurement(ImuSample((t * 1e9).toLong(), ImuKind.ACCEL, v.x + n, v.y - n, v.z + n))
            tr.onMeasurement(ImuSample((t * 1e9).toLong() + 1000, ImuKind.GYRO, 0.001 * n, 0.0, 0.0))
            t += 0.01; i++
        }
    }

    private fun upAfterStop(cfg: MotionConfig): Double {
        val tr = MotionTracker(cfg)
        tr.speedHintMps = 0.0
        feed(tr, 0.0, 5.0) { g(0.0) }                  // seeds up on the true gravity
        feed(tr, 5.0, 17.0) { g(12.0) }                // the phone is now 12° off what the tracker believes
        return angleDeg(tr.up(17_000_000_000L)!!, g(12.0))
    }

    @Test
    fun `up converges to gravity within seconds at a stop, not within a minute`() {
        val fast = upAfterStop(MotionConfig())
        val slow = upAfterStop(MotionConfig(upStillTauS = null))
        assertTrue(fast < 1.5, "fast correction left $fast°")
        assertTrue(slow > 5.0, "without it $slow° were left (the test does not reproduce the case)")
    }

    @Test
    fun `a pull-away from the stop is not mistaken for tilt`() {
        val tr = MotionTracker()
        tr.speedHintMps = 0.0
        feed(tr, 0.0, 8.0) { g(0.0) }
        // Smooth acceleration 1.5 m/s² along x, ramped in over 1 s; the estimator's speed reaches 1.5 m/s after 1 s.
        feed(tr, 8.0, 9.0) { t -> g(0.0) + Vec3(1.5 * (t - 8.0), 0.0, 0.0) }
        tr.speedHintMps = 1.0
        feed(tr, 9.0, 10.0) { g(0.0) + Vec3(1.5, 0.0, 0.0) }
        tr.speedHintMps = 3.0
        feed(tr, 10.0, 13.0) { g(0.0) + Vec3(1.5, 0.0, 0.0) }
        val err = angleDeg(tr.up(13_000_000_000L)!!, g(0.0))
        assertTrue(err < 1.5, "up was pulled $err° towards the vehicle acceleration")
    }
}
