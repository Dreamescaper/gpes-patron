package gpes.core

import gpes.core.TestSupport.fix
import gpes.core.estimator.AccelSpeedConfig
import gpes.core.estimator.BaselineConfig
import gpes.core.estimator.BaselineDrEstimator
import gpes.core.estimator.CompassConfig
import gpes.core.geo.Geo
import gpes.core.model.LocSource
import gpes.core.model.PositionEstimate
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustState
import gpes.core.motion.MotionUpdate
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * D-076. Drive 20261003-153540, 186–208 s: GPS courses were 325° before and 311° after, but the gyro of
 * the hand-held phone accumulated ~70° of extra left turn while the tilt rate reached 0.2–0.6 rad/s; σ_ψ stayed 5°, so the network
 * fixes on the right road did not turn the heading back and the track went off across a block. In miniature: 8 m/s due
 * 315°, GNSS for 20 s, then the gyro reports −70° over 20 s with tilt rate 0.4 rad/s, network fixes (hAcc 30 m) every 16 s
 * on the true straight track. Heading error at 80 s, before the old filter restarts its heading search.
 */
class HandYawTest {
    private val lat0 = 50.40
    private val lon0 = 30.50
    private val v = 8.0
    private val brg = Math.toRadians(315.0)
    private fun at(d: Double) = Pair(lat0 + d * cos(brg) / 111_195.0, lon0 + d * sin(brg) / (111_195.0 * cos(Math.toRadians(lat0))))

    private fun replay(cfg: BaselineConfig, tiltRate: Double = 0.4, restoreDuringTurn: Boolean = false): PositionEstimate {
        var est = BaselineDrEstimator(cfg)
        val dt = 0.05
        for (i in 0..(80 / dt).toInt()) {
            val t = i * dt
            val hand = t in 30.0..50.0
            // A left turn of 70° in 20 s (ω > 0 is counter-clockwise), which the car did not make.
            val w = if (hand) Math.toRadians(70.0) / 20.0 else 0.0
            est.onMotion(MotionUpdate((t * 1e9).toLong(), dt, w, false, 0.0, 0.0, 0.5, tiltRateRms = if (hand) tiltRate else 0.05))
            if (restoreDuringTurn && i == 800) {
                val saved = est.snapshot()
                est = BaselineDrEstimator(cfg).also { it.restore(saved) }
            }
            if (i % 20 != 0) continue
            val (lat, lon) = at(v * t)
            if (t <= 20) {
                val f = fix(t, lat, lon, acc = 5.0, speed = v, bearing = 315.0)
                est.onMeasurement(f, TrustAssessment(f.tNs, LocSource.GNSS, "gps", TrustState.TRUSTED, 1.0, emptySet()))
            } else if (i % 320 == 0) {
                val f = fix(t, lat, lon, acc = 30.0, speed = null, bearing = null, source = LocSource.NETWORK)
                est.onMeasurement(f, TrustAssessment(f.tNs, LocSource.NETWORK, "network", TrustState.TRUSTED, 1.0, emptySet()))
            }
        }
        return est.estimate((80 * 1e9).toLong())!!
    }

    private fun headingError(cfg: BaselineConfig): Double {
        val e = replay(cfg)
        return abs(Math.toDegrees(Geo.wrapRad(e.headingRad!! - brg)))
    }

    private val cfg = BaselineConfig(compass = CompassConfig(enabled = false), accelSpeed = AccelSpeedConfig(enabled = false))

    @Test
    fun `network fixes turn back a heading the hand rotated`() {
        val hand = headingError(cfg)
        val before = headingError(cfg.copy(handYawNoise = 0.0))
        assertTrue(hand < 25.0, "heading error with hand yaw noise: $hand° (without: $before°)")
        assertTrue(before > 2 * hand, "without it the heading should stay clearly wrong: $before° vs $hand°")
    }

    @Test
    fun `holder vibration below the floor leaves propagation unchanged`() {
        assertEquals(replay(cfg.copy(handYawNoise = 0.0), tiltRate = 0.14), replay(cfg, tiltRate = 0.14))
    }

    @Test
    fun `restore during hand rotation preserves the following corrections`() {
        assertEquals(replay(cfg), replay(cfg, restoreDuringTurn = true))
    }
}
