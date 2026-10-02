package gpes.core

import gpes.core.geo.Geo
import gpes.core.geo.MapView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MapViewTest {
    // An arbitrary origin; nothing here comes from a recording.
    private val lat0 = 50.0
    private val lon0 = 30.0

    @Test
    fun `north is up and east is right`() {
        val v = MapView(lat0, lon0, 2.0)
        val north = Geo.destination(lat0, lon0, 100.0, 0.0)
        val east = Geo.destination(lat0, lon0, 100.0, 90.0)
        // Geo.destination is on a sphere and MapView on the ellipsoid, hence the 0.5 px (1 m) allowance.
        assertEquals(-50.0, v.y(north.lat, north.lon), 0.5)
        assertEquals(0.0, v.x(north.lat, north.lon), 0.5)
        assertEquals(50.0, v.x(east.lat, east.lon), 0.5)
        assertEquals(0.0, v.y(east.lat, east.lon), 0.5)
    }

    @Test
    fun `the middle of the screen is the centre`() {
        val v = MapView(lat0, lon0, 3.0)
        assertEquals(0.0, v.x(lat0, lon0), 1e-9)
        assertEquals(0.0, v.y(lat0, lon0), 1e-9)
    }

    @Test
    fun `a pixel offset converts back to the same point`() {
        val v = MapView(lat0, lon0, 1.5)
        val p = v.toLatLon(120.0, -80.0)
        assertEquals(120.0, v.x(p.lat, p.lon), 1e-6)
        assertEquals(-80.0, v.y(p.lat, p.lon), 1e-6)
    }

    @Test
    fun `distances on screen match metres on the ground`() {
        val v = MapView(lat0, lon0, 0.5)
        val p = Geo.destination(lat0, lon0, 300.0, 37.0)
        val px = Math.hypot(v.x(p.lat, p.lon), v.y(p.lat, p.lon))
        assertEquals(300.0 / 0.5, px, 5.0) // sphere vs ellipsoid, about 0.3 %
    }

    @Test
    fun `the scale bar is a round number that fits`() {
        assertEquals(100.0, MapView.niceScaleM(2.0, 80.0), 1e-9)   // 160 m available
        assertEquals(500.0, MapView.niceScaleM(7.0, 80.0), 1e-9)   // 560 m
        assertEquals(1000.0, MapView.niceScaleM(15.0, 80.0), 1e-9) // 1200 m
        assertEquals(50.0, MapView.niceScaleM(0.8, 80.0), 1e-9)    // 64 m
        assertTrue(MapView.niceScaleM(1.234, 70.0) <= 1.234 * 70.0)
    }
}
