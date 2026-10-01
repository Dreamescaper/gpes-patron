package gpes.core

import gpes.core.TestSupport.fix
import gpes.core.model.Cov2
import gpes.core.model.EstimatorMode
import gpes.core.model.PositionEstimate
import gpes.core.model.TrustReason
import gpes.core.model.TrustState
import gpes.core.trust.DefaultTrustEvaluator
import gpes.core.trust.TrustContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** D-038: a consistent questionable (innovation-gate-only) GNSS stream is accepted only after an outage. */
class QuestionableResetTest {
    private val lat0 = 50.40
    private val lon0 = 30.50
    private fun north(m: Double) = lat0 + m / 111_195.0

    /** The estimator predicts 110 m south of the truth with σ 25 m: NIS ≈ 19, between the 13.8 and 50 gates. */
    private fun ctx(tS: Double, truthNorthM: Double) = TrustContext(
        PositionEstimate((tS * 1e9).toLong(), "test", north(truthNorthM - 110), lon0, Cov2(625.0, 0.0, 625.0),
            mode = EstimatorMode.DEAD_RECKONING, confidence = 0.5),
        null,
    )

    private fun gnss(tS: Double, northM: Double) = fix(tS, north(northM), lon0, acc = 5.0, speed = 10.0, bearing = 0.0)

    @Test
    fun `returning GNSS after an outage is accepted after a consistent questionable stream`() {
        val ev = DefaultTrustEvaluator()
        for (t in 0..10) ev.assess(gnss(t.toDouble(), 10.0 * t), TrustContext(null, null))
        // 60-s outage, then GNSS returns while the estimator's prediction is 110 m off.
        val states = (70..85).map { t -> ev.assess(gnss(t.toDouble(), 10.0 * t), ctx(t.toDouble(), 10.0 * t)) }
        assertEquals(TrustState.QUESTIONABLE, states.first().state)
        val accepted = states.indexOfFirst { it.state == TrustState.TRUSTED }
        assertTrue(accepted in 9..12, "accepted after ${accepted} s: ${states.map { it.state }}")
        assertTrue(TrustReason.RESET_AFTER_CONSISTENT_STREAM in states[accepted].reasons)
    }

    @Test
    fun `the same disagreement without an outage stays questionable`() {
        val ev = DefaultTrustEvaluator()
        for (t in 0..10) ev.assess(gnss(t.toDouble(), 10.0 * t), TrustContext(null, null))
        val states = (11..40).map { t -> ev.assess(gnss(t.toDouble(), 10.0 * t), ctx(t.toDouble(), 10.0 * t)) }
        assertTrue(states.none { it.state == TrustState.TRUSTED }, states.map { it.state }.toString())
    }
}
