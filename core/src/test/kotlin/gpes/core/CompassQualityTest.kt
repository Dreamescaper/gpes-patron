package gpes.core

import gpes.core.estimator.Compass
import gpes.core.estimator.CompassConfig
import gpes.core.estimator.CompassMode
import gpes.core.estimator.CompassQuality
import gpes.core.estimator.CompassVerdict
import gpes.core.geo.Geo
import gpes.core.model.GeomagneticReference
import gpes.core.model.ImuSample
import gpes.core.model.Measurement
import gpes.core.model.PowerState
import gpes.core.motion.MotionTracker
import gpes.core.replay.Metrics
import gpes.core.replay.TruthTrack
import gpes.core.sim.DriveSimulator
import gpes.core.sim.SimConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/** The compass must say when a holder makes the magnetometer useless, and keep working when it can be calibrated. */
class CompassQualityTest {
    private data class Result(val quality: CompassQuality, val alignedP95: Double?, val readings: Int)

    private fun run(cfg: SimConfig, cc: CompassConfig = CompassConfig()): Result {
        val d = DriveSimulator.generate(cfg)
        val truth = TruthTrack(d.truth)
        val tracker = MotionTracker()
        val c = Compass(cc)
        val half = d.truth[d.truth.size / 2].tNs
        var biasSum = 0.0; var biasN = 0; var next = half
        val errs = ArrayList<Double>()
        var readings = 0
        for (m in d.records.filterIsInstance<Measurement>()) {
            when (m) {
                is ImuSample -> c.onMag(m)
                is GeomagneticReference -> c.onReference(m)
                is PowerState -> c.onPower(m)
                else -> Unit
            }
            val u = tracker.onMeasurement(m) ?: continue
            if (u.stationary) { biasSum += u.yawRateUp; biasN++ }
            c.onMotion(u, if (biasN > 0) biasSum / biasN else 0.0)
            val t = truth.at(u.tNs) ?: continue
            if (u.tNs < half && t.speedMps > 7) c.addCalibration(u.tNs, Math.toRadians(t.bearingDeg), t.speedMps, u.yawRateUp)
            if (u.tNs >= next && t.speedMps > 3) {
                next = u.tNs + 1_000_000_000
                c.heading(u.tNs)?.let {
                    readings++
                    if (it.mode == CompassMode.GNSS_ALIGNED) errs += abs(Geo.wrapDeg(Math.toDegrees(it.bearingRad) - t.bearingDeg))
                }
            }
        }
        return Result(c.quality(), Metrics.pct(errs, 0.95), readings)
    }

    @Test
    fun `clean mount is usable`() {
        val r = run(SimConfig())
        assertEquals(CompassVerdict.USABLE, r.quality.verdict, r.quality.toString())
        assertTrue(r.quality.radiusRatio!! in 0.8..1.25)
    }

    @Test
    fun `magnetic holder is calibrated out but flagged`() {
        val clean = run(SimConfig())
        val magnet = run(SimConfig(phoneHardIronUt = listOf(250.0, -180.0, 300.0)))
        assertEquals(CompassVerdict.MARGINAL, magnet.quality.verdict)
        assertTrue("LARGE_HARD_IRON" in magnet.quality.reasons)
        assertTrue(magnet.quality.hardIronUt!! > 200)
        assertTrue(magnet.alignedP95!! < clean.alignedP95!! + 3.0, "magnet ${magnet.alignedP95} vs clean ${clean.alignedP95}")
    }

    @Test
    fun `saturated magnetometer is unusable and silent`() {
        val r = run(SimConfig(phoneHardIronUt = listOf(985.0, 0.0, 0.0), magClipUt = 1000.0))
        assertEquals(CompassVerdict.UNUSABLE, r.quality.verdict)
        assertTrue("SATURATED" in r.quality.reasons)
        assertEquals(0, r.readings)
    }

    @Test
    fun `shielding plate is detected as too weak a field`() {
        val r = run(SimConfig(carSoftIronDiag = listOf(0.2, 0.2, 1.0), carSoftIronXy = 0.0))
        assertEquals(CompassVerdict.UNUSABLE, r.quality.verdict)
        assertTrue("WEAK_FIELD" in r.quality.reasons)
    }

    @Test
    fun `wireless charging holder is flagged`() {
        val r = run(SimConfig(wirelessChargingUt = 60.0))
        assertTrue(r.quality.verdict == CompassVerdict.MARGINAL || r.quality.verdict == CompassVerdict.UNUSABLE)
        assertTrue("WIRELESS_CHARGING" in r.quality.reasons)
    }

    @Test
    fun `shake gate helps on strong wobble and tolerates moderate wobble with a rotation vector`() {
        val strong = SimConfig(mountWobbleDeg = 8.0, mountWobbleHz = 3.0, mountWobbleOnS = 20.0, mountWobbleOffS = 40.0)
        val noGate = CompassConfig(maxTiltRateRms = 1e9, maxTiltRateRmsWithOrientation = 1e9)
        val gated = run(strong)
        val ungated = run(strong, noGate)
        assertTrue(gated.alignedP95!! < ungated.alignedP95!!, "gated ${gated.alignedP95} vs ungated ${ungated.alignedP95}")
        assertTrue("SHAKY_MOUNT" in gated.quality.reasons)
        // Moderate continuous wobble: GRV keeps "up" right, so the compass keeps working.
        val moderate = run(SimConfig(mountWobbleDeg = 3.0, mountWobbleHz = 3.0))
        assertTrue(moderate.readings > 100 && moderate.quality.verdict != CompassVerdict.UNUSABLE, moderate.quality.toString())
    }
}
