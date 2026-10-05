package gpes.core

import gpes.core.TestSupport.fix
import gpes.core.model.Cov2
import gpes.core.model.EstimatorMode
import gpes.core.model.LocSource
import gpes.core.model.PositionEstimate
import gpes.core.model.ProviderEvent
import gpes.core.model.TrustReason
import gpes.core.model.TrustState
import gpes.core.trust.DefaultTrustEvaluator
import gpes.core.trust.TrustContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * D-086. Drive 20261005-104833 (Pixel 8, mock `gps` for the whole drive): Google Fused returned our own estimate as
 * non-mock `fused` fixes (all 763 at 0.0 m from it); 81 were TRUSTED once our accuracy got small, and counted as
 * "recent trusted GNSS" for other checks.
 */
class FusedEchoTest {
    private val lat0 = 50.40
    private val lon0 = 30.50
    private fun north(m: Double) = lat0 + m / 111_195.0
    private fun fused(tS: Double, northM: Double) =
        fix(tS, north(northM), lon0, acc = 20.0, speed = 10.0, bearing = 0.0, source = LocSource.FUSED)

    /** Our estimate at the same place as the echoed fix. */
    private fun ctx(tS: Double, northM: Double) = TrustContext(
        PositionEstimate((tS * 1e9).toLong(), "test", north(northM), lon0, Cov2(400.0, 0.0, 400.0),
            mode = EstimatorMode.DEAD_RECKONING, confidence = 0.5),
        null,
    )

    @Test
    fun `fused fixes while we replace gps are our own output, not evidence`() {
        val ev = DefaultTrustEvaluator()
        ev.observe(ProviderEvent((0.5e9).toLong(), "gps", ProviderEvent.Kind.OVERRIDDEN))
        for (t in 1..60) {
            val a = ev.assess(fused(t.toDouble(), 10.0 * t), ctx(t.toDouble(), 10.0 * t))
            assertEquals(TrustState.UNAVAILABLE, a.state, "t=$t ${a.reasons}")
            assertEquals(setOf(TrustReason.ECHO_OF_OUR_OUTPUT), a.reasons)
        }
        assertEquals(TrustState.UNAVAILABLE, ev.sourceState((60.5e9).toLong(), LocSource.FUSED))

        // Given back for a probe window: Fused may still return the old mock for a few seconds.
        ev.observe(ProviderEvent((60.5e9).toLong(), "gps", ProviderEvent.Kind.RESTORED))
        assertEquals(TrustState.UNAVAILABLE, ev.assess(fused(65.0, 650.0), ctx(65.0, 650.0)).state)
        // Afterwards Fused is assessed again, with no echoed fixes in its history.
        val later = (72..80).map { t -> ev.assess(fused(t.toDouble(), 10.0 * t), ctx(t.toDouble(), 10.0 * t)) }
        assertTrue(later.all { it.state == TrustState.TRUSTED }, later.map { it.state to it.reasons }.toString())
        assertEquals(TrustState.TRUSTED, ev.sourceState((80.5e9).toLong(), LocSource.FUSED))
    }
}
