package gpes.core

import gpes.core.estimator.BaselineDrEstimator
import gpes.core.io.DriveJson
import gpes.core.model.Cov2
import gpes.core.model.PositionEstimate
import gpes.core.pipeline.MeasurementPipeline
import gpes.core.pipeline.PipelineConfig
import gpes.core.pipeline.PipelineListener
import gpes.core.road.OsmWay
import gpes.core.road.RoadMatcher
import gpes.core.road.RoadMatcherConfig
import gpes.core.road.RoadNetwork
import gpes.core.road.RoadStepInput
import gpes.core.trust.DefaultTrustEvaluator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RoadMatcherTest {

    private val lat0 = 50.40
    private val lon0 = 30.50
    private val mLat = 1 / 111_195.0
    private val mLon = 1 / (111_195.0 * kotlin.math.cos(Math.toRadians(lat0)))

    private fun way(id: Long, nodes: List<Pair<Long, Pair<Double, Double>>>, cls: String = "secondary", name: String = "") = OsmWay(
        id, nodes.map { it.first }.toLongArray(),
        nodes.map { lat0 + it.second.second * mLat }.toDoubleArray(), nodes.map { lon0 + it.second.first * mLon }.toDoubleArray(),
        cls, false, 2, 0, name,
    )

    /** An avenue along x = 0 (north), a parallel street at x = 60 m, joined by a cross street at y = 1000 m. */
    private val net = RoadNetwork.build(listOf(
        way(1, listOf(1L to (0.0 to -200.0), 2L to (0.0 to 1000.0), 3L to (0.0 to 1500.0)), name = "Avenue"),
        way(2, listOf(4L to (60.0 to -200.0), 5L to (60.0 to 1000.0)), name = "Parallel"),
        way(3, listOf(2L to (0.0 to 1000.0), 5L to (60.0 to 1000.0)), name = "Cross"),
    ))

    private fun step(m: RoadMatcher, e: Double, n: Double, heading: Double, turn: Double = 0.0, sigma: Double = 15.0) =
        m.step(RoadStepInput(lat0 + n * mLat, lon0 + e * mLon, Cov2(sigma * sigma, 0.0, sigma * sigma), Math.toRadians(heading),
            Math.toRadians(5.0), 10.0, Math.toRadians(turn), 12.0))

    @Test
    fun `follows the road it is on, not the parallel one 60 m away`() {
        val m = RoadMatcher(RoadMatcherConfig(), net)
        for (k in 0..40) step(m, 8.0, k * 10.0, 0.0) // 8 m east of the avenue (lane, pose noise)
        val b = m.best()!!
        assertEquals("Avenue", b.roadName)
        assertTrue(b.probability > 0.9, "$b")
        assertTrue(b.pOffRoad < 0.05, "$b")
        assertTrue(b.confidentM >= 150, "$b")
    }

    @Test
    fun `turning off into an area with no road makes it off-road`() {
        val m = RoadMatcher(RoadMatcherConfig(), net)
        for (k in 0..30) step(m, 0.0, k * 10.0, 0.0)
        // Turn right at y = 300 into a field (no road there) and drive east 200 m.
        step(m, 10.0, 305.0, 90.0, turn = 90.0)
        for (k in 1..20) step(m, 10.0 + k * 10.0, 305.0, 90.0)
        assertTrue(m.pOffRoad() > 0.5, "pOff=${m.pOffRoad()}")
    }

    @Test
    fun `a lane change keeps the road`() {
        val m = RoadMatcher(RoadMatcherConfig(), net)
        for (k in 0..20) step(m, 0.0, k * 10.0, 0.0)
        step(m, 2.0, 210.0, 6.0, turn = 6.0); step(m, 3.5, 220.0, 0.0, turn = -6.0)
        for (k in 23..35) step(m, 3.5, k * 10.0, 0.0)
        assertEquals("Avenue", m.best()!!.roadName)
        assertTrue(m.pOffRoad() < 0.05)
    }

    /** A road along the simulated drive's truth path, so the pipeline has something to match. */
    private fun roadAlongTruth(): RoadNetwork {
        val t = TestSupport.defaultDrive.truth.filterIndexed { i, _ -> i % 20 == 0 }
        return RoadNetwork.build(listOf(OsmWay(1, LongArray(t.size) { it + 1L }, t.map { it.lat }.toDoubleArray(),
            t.map { it.lon }.toDoubleArray(), "primary", false, 4, 0, "Sim")))
    }

    @Test
    fun `the pipeline with roads is deterministic and reports the road`() {
        val roads = roadAlongTruth()
        fun run(): List<PositionEstimate> {
            val out = ArrayList<PositionEstimate>()
            val p = MeasurementPipeline(PipelineConfig(reorderWindowNs = 0), DefaultTrustEvaluator(), BaselineDrEstimator(roads = { roads }))
            p.listener = object : PipelineListener { override fun onEstimate(e: PositionEstimate) { out += e } }
            TestSupport.measurements().forEach(p::emit)
            p.flush()
            return out
        }
        val a = run(); val b = run()
        assertEquals(a.map { DriveJson.encode(it) }, b.map { DriveJson.encode(it) })
        assertTrue(a.count { it.road?.roadName == "Sim" } > a.size / 2, "road reported on most ticks")
    }
}
