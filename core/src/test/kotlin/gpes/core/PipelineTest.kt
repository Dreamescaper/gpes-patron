package gpes.core

import gpes.core.estimator.BaselineDrEstimator
import gpes.core.estimator.PositionEstimator
import gpes.core.io.DriveJson
import gpes.core.model.LocationMeasurement
import gpes.core.model.Measurement
import gpes.core.model.PositionEstimate
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustState
import gpes.core.motion.MotionUpdate
import gpes.core.pipeline.MeasurementPipeline
import gpes.core.pipeline.PipelineConfig
import gpes.core.pipeline.PipelineListener
import gpes.core.replay.Metrics
import gpes.core.replay.Scenario
import gpes.core.trust.DefaultTrustEvaluator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.StringReader
import java.io.StringWriter

class PipelineTest {
    private fun runPipeline(ms: List<Measurement>, cfg: PipelineConfig = PipelineConfig(reorderWindowNs = 0)): Pair<MeasurementPipeline, List<PositionEstimate>> {
        val out = ArrayList<PositionEstimate>()
        val p = MeasurementPipeline(cfg, DefaultTrustEvaluator(), BaselineDrEstimator())
        p.listener = object : PipelineListener { override fun onEstimate(e: PositionEstimate) { out += e } }
        ms.forEach(p::emit)
        p.flush()
        return p to out
    }

    @Test
    fun `replay is deterministic`() {
        val ms = TestSupport.measurements()
        val a = runPipeline(ms).second.map { DriveJson.encode(it) }
        val b = runPipeline(ms).second.map { DriveJson.encode(it) }
        assertEquals(a, b)
    }

    @Test
    fun `reorder buffer makes delivery order irrelevant for late location fixes`() {
        val ms = TestSupport.measurements()
        // Deliver each location fix 300 ms late (as on a real phone), measurement timestamps unchanged.
        val delayed = ms.map { m -> (if (m is LocationMeasurement) m.tNs + 300_000_000 else m.tNs) to m }
            .sortedBy { it.first }.map { it.second }
        val a = runPipeline(ms).second.map { DriveJson.encode(it) }
        val (p, bList) = runPipeline(delayed, PipelineConfig(reorderWindowNs = 500_000_000))
        assertEquals(a, bList.map { DriveJson.encode(it) })
        assertEquals(0, p.stats.late)
    }

    @Test
    fun `rollback with identity transform reproduces the same estimates`() {
        val ms = TestSupport.measurements()
        val (p, original) = runPipeline(ms)
        val from = ms.last().tNs - 120_000_000_000
        val res = requireNotNull(p.rollbackAndReplay(from))
        val originalAfter = original.filter { it.tNs >= res.estimates.first().tNs }.map { DriveJson.encode(it) }
        // The final tick may be emitted only on flush; compare the overlapping prefix.
        assertEquals(originalAfter.take(res.estimates.size), res.estimates.map { DriveJson.encode(it) })
    }

    @Test
    fun `synthetic (mock) locations never reach the estimator as usable evidence`() {
        val seen = ArrayList<Pair<LocationMeasurement, TrustAssessment?>>()
        val spy = object : PositionEstimator {
            override val name = "spy"
            override fun onMeasurement(m: Measurement, trust: TrustAssessment?) { if (m is LocationMeasurement) seen += m to trust }
            override fun onMotion(u: MotionUpdate) = Unit
            override fun estimate(tNs: Long): PositionEstimate? = null
            override fun snapshot(): Any = Unit
            override fun restore(snapshot: Any) = Unit
        }
        val p = MeasurementPipeline(PipelineConfig(reorderWindowNs = 0), DefaultTrustEvaluator(), spy)
        val real = TestSupport.fix(1.0, 50.0, 30.0)
        p.emit(real)
        p.emit(real.copy(tNs = 2_000_000_000, isMock = true))
        p.emit(real.copy(tNs = 3_000_000_000, extras = mapOf(LocationMeasurement.SYNTHETIC_EXTRA to "1")))
        p.flush()
        val synthetic = seen.filter { it.first.isSynthetic }
        assertEquals(2, synthetic.size)
        assertFalse(synthetic.any { it.second?.state == TrustState.TRUSTED || it.second?.state == TrustState.QUESTIONABLE })
    }

    @Test
    fun `jsonl round trip preserves records`() {
        val recs = TestSupport.defaultDrive.records.take(2000)
        val w = StringWriter()
        DriveJson.write(recs.asSequence(), w)
        val back = DriveJson.read(BufferedReader(StringReader(w.toString()))).toList()
        assertEquals(recs, back)
    }

    @Test
    fun `metrics error vs time bins`() {
        val (r, _) = TestSupport.run(Scenario("x", steps = listOf(gpes.core.replay.ScenarioStep.DropSource(gpes.core.model.LocSource.GNSS, 100.0, 60.0))))
        val bins = Metrics.errorVsTimeSinceDegraded(Metrics.rows(r))
        assertEquals(6, bins.size)
    }
}
