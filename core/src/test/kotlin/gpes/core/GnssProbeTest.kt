package gpes.core

import gpes.core.model.GnssStatusSnapshot
import gpes.core.model.LocSource
import gpes.core.model.SatInfo
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustReason
import gpes.core.model.TrustState
import gpes.core.trust.GnssProbeConfig
import gpes.core.trust.GnssProbeController
import gpes.core.trust.ProbeAction
import gpes.core.trust.ProbePhase
import gpes.core.trust.ProbeResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * D-052: when to give the real `gps` provider back for a moment while we replace it (Waze reads `gps`). The
 * situations are the ones the policy was designed for (healthy chip, spoofer-like uniform signals, chip with a fix
 * but no Location, rejected and ambiguous fixes), built in miniature; none comes from a recording.
 */
class GnssProbeTest {
    private val cfg = GnssProbeConfig()
    private fun s(t: Double) = (t * 1e9).toLong()

    private fun status(t: Double, used: Int = 8, cn0: (Int) -> Double = { 25.0 + it * 2.5 }) =
        GnssStatusSnapshot(s(t), List(used) { SatInfo(it + 1, 1, cn0(it), 30.0 + it, 10.0 * it, usedInFix = true) })

    private fun assessment(t: Double, state: TrustState, vararg reasons: TrustReason) =
        TrustAssessment(s(t), LocSource.GNSS, "gps", state, 1.0, reasons.toSet())

    /** Healthy statuses once a second for [seconds]; returns the first OPEN time, if any. */
    private fun run(c: GnssProbeController, from: Double, seconds: Int, feed: (Double) -> GnssStatusSnapshot = { status(it) }): Double? {
        for (i in 0..seconds) {
            val t = from + i
            c.onStatus(feed(t))
            if (c.step(s(t)) == ProbeAction.OPEN) return t
        }
        return null
    }

    @Test
    fun `opens only after the chip looked healthy for a while`() {
        val c = GnssProbeController(cfg)
        val t = run(c, 0.0, 30)
        assertEquals(cfg.healthyForS, t)
        assertEquals(ProbePhase.WINDOW, c.phase)
    }

    @Test
    fun `uniform signal levels (spoofer-like) never open a window`() {
        val c = GnssProbeController(cfg)
        assertNull(run(c, 0.0, 120) { status(it, cn0 = { 38.0 + (it % 2) * 0.3 }) })
    }

    @Test
    fun `too few satellites in the fix never open a window`() {
        val c = GnssProbeController(cfg)
        assertNull(run(c, 0.0, 120) { status(it, used = 3) })
    }

    @Test
    fun `stale status does not count as healthy`() {
        val c = GnssProbeController(cfg)
        c.onStatus(status(0.0))
        // No new status: the chip went silent. Ticks alone must not open the window.
        for (i in 1..30) assertEquals(ProbeAction.NONE, c.step(s(i.toDouble())))
    }

    @Test
    fun `no real fix soon after opening closes the window and doubles the wait`() {
        val c = GnssProbeController(cfg)
        val open = run(c, 0.0, 30)!!
        var closeAt: Double? = null
        for (i in 1..10) {
            val t = open + i
            c.onStatus(status(t))
            if (c.step(s(t)) == ProbeAction.CLOSE) { closeAt = t; break }
        }
        assertEquals(open + cfg.noFixAbortS, closeAt)
        assertEquals(ProbeResult.FAILED, c.lastResult)
        // Healthy again: the next window waits twice the base interval.
        val next = run(c, closeAt!! + 1, 300)!!
        assertEquals(closeAt + 2 * cfg.intervalS, next)
    }

    @Test
    fun `a rejected fix for a hard reason closes at once`() {
        val c = GnssProbeController(cfg)
        val open = run(c, 0.0, 30)!!
        c.onAssessment(assessment(open + 1, TrustState.REJECTED, TrustReason.IMPOSSIBLE_VELOCITY))
        assertEquals(ProbeAction.CLOSE, c.step(s(open + 1)))
        assertEquals(ProbeResult.FAILED, c.lastResult)
    }

    @Test
    fun `disagreeing with our estimate is ambiguous and keeps the window open until the window limit`() {
        val c = GnssProbeController(cfg)
        val open = run(c, 0.0, 30)!!
        var closeAt: Double? = null
        for (i in 1..40) {
            val t = open + i
            c.onAssessment(assessment(t, TrustState.REJECTED, TrustReason.INNOVATION_GATE))
            if (c.step(s(t)) == ProbeAction.CLOSE) { closeAt = t; break }
        }
        assertEquals(open + cfg.windowMaxS, closeAt)
        assertEquals(ProbeResult.FAILED, c.lastResult)
    }

    @Test
    fun `a returning stream that trust accepts closes the window as recovered and keeps the base interval`() {
        val c = GnssProbeController(cfg)
        val open = run(c, 0.0, 30)!!
        // First fixes disagree with our drifted estimate, then trust resets onto the consistent stream.
        for (i in 1..9) c.onAssessment(assessment(open + i, TrustState.QUESTIONABLE, TrustReason.INNOVATION_GATE))
        assertEquals(ProbeAction.NONE, c.step(s(open + 9)))
        c.onAssessment(assessment(open + 10, TrustState.TRUSTED, TrustReason.RESET_AFTER_CONSISTENT_STREAM))
        assertEquals(ProbeAction.CLOSE, c.step(s(open + 10)))
        assertEquals(ProbeResult.RECOVERED, c.lastResult)
        val next = run(c, open + 11, 300)!!
        assertEquals(open + 10 + cfg.intervalS, next)
    }

    @Test
    fun `assessments of fixes from before the window or of our own output are ignored`() {
        val c = GnssProbeController(cfg)
        val open = run(c, 0.0, 30)!!
        c.onAssessment(assessment(open - 1, TrustState.TRUSTED))
        c.onAssessment(assessment(open + 1, TrustState.UNAVAILABLE, TrustReason.SOURCE_OVERRIDDEN))
        c.onAssessment(assessment(open + 1, TrustState.REJECTED, TrustReason.SYNTHETIC_INPUT))
        assertEquals(ProbeAction.NONE, c.step(s(open + 1)))
        assertEquals(ProbePhase.WINDOW, c.phase)
    }

    @Test
    fun `status reports the wait and the last result`() {
        val c = GnssProbeController(cfg)
        c.onStatus(status(0.0))
        assertEquals(cfg.healthyForS, c.status(s(0.0)).nextProbeInS)
        c.onStatus(status(3.0, used = 2))
        assertNull(c.status(s(3.0)).nextProbeInS)
        assertFalse(c.status(s(3.0)).health!!.healthy)
        assertNotNull(c.status(s(3.0)).health!!.meanCn0DbHz)
    }
}
