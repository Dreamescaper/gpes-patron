package gpes.core

import gpes.core.estimator.BaselineConfig
import gpes.core.model.LocSource
import gpes.core.replay.Metrics
import gpes.core.replay.ReplayRunner
import gpes.core.replay.Scenario
import gpes.core.replay.ScenarioStep
import gpes.core.replay.TruthTrack
import gpes.core.replay.Variant
import gpes.core.road.OsmWay
import gpes.core.road.RoadConstraintConfig
import gpes.core.road.RoadNetwork
import gpes.core.sim.DriveSimulator
import gpes.core.sim.Leg
import gpes.core.sim.SimConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Real case: 2026-09-28 +5:37 (R-021…R-024, D-046, D-049). Before a 90° turn the estimate ran ~20 m behind
 * along the street (speedometer scale); after the turn that became a ~22 m cross-track offset, and no road
 * update applied because the matcher was not yet confident on the new street. The corner fix (2-D update
 * right after the turn) removes it; its significance test keeps it out when the estimate is already good.
 */
class CornerFixTest {

    private val drive = DriveSimulator.generate(
        SimConfig(legs = listOf(Leg.Stop(5.0), Leg.Straight(60.0, 12.0), Leg.Turn(90.0, 10.0), Leg.Straight(50.0, 12.0)), gyroBiasZ = 0.0),
    )
    private val truth = TruthTrack(drive.truth)
    private val roads = drive.truth.filterIndexed { i, _ -> i % 10 == 0 }.let { t ->
        RoadNetwork.build(listOf(OsmWay(1, LongArray(t.size) { it + 1L }, t.map { it.lat }.toDoubleArray(), t.map { it.lon }.toDoubleArray(),
            "secondary", false, 2, 0, "Road")))
    }

    /** Mean error 10–40 s after the turn, without GNSS, with a speedometer reading [scaleError] off. */
    private fun errAfterTurn(scaleError: Double, corner: RoadConstraintConfig): Pair<Double, List<String>> {
        val steps = listOf(
            // GNSS for the first 20 s only (heading known, as on the real drive long before the turn).
            ScenarioStep.DropSource(LocSource.GNSS, dropRawGnss = true, startS = 20.0),
            ScenarioStep.DropSource(LocSource.FUSED, dropRawGnss = false, startS = 0.0),
            ScenarioStep.SyntheticNetwork(sigmaM = 60.0, periodS = 40.0),
            ScenarioStep.SyntheticVehicleSpeed(sigmaMps = 0.1, scaleError = scaleError),
        )
        // No confident-road updates at all (as after the real turn), so only the corner fix can act.
        val cfg = BaselineConfig(roadConstraint = corner.copy(minConfidentM = 1e9))
        val r = ReplayRunner(roads = roads).run(TestSupport.measurements(drive), Scenario("corner", "", emptyList()),
            Variant("v", extraSteps = steps, baseline = cfg, roads = true), truth)
        val rows = Metrics.rows(r)
        val turnEnd = 5 + 60 + 9.0
        val after = rows.filter { it.tS in (turnEnd + 10)..(turnEnd + 40) }.mapNotNull { it.errM }
        return after.average() to r.estimates.map { "${it.lat},${it.lon}" }
    }

    @Test
    fun `the corner fix removes the offset carried into a turn`() {
        val off = errAfterTurn(-0.08, RoadConstraintConfig(cornerFix = false)).first
        val on = errAfterTurn(-0.08, RoadConstraintConfig(cornerFix = true, cornerMinSigmas = 2.0)).first
        assertTrue(on < 0.7 * off, "after the turn: without $off m, with $on m")
    }

    @Test
    fun `when the estimate is already good the significance test keeps the corner fix out`() {
        val off = errAfterTurn(0.0, RoadConstraintConfig(cornerFix = false)).second
        val on = errAfterTurn(0.0, RoadConstraintConfig(cornerFix = true, cornerMinSigmas = 2.0)).second
        assertEquals(off, on)
    }
}
