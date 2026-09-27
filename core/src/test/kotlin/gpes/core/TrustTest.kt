package gpes.core

import gpes.core.TestSupport.fix
import gpes.core.model.TrustReason
import gpes.core.model.TrustState
import gpes.core.replay.Scenario
import gpes.core.replay.ScenarioStep
import gpes.core.trust.DefaultTrustEvaluator
import gpes.core.trust.TrustConfig
import gpes.core.trust.TrustContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TrustTest {
    private val noCtx = TrustContext(null, null)

    @Test
    fun `kyiv to lima in one second is rejected`() {
        val ev = DefaultTrustEvaluator()
        assertEquals(TrustState.TRUSTED, ev.assess(fix(0.0, 50.4501, 30.5234), noCtx).state)
        assertEquals(TrustState.TRUSTED, ev.assess(fix(1.0, 50.4501, 30.5238), noCtx).state)
        val a = ev.assess(fix(2.0, -12.0464, -77.0428), noCtx)
        assertEquals(TrustState.REJECTED, a.state)
        assertTrue(TrustReason.IMPOSSIBLE_VELOCITY in a.reasons)
    }

    @Test
    fun `mock or synthetic input is always rejected`() {
        val ev = DefaultTrustEvaluator()
        assertEquals(TrustState.REJECTED, ev.assess(fix(0.0, 50.0, 30.0).copy(isMock = true), noCtx).state)
        val tagged = fix(1.0, 50.0, 30.0).copy(extras = mapOf("gpes.synthetic" to "1"))
        assertEquals(TrustState.REJECTED, ev.assess(tagged, noCtx).state)
    }

    @Test
    fun `recovery requires consecutive clean fixes`() {
        val ev = DefaultTrustEvaluator(TrustConfig(recoveryConsecutive = 3, resetAfterConsistentS = null))
        ev.assess(fix(0.0, 50.0, 30.0, speed = 0.0, bearing = null), noCtx)
        assertEquals(TrustState.REJECTED, ev.assess(fix(1.0, 51.0, 30.0, speed = 0.0, bearing = null), noCtx).state)
        val states = (2..5).map { ev.assess(fix(it.toDouble(), 50.0, 30.0, speed = 0.0, bearing = null), noCtx).state }
        assertEquals(listOf(TrustState.QUESTIONABLE, TrustState.QUESTIONABLE, TrustState.QUESTIONABLE, TrustState.TRUSTED), states)
    }

    @Test
    fun `clean simulated drive is essentially never rejected`() {
        val (_, s) = TestSupport.run(Scenario("clean"))
        assertTrue(s.trust.cleanFixes > 400, "clean=${s.trust.cleanFixes}")
        assertTrue(s.trust.falseRejectionRate!! < 0.02, "false rejection rate ${s.trust.falseRejectionRate}")
    }

    @Test
    fun `5 km jump is rejected immediately and throughout`() {
        val (_, s) = TestSupport.run(Scenario("jump", steps = listOf(ScenarioStep.Offset(200.0, 60.0, 5000.0, 0.0))))
        assertTrue(s.trust.perturbedFixes >= 55)
        assertTrue(s.trust.missedDetectionRate!! < 0.05, "missed ${s.trust.missedDetectionRate}")
        assertTrue(s.trust.detectionLatencyS.single()!! <= 1.0)
    }

    @Test
    fun `jump to another country is rejected`() {
        val (_, s) = TestSupport.run(Scenario("country", steps = listOf(ScenarioStep.Teleport(200.0, 120.0, -12.0464, -77.0428))))
        assertEquals(0, s.trust.missedDetections)
    }

    @Test
    fun `gradual drift is flagged within bounded time`() {
        val (_, s) = TestSupport.run(Scenario("drift", steps = listOf(ScenarioStep.Drift(150.0, 180.0, rateMps = 5.0, bearingDeg = 0.0))))
        val latency = s.trust.detectionLatencyS.single()
        assertNotNull(latency)
        assertTrue(latency!! < 30.0, "latency $latency")
        assertTrue(s.trust.missedDetectionRate!! < 0.2, "missed ${s.trust.missedDetectionRate}")
    }
}
