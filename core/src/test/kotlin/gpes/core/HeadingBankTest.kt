package gpes.core

import gpes.core.estimator.BaselineConfig
import gpes.core.estimator.CompassConfig
import gpes.core.estimator.HeadingBank
import gpes.core.estimator.HeadingBankConfig
import gpes.core.geo.Geo
import gpes.core.geo.LocalFrame
import gpes.core.model.EstimatorMode
import gpes.core.model.LocSource
import gpes.core.replay.Scenario
import gpes.core.replay.ScenarioStep
import gpes.core.replay.Variant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class HeadingBankTest {

    @Test
    fun `bank finds the heading of a straight drive from noisy coarse fixes`() {
        val bank = HeadingBank(HeadingBankConfig())
        val frame = LocalFrame(50.4, 30.5)
        val rnd = Random(3)
        val trueBearing = Math.toRadians(70.0)
        val sigma = 40.0
        var t = 0.0
        while (t <= 150.0) {
            if (t > 0) bank.propagate(0.05, 0.0, 12.0, 0.1)
            if ((t * 20).toInt() % (13 * 20) == 0) {
                val d = 12.0 * t
                val ll = frame.toLatLon(d * kotlin.math.sin(trueBearing) + rnd.nextDouble(-1.0, 1.0) * sigma, d * kotlin.math.cos(trueBearing) + rnd.nextDouble(-1.0, 1.0) * sigma)
                bank.onFix(ll.lat, ll.lon, sigma * sigma)
            }
            t += 0.05
        }
        assertTrue(bank.converged(), "not converged: ${bank.heading()}")
        val (h, std) = bank.heading()!!
        assertEquals(0.0, Math.toDegrees(Geo.wrapRad(h - trueBearing)), 10.0)
        assertTrue(Math.toDegrees(std) < 15.0)
    }

    private val absent = Scenario("absent", steps = listOf(ScenarioStep.DropSource(LocSource.GNSS, 0.0)))
    private val coarse = listOf(
        ScenarioStep.DropSource(LocSource.NETWORK, dropRawGnss = false),
        ScenarioStep.SyntheticNetwork(40.0, 13.0),
    )
    private val obd = ScenarioStep.SyntheticVehicleSpeed(0.3, scaleError = 0.01)
    // Accelerometer speed off: these tests are about the bank with OBD or with no speed source at all (D-050
    // gives a speed without OBD; that path is covered by AccelSpeedTest and the real drives, R-026).
    private fun cfg(bank: Boolean) = BaselineConfig(
        compass = CompassConfig(enabled = false), headingBank = HeadingBankConfig(enabled = bank),
        accelSpeed = gpes.core.estimator.AccelSpeedConfig(enabled = false),
    )

    @Test
    fun `without GNSS and compass the bank turns coarse fixes plus OBD into dead reckoning`() {
        val (_, off) = TestSupport.run(absent, Variant("off", extraSteps = coarse + obd, baseline = cfg(false)))
        val (r, on) = TestSupport.run(absent, Variant("on", extraSteps = coarse + obd, baseline = cfg(true)))
        assertTrue(on.p95M!! < off.p95M!! * 0.7, "bank p95 ${on.p95M} vs off ${off.p95M}")
        assertTrue(r.estimates.any { it.mode == EstimatorMode.DEAD_RECKONING }, "never left COARSE_ONLY")
        assertTrue(on.within95!! > 0.6, "calibration within95 ${on.within95}")
    }

    @Test
    fun `without a speed source the bank does no harm`() {
        // The heading is observable without speed (fixes line up along the direction of travel), so
        // the bank may hand it over; the speed stays unknown, so the result must not get worse.
        val (_, off) = TestSupport.run(absent, Variant("nospeed-off", extraSteps = coarse, baseline = cfg(false)))
        val (_, on) = TestSupport.run(absent, Variant("nospeed-on", extraSteps = coarse, baseline = cfg(true)))
        println("no speed: off p95 ${off.p95M} w95 ${off.within95}; on p95 ${on.p95M} w95 ${on.within95}")
        assertTrue(on.p95M!! <= off.p95M!! * 1.1, "bank p95 ${on.p95M} vs off ${off.p95M}")
        assertTrue(on.within95!! >= off.within95!! - 0.1, "calibration ${on.within95} vs ${off.within95}")
    }
}
