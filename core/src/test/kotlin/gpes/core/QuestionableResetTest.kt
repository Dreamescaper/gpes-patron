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

    /**
     * Drive 20261003-140822, probe window at 1781 s: GPS back after an outage, the estimate ~400 m off (NIS 19–27, gate
     * only). A wrong network fix made 4 fixes NETWORK_DISAGREEMENT, which restarted the questionable stream, and the
     * restarted stream no longer counted as "after an outage": real GPS stayed QUESTIONABLE for the whole window.
     */
    @Test
    fun `a stream interrupted by a wrong network fix keeps its outage status`() {
        val ev = DefaultTrustEvaluator()
        for (t in 0..10) ev.assess(gnss(t.toDouble(), 10.0 * t), TrustContext(null, null))
        val states = (70..110).map { t ->
            // A network fix 400 m to the east of the car (hAcc 40 m) at 74 s: disagrees with GNSS for ~10 s.
            if (t == 74) ev.assess(fix(73.5, north(735.0), lon0 + 400.0 / 71_000.0, acc = 40.0, speed = null, bearing = null,
                source = gpes.core.model.LocSource.NETWORK), ctx(73.5, 735.0))
            ev.assess(gnss(t.toDouble(), 10.0 * t), ctx(t.toDouble(), 10.0 * t))
        }
        val disagreed = states.indexOfLast { TrustReason.NETWORK_DISAGREEMENT in it.reasons }
        assertTrue(disagreed in 3..15, "network disagreement until ${disagreed}: ${states.map { it.reasons }}")
        val accepted = states.indexOfFirst { it.state == TrustState.TRUSTED }
        assertTrue(accepted in disagreed + 10..disagreed + 12, "accepted at $accepted, disagreement until $disagreed: ${states.map { it.state }}")
    }

    /**
     * D-071. Drive 20261003-140822, probe window at 1347 s: GPS back after 63 s, 4.8 m hAcc, the dead-reckoned estimate
     * 1272 m away (claimed ±240 m, NIS 70). It was REJECTED, which needs 120 s of stream; the window lasts 20 s.
     */
    @Test
    fun `GNSS returning 1 km from a drifted estimate is accepted after 10 s, but not 6 km away`() {
        fun predicted(tS: Double, truthNorthM: Double, offM: Double) = TrustContext(
            PositionEstimate((tS * 1e9).toLong(), "test", north(truthNorthM - offM), lon0, Cov2(150.0 * 150.0, 0.0, 150.0 * 150.0),
                mode = EstimatorMode.DEAD_RECKONING, confidence = 0.5),
            null,
        )
        for ((off, accepted) in listOf(1272.0 to true, 6000.0 to false)) {
            val ev = DefaultTrustEvaluator()
            for (t in 0..10) ev.assess(gnss(t.toDouble(), 10.0 * t), TrustContext(null, null))
            val states = (73..95).map { t -> ev.assess(gnss(t.toDouble(), 10.0 * t), predicted(t.toDouble(), 10.0 * t, off)) }
            assertEquals(accepted, states.any { it.state == TrustState.TRUSTED }, "off $off: ${states.map { it.state }}")
            if (accepted) assertEquals(TrustState.QUESTIONABLE, states.first().state)
            else assertEquals(TrustState.REJECTED, states.first().state)
        }
    }
}
