package gpes.core

import gpes.core.TestSupport.fix
import gpes.core.geo.Geo
import gpes.core.model.Cov2
import gpes.core.model.EstimatorMode
import gpes.core.model.LocSource
import gpes.core.model.PositionEstimate
import gpes.core.model.Measurement
import gpes.core.model.TrustReason
import gpes.core.model.TrustState
import gpes.core.model.VehicleSpeedMeasurement
import gpes.core.motion.MotionTracker
import gpes.core.motion.Odometry
import gpes.core.sim.DriveSimulator
import gpes.core.sim.Leg
import gpes.core.sim.SimConfig
import gpes.core.trust.DefaultTrustEvaluator
import gpes.core.trust.MotionView
import gpes.core.trust.TrustConfig
import gpes.core.trust.TrustContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CoarseOdometryTest {

    @Test
    fun `odometry chord matches the true displacement through a turn and needs vehicle speed`() {
        val drive = DriveSimulator.generate(
            SimConfig(legs = listOf(Leg.Stop(3.0), Leg.Straight(20.0, 10.0), Leg.Turn(90.0, 10.0), Leg.Straight(20.0, 10.0)), gyroBiasZ = 0.0),
        )
        val tracker = MotionTracker()
        val speeds = drive.truth.filterIndexed { i, _ -> i % 10 == 0 }.map { VehicleSpeedMeasurement(it.tNs, it.speedMps, 0.3, "test") }
        val all = (drive.records.filterIsInstance<Measurement>() + speeds).sortedBy { it.tNs }
        all.forEach { tracker.onMeasurement(it) }

        val a = drive.truth.first { it.tNs >= drive.truth.first().tNs + 5_000_000_000 }
        val b = drive.truth.last { it.tNs <= drive.truth.last().tNs - 1_000_000_000 }
        val odo = tracker.odometry(a.tNs, b.tNs)!!
        val trueChord = Geo.haversineM(a.lat, a.lon, b.lat, b.lon)
        assertEquals(trueChord, odo.chordM, 0.03 * trueChord)
        assertTrue(odo.distanceM > odo.chordM + 20, "a turn makes the path longer than the chord: $odo")

        val noSpeed = MotionTracker()
        drive.records.filterIsInstance<Measurement>().forEach { noSpeed.onMeasurement(it) }
        assertNull(noSpeed.odometry(a.tNs, b.tNs))
    }

    /** Straight road driven at [speed] from t = 0; the chord is simply speed · Δt. */
    private class StraightRoad(val speed: Double) : MotionView {
        override fun stationaryForS() = 0.0
        override fun bearingChange(t1: Long, t2: Long) = 0.0
        override fun odometry(t1: Long, t2: Long) = ((t2 - t1) / 1e9 * speed).let { Odometry(it, it) }
    }

    private val lat0 = 50.38
    private val lon0 = 30.47
    private fun north(m: Double) = lat0 + m / 111_195.0
    private fun net(tS: Double, northM: Double, acc: Double = 40.0) =
        fix(tS, north(northM), lon0, acc = acc, speed = null, bearing = null, source = LocSource.NETWORK)

    @Test
    fun `a stale coarse fix that stays put while the car drives away is rejected`() {
        val ev = DefaultTrustEvaluator()
        val ctx = TrustContext(null, StraightRoad(15.0))
        assertEquals(TrustState.TRUSTED, ev.assess(net(0.0, 0.0), ctx).state)
        // 20 s at 15 m/s = 300 m, but the fix is still 20 m from the last one.
        val stale = ev.assess(net(20.0, 20.0), ctx)
        assertEquals(TrustState.REJECTED, stale.state)
        assertTrue(TrustReason.COARSE_ODOMETRY_MISMATCH in stale.reasons)
        assertEquals(TrustState.TRUSTED, ev.assess(net(33.0, 480.0), ctx).state)
    }

    @Test
    fun `a coarse fix that jumps much further than the car drove is rejected`() {
        val ev = DefaultTrustEvaluator()
        val ctx = TrustContext(null, StraightRoad(13.0))
        ev.assess(net(0.0, 0.0, acc = 60.0), ctx)
        // 17 s at 13 m/s = 221 m, the fix claims 1100 m.
        assertEquals(TrustState.REJECTED, ev.assess(net(17.0, 1100.0, acc = 140.0), ctx).state)
    }

    /** Voters with hAcc 200, 700, 700 m; then a fix 1600 m on where the car drove 507 m. */
    private fun vagueVoters(cfg: TrustConfig): TrustState {
        val ev = DefaultTrustEvaluator(cfg)
        val ctx = TrustContext(null, StraightRoad(13.0))
        ev.assess(net(0.0, 0.0, acc = 200.0), ctx)
        ev.assess(net(13.0, 169.0, acc = 700.0), ctx)
        ev.assess(net(26.0, 338.0, acc = 700.0), ctx)
        return ev.assess(net(39.0, 1600.0, acc = 157.0), ctx).state
    }

    @Test
    fun `vague voters cannot outvote a precise one`() {
        assertEquals(TrustState.REJECTED, vagueVoters(TrustConfig()))
        // One vote each (the old rule): the two 700-m voters agree, so it was accepted.
        assertEquals(TrustState.TRUSTED, vagueVoters(TrustConfig(coarseOdoWeighted = false)))
    }

    @Test
    fun `one bad reference cannot lock the source out`() {
        val ev = DefaultTrustEvaluator()
        val ctx = TrustContext(null, StraightRoad(10.0))
        ev.assess(net(0.0, 400.0), ctx) // a bad first fix: nothing to compare with, so it is trusted
        assertEquals(TrustState.REJECTED, ev.assess(net(13.0, 130.0), ctx).state)
        // The next good fix agrees with the previous (rejected) good one → accepted.
        assertEquals(TrustState.TRUSTED, ev.assess(net(26.0, 260.0), ctx).state)
    }

    /** Heading-free straight road for the vector check: the gyro frame starts at relative bearing 0. */
    private class StraightRoadVec(val speed: Double) : MotionView {
        override fun stationaryForS() = 0.0
        override fun bearingChange(t1: Long, t2: Long) = 0.0
        override fun odometry(t1: Long, t2: Long) = ((t2 - t1) / 1e9 * speed).let { Odometry(it, it, 0.0, it, 0.0) }
    }

    private fun predicted(tS: Double, headingDeg: Double?, stdDeg: Double) = PositionEstimate(
        (tS * 1e9).toLong(), "test", lat0, lon0, Cov2(1e4, 0.0, 1e4),
        headingRad = headingDeg?.let { Math.toRadians(it) }, headingStdRad = Math.toRadians(stdDeg),
        mode = EstimatorMode.DEAD_RECKONING, confidence = 0.5,
    )

    /** A fix the right distance away but 300 m to the side (east) of a car driving north. */
    private fun sideways(ev: DefaultTrustEvaluator, heading: Double?, std: Double): TrustState {
        val road = StraightRoadVec(10.0)
        for (i in 0..2) ev.assess(net(13.0 * i, 130.0 * i, acc = 60.0), TrustContext(predicted(13.0 * i, heading, std), road))
        // The car is 260 m north of the last fix; this fix is 260 m from it too, but due east.
        val bad = fix(52.0, north(260.0), lon0 + 260.0 / (111_195.0 * kotlin.math.cos(Math.toRadians(lat0))),
            acc = 130.0, speed = null, bearing = null, source = LocSource.NETWORK)
        return ev.assess(bad, TrustContext(predicted(52.0, heading, std), road)).state
    }

    @Test
    fun `with a known heading a fix in the wrong direction is rejected`() {
        assertEquals(TrustState.REJECTED, sideways(DefaultTrustEvaluator(), heading = 0.0, std = 5.0))
    }

    @Test
    fun `without a reliable heading only the distance is checked`() {
        assertEquals(TrustState.TRUSTED, sideways(DefaultTrustEvaluator(), heading = null, std = 5.0))
        assertEquals(TrustState.TRUSTED, sideways(DefaultTrustEvaluator(), heading = 0.0, std = 40.0))
    }

    @Test
    fun `with recently trusted GNSS the heading may be spoofed, so only the distance is checked`() {
        val ev = DefaultTrustEvaluator()
        // A trusted GNSS fix 5 s before the coarse fixes, 57 s before the tested one (the EKF heading follows GNSS).
        val g = fix(-5.0, north(-50.0), lon0, acc = 5.0, speed = 10.0, bearing = 0.0)
        assertEquals(TrustState.TRUSTED, ev.assess(g, TrustContext(null, StraightRoadVec(10.0))).state)
        assertEquals(TrustState.TRUSTED, sideways(ev, heading = 0.0, std = 5.0))
    }

    @Test
    fun `with a known heading a fix in the right direction is accepted`() {
        val ev = DefaultTrustEvaluator()
        val road = StraightRoadVec(10.0)
        for (i in 0..3) {
            val st = ev.assess(net(13.0 * i, 130.0 * i + 50.0 * (i % 2), acc = 60.0), TrustContext(predicted(13.0 * i, 0.0, 5.0), road)).state
            assertEquals(TrustState.TRUSTED, st, "fix $i")
        }
    }

    @Test
    fun `without vehicle speed the check is skipped`() {
        val ev = DefaultTrustEvaluator()
        val ctx = TrustContext(null, object : MotionView {
            override fun stationaryForS() = 0.0
            override fun bearingChange(t1: Long, t2: Long) = 0.0
        })
        ev.assess(net(0.0, 0.0), ctx)
        assertFalse(TrustReason.COARSE_ODOMETRY_MISMATCH in ev.assess(net(20.0, 20.0), ctx).reasons)
    }
}
