package gpes.core

import gpes.core.TestSupport.fix
import gpes.core.estimator.BaselineDrEstimator
import gpes.core.model.EstimatorMode
import gpes.core.model.LocSource
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustState
import gpes.core.replay.Scenario
import gpes.core.replay.ScenarioStep
import gpes.core.replay.Variant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EstimatorTest {
    private fun trusted(tS: Double, source: LocSource) =
        TrustAssessment((tS * 1e9).toLong(), source, "x", TrustState.TRUSTED, 1.0, emptySet())

    @Test
    fun `startup from coarse network fix is honestly uncertain`() {
        val est = BaselineDrEstimator()
        val net = fix(0.0, 50.45, 30.52, acc = 800.0, speed = null, bearing = null, source = LocSource.NETWORK)
        est.onMeasurement(net, trusted(0.0, LocSource.NETWORK))
        val e = est.estimate(0)!!
        assertEquals(EstimatorMode.COARSE_ONLY, e.mode)
        assertTrue(e.accuracyM >= 800.0, "accuracy ${e.accuracyM}")
        assertNull(e.headingRad)
        // With no motion info the uncertainty must grow, not stay put.
        val later = est.estimate(60_000_000_000)!!
        assertTrue(later.accuracyM > e.accuracyM)
    }

    @Test
    fun `clean drive tracks GNSS closely`() {
        val (_, s) = TestSupport.run(Scenario("clean"))
        assertTrue(s.p95M!! < 10.0, "p95 ${s.p95M}")
        assertTrue(s.headingP95Deg!! < 10.0, "heading p95 ${s.headingP95Deg}")
    }

    @Test
    fun `30 s outage with synthetic vehicle speed stays bounded`() {
        val scenario = Scenario("drop30", steps = listOf(ScenarioStep.DropSource(LocSource.GNSS, 150.0, 30.0)))
        val v = Variant("obd", extraSteps = listOf(ScenarioStep.SyntheticVehicleSpeed(sigmaMps = 0.3)))
        val (_, s) = TestSupport.run(scenario, v)
        val w = s.windows.single()
        assertTrue(w.maxErrM!! < 30.0, "max error in outage ${w.maxErrM}")
        assertTrue(w.recoveryS!! < 5.0, "recovery ${w.recoveryS}")
    }

    @Test
    fun `2 min outage without speed has growing but honest uncertainty`() {
        val scenario = Scenario("drop120", steps = listOf(ScenarioStep.DropSource(LocSource.GNSS, 150.0, 120.0)))
        val (r, s) = TestSupport.run(scenario)
        val inWindow = r.estimates.filter { (it.tNs - r.t0Ns) / 1e9 in 150.0..270.0 }
        assertTrue(inWindow.last().accuracyM > inWindow.first().accuracyM * 5, "uncertainty should grow")
        // Calibration: error should rarely exceed the reported 95% radius.
        assertTrue(s.within95!! > 0.85, "within95 ${s.within95}")
    }

    @Test
    fun `hold-last-fix is worse than baseline with speed during an outage`() {
        val scenario = Scenario("drop60", steps = listOf(ScenarioStep.DropSource(LocSource.GNSS, 150.0, 60.0)))
        val (_, hold) = TestSupport.run(scenario, Variant("hold", estimator = "passthrough"))
        val (_, dr) = TestSupport.run(scenario, Variant("obd", extraSteps = listOf(ScenarioStep.SyntheticVehicleSpeed())))
        assertTrue(dr.windows.single().maxErrM!! < hold.windows.single().maxErrM!! / 3)
    }
}
