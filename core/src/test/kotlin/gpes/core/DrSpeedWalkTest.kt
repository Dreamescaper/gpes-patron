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
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * D-075. Drive 20261003-153540 (no OBD, GNSS only in probe windows): with the accelerometer speed off, the speed was a
 * random walk of 0.7 m/s/√s, while a city car went 0 → 15 m/s within 20 s, so the estimator claimed half its error
 * (within68 0.20). In miniature: the car waits at a light with GNSS, GNSS disappears, the car pulls away to 15 m/s in
 * 20 s and cruises; no speed source at all. Under a spoofer (GNSS fixes keep arriving, untrusted) the walk stays tight.
 */
class DrSpeedWalkTest {
    private val lat0 = 50.40
    private val lon0 = 30.50
    private fun north(m: Double) = lat0 + m / 111_195.0
    private fun truthSpeed(t: Double) = when { t < 20 -> 0.0; t < 40 -> 0.75 * (t - 20); else -> 15.0 }

    /** (|v_est − v_true|, σ_v) at 45 s. [gnssAfter]: trust state of GNSS fixes after 20 s (null = no fixes). */
    private fun speedAt45(cfg: BaselineConfig, gnssAfter: TrustState?): Pair<Double, Double> {
        val est = BaselineDrEstimator(cfg)
        val dt = 0.05
        var pos = 0.0
        for (i in 0..(45 / dt).toInt()) {
            val t = i * dt
            pos += truthSpeed(t) * dt
            est.onMotion(MotionUpdate((t * 1e9).toLong(), dt, 0.0, false, 0.0, 0.0, 0.5))
            if (i % 20 != 0) continue
            val state = if (t <= 20) TrustState.TRUSTED else gnssAfter ?: continue
            val f = fix(t, north(pos), lon0, acc = 5.0, speed = truthSpeed(t), bearing = 0.0)
            est.onMeasurement(f, TrustAssessment(f.tNs, LocSource.GNSS, "gps", state, 1.0, emptySet()))
        }
        val e = est.estimate((45 * 1e9).toLong())!!
        return abs(e.speedMps!! - 15.0) to e.speedStdMps!!
    }

    private val cfg = BaselineConfig(compass = CompassConfig(enabled = false), accelSpeed = AccelSpeedConfig(enabled = false))

    @Test
    fun `without any GNSS the speed uncertainty covers a pull-away`() {
        val (err, sd) = speedAt45(cfg, null)
        val (errOld, sdOld) = speedAt45(cfg.copy(speedRandomWalkDr = null), null)
        assertTrue(err <= 2.5 * sd, "error $err m/s vs σ $sd (2.5σ must cover it)")
        assertTrue(errOld > 2.5 * sdOld, "with 0.7 m/s/√s the σ should be too small: error $errOld vs σ $sdOld")
    }

    @Test
    fun `untrusted GNSS fixes keep the tight walk`() {
        val (_, sdSpoofed) = speedAt45(cfg, TrustState.QUESTIONABLE)
        val (_, sdOld) = speedAt45(cfg.copy(speedRandomWalkDr = null), TrustState.QUESTIONABLE)
        assertTrue(abs(sdSpoofed - sdOld) < 1e-9, "σ with untrusted fixes $sdSpoofed vs old $sdOld")
    }
}
