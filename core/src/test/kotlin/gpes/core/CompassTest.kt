package gpes.core

import gpes.core.estimator.BaselineConfig
import gpes.core.estimator.Compass
import gpes.core.estimator.CompassConfig
import gpes.core.estimator.CompassMode
import gpes.core.sim.Leg
import gpes.core.geo.Geo
import gpes.core.model.GeomagneticReference
import gpes.core.model.ImuKind
import gpes.core.model.ImuSample
import gpes.core.model.LocSource
import gpes.core.model.Measurement
import gpes.core.motion.MotionTracker
import gpes.core.motion.Vec3
import gpes.core.replay.Scenario
import gpes.core.replay.ScenarioStep
import gpes.core.replay.Variant
import gpes.core.sim.DriveSimulator
import gpes.core.sim.SimConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.acos

class CompassTest {
    private val drive = TestSupport.defaultDrive

    /** A drive that covers every heading (two full loops of right turns), so the ellipse fit is possible. */
    private val loopDrive by lazy {
        DriveSimulator.generate(
            SimConfig(legs = listOf(Leg.Stop(10.0)) + List(8) { listOf(Leg.Straight(40.0, 13.0), Leg.Turn(90.0, 8.0)) }.flatten() + listOf(Leg.Straight(60.0, 13.0), Leg.Stop(10.0))),
        )
    }

    @Test
    fun `forward axis is learned from turns for a tilted mount`() {
        val cfg = SimConfig()
        val tracker = MotionTracker()
        drive.records.filterIsInstance<Measurement>().forEach { tracker.onMeasurement(it) }
        val r = DriveSimulator.mountRotation(cfg)
        val expected = Vec3(r[0][1], r[1][1], r[2][1]) // R · (0,1,0)
        val f = requireNotNull(tracker.forward(drive.truth.last().tNs)) { "forward not learned" }
        val angle = Math.toDegrees(acos((f dot expected).coerceIn(-1.0, 1.0)))
        assertTrue(angle < 8.0, "forward axis error $angle°")
    }

    @Test
    fun `re-mounting the phone is detected`() {
        val tracker = MotionTracker()
        var t = 1_000_000_000L
        repeat(500) { // 5 s still, gravity on z
            tracker.onMeasurement(ImuSample(t, ImuKind.ACCEL, 0.0, 0.0, 9.81)); tracker.onMeasurement(ImuSample(t, ImuKind.GYRO, 0.0, 0.0, 0.0)); t += 10_000_000
        }
        val before = tracker.latest!!.mountEpoch
        repeat(100) { // 1 s rotating about phone x at 1 rad/s (a tilt, not a yaw)
            tracker.onMeasurement(ImuSample(t, ImuKind.ACCEL, 0.0, 0.0, 9.81)); tracker.onMeasurement(ImuSample(t, ImuKind.GYRO, 1.0, 0.0, 0.0)); t += 10_000_000
        }
        assertEquals(before + 1, tracker.latest!!.mountEpoch)
    }

    /** Runs tracker + compass over the sim drive, calibrating from truth course during the first half. */
    private data class Err(val absDeg: Double, val sigmaDeg: Double, val mode: CompassMode)

    private fun compassErrors(calibrate: Boolean, d: gpes.core.sim.SimDrive = drive): List<Err> {
        val drive = d
        val truth = gpes.core.replay.TruthTrack(d.truth)
        val tracker = MotionTracker()
        val compass = Compass(CompassConfig())
        val half = drive.truth[drive.truth.size / 2].tNs
        val errs = ArrayList<Err>()
        var nextCheck = half
        var lastYaw = 0.0
        var biasSum = 0.0
        var biasN = 0
        for (m in drive.records.filterIsInstance<Measurement>()) {
            if (m is ImuSample) compass.onMag(m)
            if (m is GeomagneticReference) compass.onReference(m)
            val u = tracker.onMeasurement(m) ?: continue
            if (u.stationary) { biasSum += u.yawRateUp; biasN++ }
            compass.onMotion(u, if (biasN > 0) biasSum / biasN else 0.0); lastYaw = u.yawRateUp
            val tr = truth.at(u.tNs) ?: continue
            if (calibrate && u.tNs < half && tr.speedMps > 7) compass.addCalibration(u.tNs, Math.toRadians(tr.bearingDeg), tr.speedMps, lastYaw)
            if (u.tNs >= nextCheck && tr.speedMps > 3) {
                nextCheck = u.tNs + 1_000_000_000
                compass.heading(u.tNs)?.let { errs += Err(abs(Geo.wrapDeg(Math.toDegrees(it.bearingRad) - tr.bearingDeg)), Math.toDegrees(it.sigmaRad), it.mode) }
            }
        }
        return errs
    }

    @Test
    fun `calibrated compass is honest on a short drive and accurate with full heading coverage`() {
        // Short city drive: only part of the heading circle is seen, so soft iron stays unmodelled.
        val short = compassErrors(calibrate = true)
        assertTrue(short.size > 100, "readings ${short.size}")
        val aligned = short.count { it.mode == CompassMode.GNSS_ALIGNED }.toDouble() / short.size
        assertTrue(aligned > 0.8, "GNSS-aligned fraction $aligned")
        val p95 = gpes.core.replay.Metrics.pct(short.filter { it.mode == CompassMode.GNSS_ALIGNED }.map { it.absDeg }, 0.95)!!
        assertTrue(p95 < 12.0, "short-drive p95 $p95°")
        val within2Sigma = short.count { it.absDeg <= 2 * it.sigmaDeg }.toDouble() / short.size
        assertTrue(within2Sigma > 0.9, "only $within2Sigma of errors within 2σ")
        // Full coverage: the ellipse fit removes hard and soft iron.
        val loop = compassErrors(calibrate = true, d = loopDrive)
        val loopP95 = gpes.core.replay.Metrics.pct(loop.map { it.absDeg }, 0.95)!!
        assertTrue(loopP95 < 9.0, "full-coverage p95 $loopP95° (with magnetic anomalies)")
    }

    @Test
    fun `uncalibrated compass gives a rough but useful heading`() {
        val errs = compassErrors(calibrate = false).map { it.absDeg }
        assertTrue(errs.size > 50, "readings ${errs.size}")
        val p50 = gpes.core.replay.Metrics.pct(errs, 0.5)!!
        assertTrue(p50 < 20.0, "uncalibrated p50 heading error $p50°")
    }

    @Test
    fun `compass helps a start without GNSS when speed is known`() {
        val sc = Scenario("absent", steps = listOf(ScenarioStep.DropSource(LocSource.GNSS, 0.0)))
        val extra = listOf(
            ScenarioStep.DropSource(LocSource.NETWORK, dropRawGnss = false),
            ScenarioStep.SyntheticNetwork(500.0, 20.0),
            ScenarioStep.SyntheticVehicleSpeed(0.3, scaleError = 0.01),
        )
        val (_, off) = TestSupport.run(sc, Variant("off", extraSteps = extra, baseline = BaselineConfig(compass = CompassConfig(enabled = false))))
        val (_, on) = TestSupport.run(sc, Variant("on", extraSteps = extra))
        assertTrue(on.p95M!! < off.p95M!! * 0.8, "compass on p95 ${on.p95M} vs off ${off.p95M}")
        assertTrue(on.within95!! > 0.8, "calibration within95 ${on.within95}")
    }
}
