package gpes.core

import gpes.core.estimator.AccelSpeedConfig
import gpes.core.estimator.BaselineConfig
import gpes.core.estimator.BaselineDrEstimator
import gpes.core.estimator.CompassConfig
import gpes.core.geo.Geo
import gpes.core.geo.LocalFrame
import gpes.core.model.Cov2
import gpes.core.model.LocSource
import gpes.core.model.PositionEstimate
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustState
import gpes.core.motion.MotionUpdate
import gpes.core.road.OsmWay
import gpes.core.road.RoadConstraintConfig
import gpes.core.road.RoadHeadingConsensus
import gpes.core.road.RoadMatcherConfig
import gpes.core.road.RoadNetwork
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * D-077, 20261003-153540 (R-032): after hand movement the course remains wrong between correct network fixes,
 * and speed uncertainty prevents the existing road updates. A miniature straight corridor with a 20° residual
 * course error and 8 m/s nominal speed, without OBD. Local offsets from an arbitrary origin; no real trace.
 */
class RoadWithoutSpeedTest {
    private val frame = LocalFrame(45.0, 10.0)
    private fun way(id: Long, points: List<Pair<Double, Double>>, name: String = "Road") = OsmWay(
        id, LongArray(points.size) { id * 100 + it },
        points.map { frame.toLatLon(it.first, it.second).lat }.toDoubleArray(),
        points.map { frame.toLatLon(it.first, it.second).lon }.toDoubleArray(), "secondary", false, 2, 0, name,
    )
    private val straight = way(1, listOf(0.0 to -1000.0, 0.0 to 2000.0))
    private val net = RoadNetwork.build(listOf(straight))
    private val rc = RoadConstraintConfig(crossTrack = false, alongTrack = false, cornerFix = false, minConfidentM = 1e9)
    private val cfg = BaselineConfig(compass = CompassConfig(enabled = false), accelSpeed = AccelSpeedConfig(enabled = false),
        headingRandomWalk = 0.03, roadConstraint = rc)

    private fun consensus(network: RoadNetwork, e: Double = 0.0, n: Double = 0.0, sigma: Double = 15.0): Double? {
        val ll = frame.toLatLon(e, n)
        return RoadHeadingConsensus.bearing(network, ll.lat, ll.lon, Cov2.isotropic(sigma), Math.toRadians(20.0), Math.toRadians(30.0),
            RoadMatcherConfig(), rc)
    }

    @Test
    fun `parallel carriageways share an axis without selecting one by name`() {
        // R-020b / D-045: a side carriageway 20 m away shares the main road's name.
        val parallel = RoadNetwork.build(listOf(straight, way(2, listOf(20.0 to -1000.0, 20.0 to 2000.0))))
        assertEquals(0.0, consensus(parallel, e = 10.0)!!, 1e-6)
    }

    @Test
    fun `a plausible crossing road vetoes a heading even when its name differs`() {
        val crossing = RoadNetwork.build(listOf(straight, way(2, listOf(-1000.0 to 0.0, 1000.0 to 0.0), "Cross")))
        assertNull(consensus(crossing))
    }

    @Test
    fun `duplicated map geometry cannot outvote a competing road axis`() {
        val roads = (1L..20L).map { way(it, listOf(0.0 to -1000.0, 0.0 to 2000.0)) } +
            way(21, listOf(-1000.0 to 0.0, 1000.0 to 0.0), "Cross")
        assertNull(consensus(RoadNetwork.build(roads)))
    }

    @Test
    fun `large along-track uncertainty still permits a common straight axis`() {
        val bearing = RoadHeadingConsensus.bearing(net, frame.lat0, frame.lon0, Cov2(25.0, 0.0, 10_000.0),
            Math.toRadians(20.0), Math.toRadians(30.0), RoadMatcherConfig(), rc)
        assertEquals(0.0, bearing!!, 1e-6)
    }

    @Test
    fun `missing actual road does not make a distant parallel road plausible`() {
        val onlyParallel = RoadNetwork.build(listOf(way(2, listOf(60.0 to -1000.0, 60.0 to 2000.0))))
        assertNull(consensus(onlyParallel, sigma = 5.0))
    }

    @Test
    fun `an S bend between equal end bearings is not a straight road`() {
        val bend = RoadNetwork.build(listOf(way(1, listOf(0.0 to -1000.0, 0.0 to 1.0,
            0.1 to 1.1, 0.0 to 1.2, 0.0 to 2000.0))))
        assertNull(consensus(bend))
    }

    private fun replay(enabled: Boolean, restoreAt: Double? = null, tilt: Double = 0.05,
                       yaw: Double = 0.0, fixAgeS: Double = 45.0): Pair<PositionEstimate, Int> {
        val config = cfg.copy(roadConstraint = rc.copy(uncertainSpeedHeading = enabled, uncertainHeadingFixMaxAgeS = fixAgeS))
        var estimator = BaselineDrEstimator(config, { net })
        val f = TestSupport.fix(0.0, frame.lat0, frame.lon0, acc = 30.0, speed = 8.0, bearing = 20.0)
            .copy(bearingAccDeg = 30.0)
        estimator.onMeasurement(f, TrustAssessment(f.tNs, LocSource.GNSS, "gps", TrustState.TRUSTED, 1.0, emptySet()))
        for (i in 0..160) {
            val t = i * 0.05
            estimator.onMotion(MotionUpdate((t * 1e9).toLong(), 0.05, yaw, false, 0.0, 0.0, 0.5, tiltRateRms = tilt))
            if (restoreAt != null && i == (restoreAt / 0.05).toInt()) {
                val snapshot = estimator.snapshot()
                estimator = BaselineDrEstimator(config, { net }).also { it.restore(snapshot) }
            }
        }
        return estimator.estimate(8_000_000_000)!! to estimator.uncertainHeadingStats[0]
    }

    @Test
    fun `road axis corrects heading with unknown speed without inventing a speed observation`() {
        val (before, _) = replay(false)
        val (after, updates) = replay(true)
        assertTrue(after.speedStdMps!! > rc.maxSpeedStdMps)
        assertTrue(updates > 0, "the no-speed road constraint must actually apply")
        assertTrue(abs(Math.toDegrees(Geo.wrapRad(after.headingRad!!))) < 8.0)
        assertTrue(abs(Math.toDegrees(Geo.wrapRad(before.headingRad!!))) > 15.0)
        assertEquals(before.speedMps, after.speedMps)
        assertEquals(before.speedStdMps, after.speedStdMps)
        assertTrue(after.cov.r68 >= before.cov.r68 - 1e-6, "the reported uncertainty retains the road-free radius")
    }

    @Test
    fun `movement of the phone defers the road update`() {
        assertEquals(0, replay(true, tilt = 0.4).second)
    }

    @Test
    fun `a real gyro turn defers the road update`() {
        assertEquals(0, replay(true, yaw = 0.1).second)
    }

    @Test
    fun `no fresh position anchor leaves the road inactive`() {
        assertEquals(0, replay(true, fixAgeS = 0.0).second)
    }

    @Test
    fun `rollback retains the motion window and road update cadence`() {
        assertEquals(replay(true).first, replay(true, restoreAt = 2.0).first)
        assertEquals(replay(true).first, replay(true, restoreAt = 6.0).first)
    }
}
