package gpes.core

import gpes.core.TestSupport.fix
import gpes.core.model.Cov2
import gpes.core.model.EstimatorMode
import gpes.core.model.LocSource
import gpes.core.model.PositionEstimate
import gpes.core.model.ProviderEvent
import gpes.core.model.VehicleSpeedMeasurement
import gpes.core.model.TrustReason
import gpes.core.model.TrustState
import gpes.core.trust.DefaultTrustEvaluator
import gpes.core.trust.TrustConfig
import gpes.core.trust.TrustContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * D-069. Drive 20261003-140822 (Pixel 8, mock `gps`, GNSS probe windows): real fixes 5 m accurate, consistent
 * with their own velocity, were QUESTIONABLE for ~10 s after each window opened, because our mock track
 * (hundreds of metres from the real position) sat in the GNSS source's history; and fixes 35–180 m from a
 * drifted estimate were held back by the innovation gate.
 */
class AgreementTrustTest {
    private val lat0 = 50.40
    private val lon0 = 30.50
    private fun north(m: Double) = lat0 + m / 111_195.0
    private fun gnss(tS: Double, northM: Double) = fix(tS, north(northM), lon0, acc = 5.0, speed = 10.0, bearing = 0.0)
    private fun mock(tS: Double, northM: Double) =
        fix(tS, north(northM), lon0, acc = 100.0, speed = 10.0, bearing = 0.0).copy(isMock = true)

    /** The estimator predicts [offM] south of the real position with σ 20 m (NIS 22 at 95 m: QUESTIONABLE). */
    private fun ctx(tS: Double, truthNorthM: Double, offM: Double) = TrustContext(
        PositionEstimate((tS * 1e9).toLong(), "test", north(truthNorthM - offM), lon0, Cov2(400.0, 0.0, 400.0),
            mode = EstimatorMode.DEAD_RECKONING, confidence = 0.5),
        null,
    )

    @Test
    fun `our own mock track does not make the first real fixes of a probe window mismatch`() {
        val ev = DefaultTrustEvaluator()
        for (t in 0..10) ev.assess(gnss(t.toDouble(), 10.0 * t), TrustContext(null, null))
        ev.observe(ProviderEvent((10.5e9).toLong(), "gps", ProviderEvent.Kind.OVERRIDDEN))
        // 50 s of our mock output, 300 m ahead of the real car, hAcc 100 m.
        for (t in 11..59) {
            val a = ev.assess(mock(t.toDouble(), 10.0 * t + 300.0), TrustContext(null, null))
            assertEquals(TrustState.UNAVAILABLE, a.state)
        }
        ev.observe(ProviderEvent((59.5e9).toLong(), "gps", ProviderEvent.Kind.RESTORED))
        val states = (61..75).map { t -> ev.assess(gnss(t.toDouble(), 10.0 * t), TrustContext(null, null)) }
        assertTrue(states.all { it.state == TrustState.TRUSTED }, states.map { it.state to it.reasons }.toString())
    }

    @Test
    fun `a fix near the estimate is trusted despite the innovation gate`() {
        val ev = DefaultTrustEvaluator()
        for (t in 0..10) ev.assess(gnss(t.toDouble(), 10.0 * t), TrustContext(null, null))
        val a = ev.assess(gnss(11.0, 110.0), ctx(11.0, 110.0, 95.0))
        assertEquals(TrustState.TRUSTED, a.state)
        assertTrue(TrustReason.AGREES_WITH_ESTIMATE in a.reasons)
        // Without the rule the same fix is held back by the innovation gate.
        val off = DefaultTrustEvaluator(TrustConfig(agreeWithEstimateM = null))
        for (t in 0..10) off.assess(gnss(t.toDouble(), 10.0 * t), TrustContext(null, null))
        val b = off.assess(gnss(11.0, 110.0), ctx(11.0, 110.0, 95.0))
        assertEquals(TrustState.QUESTIONABLE, b.state)
        assertTrue(TrustReason.INNOVATION_GATE in b.reasons)
    }

    @Test
    fun `a fix farther than the radius from the estimate is still gated`() {
        val ev = DefaultTrustEvaluator()
        for (t in 0..10) ev.assess(gnss(t.toDouble(), 10.0 * t), TrustContext(null, null))
        val a = ev.assess(gnss(11.0, 110.0), ctx(11.0, 110.0, 130.0))
        assertTrue(a.state != TrustState.TRUSTED, a.toString())
        assertTrue(TrustReason.INNOVATION_GATE in a.reasons)
    }

    @Test
    fun `a fix near the estimate skips the recovery count after a rejection`() {
        val ev = DefaultTrustEvaluator()
        for (t in 0..10) ev.assess(gnss(t.toDouble(), 10.0 * t), TrustContext(null, null))
        // One impossible fix 200 km away: REJECTED, and the next five clean fixes would be RECOVERING.
        val bad = ev.assess(gnss(11.0, 200_000.0), TrustContext(null, null))
        assertEquals(TrustState.REJECTED, bad.state)
        val a = ev.assess(gnss(12.0, 120.0), ctx(12.0, 120.0, 10.0))
        assertEquals(TrustState.TRUSTED, a.state, a.toString())
        val off = DefaultTrustEvaluator(TrustConfig(agreeWithEstimateM = null))
        for (t in 0..10) off.assess(gnss(t.toDouble(), 10.0 * t), TrustContext(null, null))
        off.assess(gnss(11.0, 200_000.0), TrustContext(null, null))
        val b = off.assess(gnss(12.0, 120.0), ctx(12.0, 120.0, 10.0))
        assertTrue(TrustReason.RECOVERING in b.reasons)
    }

    @Test
    fun `agreement with the estimate never overrides a network fix in another country`() {
        val ev = DefaultTrustEvaluator()
        // Network says Kyiv; the (spoofed) GNSS says 80 km away, and our estimate has followed it.
        ev.assess(fix(0.0, lat0, lon0, acc = 500.0, source = LocSource.NETWORK), TrustContext(null, null))
        val a = ev.assess(gnss(1.0, 80_000.0), ctx(1.0, 80_000.0, 10.0))
        assertEquals(TrustState.REJECTED, a.state, a.toString())
        assertTrue(TrustReason.GEOGRAPHICALLY_IMPOSSIBLE in a.reasons)
    }

    @Test
    fun `with fresh OBD speed the strict innovation gate stays (R-002 drift defence)`() {
        val ev = DefaultTrustEvaluator()
        for (t in 0..10) ev.assess(gnss(t.toDouble(), 10.0 * t), TrustContext(null, null))
        ev.observe(VehicleSpeedMeasurement((10.9e9).toLong(), 10.0, 0.3, "obd"))
        val a = ev.assess(gnss(11.0, 110.0), ctx(11.0, 110.0, 95.0))
        assertEquals(TrustState.QUESTIONABLE, a.state, a.toString())
        assertTrue(TrustReason.INNOVATION_GATE in a.reasons)
    }
}
