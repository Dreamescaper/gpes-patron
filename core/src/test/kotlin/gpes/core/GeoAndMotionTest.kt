package gpes.core

import gpes.core.geo.Geo
import gpes.core.geo.LocalFrame
import gpes.core.model.Measurement
import gpes.core.motion.MotionTracker
import gpes.core.sim.DriveSimulator
import gpes.core.sim.Leg
import gpes.core.sim.SimConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeoAndMotionTest {
    @Test
    fun `local frame round trip and distance agree with haversine`() {
        val f = LocalFrame(50.45, 30.52)
        val ll = f.toLatLon(3000.0, -4000.0)
        val back = f.toEnu(ll.lat, ll.lon)
        assertEquals(3000.0, back.e, 1e-6)
        assertEquals(-4000.0, back.n, 1e-6)
        assertEquals(5000.0, Geo.haversineM(50.45, 30.52, ll.lat, ll.lon), 10.0)
    }

    @Test
    fun `kyiv to lima is about 12000 km`() {
        val d = Geo.haversineM(50.45, 30.52, -12.05, -77.04)
        assertTrue(d in 11_500_000.0..12_500_000.0, "d=$d")
    }

    @Test
    fun `gyro projected on gravity measures a 90 degree right turn regardless of tilted mount`() {
        val drive = DriveSimulator.generate(
            SimConfig(legs = listOf(Leg.Stop(5.0), Leg.Straight(5.0, 10.0), Leg.Turn(90.0, 8.0), Leg.Straight(5.0, 10.0)), gyroBiasZ = 0.0),
        )
        val tracker = MotionTracker()
        drive.records.filterIsInstance<Measurement>().forEach { tracker.onMeasurement(it) }
        val t0 = drive.truth.first().tNs + 6_000_000_000
        val t1 = drive.truth.last().tNs - 1_000_000_000
        val turn = Math.toDegrees(tracker.bearingChange(t0, t1)!!)
        assertEquals(90.0, turn, 4.0)
    }

    @Test
    fun `stationary detection`() {
        val drive = DriveSimulator.generate(SimConfig(legs = listOf(Leg.Stop(5.0), Leg.Straight(10.0, 10.0))))
        val tracker = MotionTracker()
        var stillAt3 = false
        var movingAt12 = false
        val t0 = drive.truth.first().tNs
        drive.records.filterIsInstance<Measurement>().forEach { m ->
            val u = tracker.onMeasurement(m) ?: return@forEach
            val rel = (u.tNs - t0) / 1e9
            if (rel in 3.0..3.1) stillAt3 = stillAt3 || u.stationary
            if (rel in 12.0..12.1) movingAt12 = movingAt12 || !u.stationary
        }
        assertTrue(stillAt3)
        assertTrue(movingAt12)
    }
}
