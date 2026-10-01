package gpes.core

import gpes.core.estimator.BaselineConfig
import gpes.core.estimator.CompassConfig
import gpes.core.geo.Geo
import gpes.core.geo.LocalFrame
import gpes.core.model.Cov2
import gpes.core.model.LocSource
import gpes.core.model.LocationMeasurement
import gpes.core.model.Measurement
import gpes.core.replay.ReplayRunner
import gpes.core.replay.Scenario
import gpes.core.replay.ScenarioStep
import gpes.core.replay.TruthTrack
import gpes.core.replay.Variant
import gpes.core.sim.DriveSimulator
import gpes.core.sim.SimConfig
import gpes.core.sim.SimDrive
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** Robust coarse updates and the consistent-stream reset (D-034). */
class RobustCoarseTest {
    private val drive: SimDrive by lazy { DriveSimulator.generate(SimConfig()) }
    private val t0 get() = drive.truth.first().tNs

    /** Network fixes every 16 s (the EKF fuses at most one per 15 s once it is confident) from truth (σ 25 m, hAcc honest), with an optional offset by time. */
    private fun network(offset: (Double) -> Pair<Double, Double>): List<LocationMeasurement> {
        val rnd = Random(7)
        val sigma = 25.0
        return drive.truth.filter { ((it.tNs - t0) / 1e9).let { s -> s >= 1 && (s % 16.0) < 0.1 } }.map { tr ->
            val s = (tr.tNs - t0) / 1e9
            val (oe, on) = offset(s)
            val f = LocalFrame(tr.lat, tr.lon)
            val ll = f.toLatLon(oe + rnd.nextDouble(-1.0, 1.0) * sigma * 1.7, on + rnd.nextDouble(-1.0, 1.0) * sigma * 1.7)
            LocationMeasurement(tr.tNs, tr.tNs + 300_000_000, LocSource.NETWORK, "network", ll.lat, ll.lon, hAccM = sigma * Cov2.R68_PER_SIGMA)
        }
    }

    private fun run(net: List<LocationMeasurement>, baseline: BaselineConfig, gnssUntilS: Double) = ReplayRunner().run(
        (drive.records.filterIsInstance<Measurement>() + net).sortedBy { it.tNs },
        Scenario("s", steps = listOf(ScenarioStep.DropSource(LocSource.GNSS, gnssUntilS))),
        Variant("v", extraSteps = listOf(ScenarioStep.SyntheticVehicleSpeed(0.3, scaleError = 0.01)), baseline = baseline),
        TruthTrack(drive.truth),
    )

    private val robust = BaselineConfig(compass = CompassConfig(enabled = false))
    private val plain = robust.copy(coarseRobustNis = null)

    private fun errAt(r: gpes.core.replay.ReplayResult, s: Double): Double {
        val e = r.estimates.first { (it.tNs - r.t0Ns) / 1e9 >= s }
        val tr = r.truth.at(e.tNs)!!
        return Geo.haversineM(e.lat, e.lon, tr.lat, tr.lon)
    }

    @Test
    fun `a single coarse outlier barely moves a good estimate`() {
        // GNSS until 120 s, then dead reckoning with good network fixes; one fix at 176 s is 400 m off.
        val net = network { s -> if (s in 175.0..177.0) 400.0 to 0.0 else 0.0 to 0.0 }
        val before = run(net, robust, 120.0).let { errAt(it, 175.0) }
        val rob = run(net, robust, 120.0).let { errAt(it, 178.0) }
        val pl = run(net, plain, 120.0).let { errAt(it, 178.0) }
        println("outlier: before $before, robust $rob, plain $pl")
        assertTrue(rob < before + 60, "robust: error $before → $rob m after the outlier")
        assertTrue(pl > rob + 100, "plain should jump to the outlier (reset at NIS > 50): plain $pl vs robust $rob")
    }

    @Test
    fun `a stream of consistent fixes pulls a drifted estimate back`() {
        // No GNSS from 120 s. Network is 500 m off until 300 s (the estimate follows it), then correct:
        // several fixes in a row "say we are 500 m away" and agree with each other.
        val net = network { s -> if (s < 300.0) 500.0 to 0.0 else 0.0 to 0.0 }
        val r = run(net, robust, 120.0)
        val atSwitch = errAt(r, 298.0)
        val after = errAt(r, 370.0)
        println("stream: at switch $atSwitch, +40 s ${errAt(r, 340.0)}, +70 s $after; plain +70 s ${errAt(run(net, plain, 120.0), 370.0)}")
        assertTrue(atSwitch > 300, "setup: the estimate should be off before the switch ($atSwitch m)")
        assertTrue(after < 150, "not recovered 70 s after the switch: $after m")
    }
}
