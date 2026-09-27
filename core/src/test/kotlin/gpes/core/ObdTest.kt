package gpes.core

import gpes.core.estimator.BaselineConfig
import gpes.core.model.LocSource
import gpes.core.model.ObdExchange
import gpes.core.obd.Elm327
import gpes.core.obd.ElmTransport
import gpes.core.obd.PollResult
import gpes.core.replay.Scenario
import gpes.core.replay.ScenarioStep
import gpes.core.replay.Variant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Scripted ELM327 clone. Each command advances a fake clock by [latencyNs]. */
private class FakeElm(
    private val supportsCount: Boolean = true,
    private val speedKmh: () -> Int? = { 42 },
    private val dead: Boolean = false,
) : ElmTransport {
    var now = 1_000_000_000L
    val latencyNs = 120_000_000L
    private var echo = true
    private var last = ""
    override fun write(command: String) { last = command; now += latencyNs / 2 }
    override fun readUntilPrompt(timeoutMs: Long): String? {
        now += latencyNs / 2
        if (dead) return null
        val body = when (last) {
            "ATZ" -> "\r\rELM327 v1.5\r"
            "ATE0" -> { echo = false; "OK" }
            "ATAT2" -> "?"
            "0100" -> "SEARCHING...\r41 00 BE 3F A8 13"
            "ATDPN" -> "A6"
            "010D1" -> if (!supportsCount) "?" else speedKmh()?.let { "41 0D %02X".format(it) } ?: "NO DATA"
            "010D" -> speedKmh()?.let { "41 0D %02X".format(it) } ?: "NO DATA"
            else -> "OK"
        }
        return (if (echo) "$last\r" else "") + body + "\r\r"
    }
}

class ObdTest {
    @Test
    fun `init and poll against a v1_5 clone, timestamps at request midpoint`() {
        val fake = FakeElm()
        val log = ArrayList<ObdExchange>()
        val elm = Elm327(fake, { fake.now }, { log += it })
        val info = elm.init()
        assertEquals("ELM327 v1.5", info.version)
        assertEquals("A6", info.protocol)
        assertTrue(info.countSuffix)
        val before = fake.now
        val r = elm.pollSpeed() as PollResult.Speed
        assertEquals(42 / 3.6, r.m.speedMps, 1e-9)
        assertEquals(before + fake.latencyNs / 2, r.m.tNs) // midpoint
        assertTrue(log.any { it.request == "ATZ" } && log.any { it.request == "010D1" })
    }

    @Test
    fun `clone without response-count suffix falls back to plain 010D`() {
        val fake = FakeElm(supportsCount = false)
        val elm = Elm327(fake, { fake.now })
        assertFalse(elm.init().countSuffix)
        assertTrue(elm.pollSpeed() is PollResult.Speed)
    }

    @Test
    fun `no data and dead adapter are reported, not thrown`() {
        val fake = FakeElm(speedKmh = { null })
        val elm = Elm327(fake, { fake.now })
        runCatching { elm.init() } // 010D1 probe gets NO DATA → count suffix disabled; init still succeeds
        assertTrue(elm.pollSpeed() is PollResult.NoData)
        val dead = Elm327(FakeElm(dead = true), { 0L })
        assertTrue(runCatching { dead.init() }.isFailure)
        assertEquals(PollResult.Timeout, dead.pollSpeed())
    }

    @Test
    fun `parses headers and spaces`() {
        assertEquals(0x37, Elm327.parseSpeedKmh("7E8 03 41 0D 37"))
        assertEquals(0x37, Elm327.parseSpeedKmh("410D37"))
        assertEquals(null, Elm327.parseSpeedKmh("NO DATA"))
    }

    private val realisticObd = ScenarioStep.SyntheticVehicleSpeed(sigmaMps = 0.3, periodS = 0.2, scaleError = 0.04, quantizeKmh = true, latencyS = 0.15)

    @Test
    fun `speedometer scale is learned from GNSS and keeps a 5-minute outage tight`() {
        val drop = Scenario("drop5min", steps = listOf(ScenarioStep.DropSource(LocSource.GNSS, 150.0, 300.0)))
        val (_, learn) = TestSupport.run(drop, Variant("learn", extraSteps = listOf(realisticObd)))
        val (_, fixed) = TestSupport.run(
            drop, Variant("fixed", extraSteps = listOf(realisticObd), baseline = BaselineConfig(speedScaleInitialStd = 1e-6, speedScaleRandomWalk = 0.0)),
        )
        val wl = learn.windows.single()
        val wf = fixed.windows.single()
        assertTrue(wl.maxErrM!! < 20.0, "with scale learning max ${wl.maxErrM}")
        assertTrue(wl.maxErrM!! < wf.maxErrM!! / 3, "learn ${wl.maxErrM} vs fixed ${wf.maxErrM}")
        assertTrue(learn.within95!! > 0.9)
    }

    @Test
    fun `OBD speed exposes a Doppler-consistent spoofer`() {
        val spoof = Scenario("drift", steps = listOf(ScenarioStep.Drift(120.0, 300.0, 2.0, 0.0, consistentVelocity = true)))
        val (_, without) = TestSupport.run(spoof, Variant("noObd"))
        val (_, with) = TestSupport.run(spoof, Variant("obd", extraSteps = listOf(realisticObd)))
        assertTrue(with.trust.missedDetectionRate!! < 0.5 * without.trust.missedDetectionRate!!,
            "missed with OBD ${with.trust.missedDetectionRate} vs without ${without.trust.missedDetectionRate}")
        val (_, clean) = TestSupport.run(Scenario("clean"), Variant("obd", extraSteps = listOf(realisticObd)))
        assertTrue(clean.trust.falseRejectionRate!! < 0.02)
    }
}
