package gpes.core

import gpes.core.estimator.AccelSpeedConfig
import gpes.core.estimator.BaselineConfig
import gpes.core.estimator.CompassConfig
import gpes.core.model.LocSource
import gpes.core.replay.Metrics
import gpes.core.replay.Scenario
import gpes.core.replay.ScenarioStep
import gpes.core.replay.Variant
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Real case: drives without OBD (D-036, D-050, R-026): phone+network p95 ×0.49 (R-007), ×0.59 (2026-09-28),
 * ×0.87 (A). The simulator has no realistic vibration yet (roadmap), so its stop and up behaviour differ from a
 * real car and it cannot show that gain; this test only guards that the accelerometer speed does no harm there:
 * GNSS gone after 120 s, coarse fixes only, no OBD.
 */
class AccelSpeedTest {

    private val scenario = Scenario("drop", steps = listOf(ScenarioStep.DropSource(LocSource.GNSS, 120.0)))
    private val coarse = listOf(
        ScenarioStep.DropSource(LocSource.NETWORK, dropRawGnss = false),
        ScenarioStep.SyntheticNetwork(40.0, 13.0),
    )

    private fun run(accel: Boolean): Pair<Double, Double> {
        val cfg = BaselineConfig(compass = CompassConfig(enabled = false), accelSpeed = AccelSpeedConfig(enabled = accel))
        val (r, s) = TestSupport.run(scenario, Variant("v", extraSteps = coarse, baseline = cfg))
        val speedErr = r.estimates.filter { (it.tNs - r.t0Ns) / 1e9 > 180 }.mapNotNull { e ->
            val tr = r.truth.at(e.tNs) ?: return@mapNotNull null
            e.speedMps?.let { abs(it - tr.speedMps) }
        }.sorted().let { it[it.size / 2] }
        return speedErr to s.p95M!!
    }

    @Test
    fun `without OBD the accelerometer speed does no harm in the simulator`() {
        val (spOff, p95Off) = run(false)
        val (spOn, p95On) = run(true)
        assertTrue(spOn <= 1.05 * spOff, "median speed error: random walk $spOff m/s, accelerometer $spOn m/s")
        assertTrue(p95On <= p95Off * 1.1, "p95: random walk $p95Off m, accelerometer $p95On m")
    }
}
