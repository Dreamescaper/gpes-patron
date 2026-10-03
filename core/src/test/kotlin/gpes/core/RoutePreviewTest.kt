package gpes.core

import gpes.core.geo.Geo
import gpes.core.geo.RoutePoint
import gpes.core.geo.RoutePreview
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Route preview of a recording (D-064). Routes are built from an arbitrary origin; nothing here is a real trace. */
class RoutePreviewTest {
    private val lat0 = 50.0
    private val lon0 = 30.0

    /** A route from [lat0], [lon0] along [legs] (bearing degrees, metres), one point every [stepM] metres, one second apart at 10 m/s. */
    private fun route(vararg legs: Pair<Double, Double>, stepM: Double = 10.0, jitterM: Double = 0.0): List<RoutePoint> {
        val out = ArrayList<RoutePoint>()
        var lat = lat0; var lon = lon0; var t = 0L
        out += RoutePoint(t, lat, lon)
        for ((bearing, length) in legs) {
            var done = 0.0
            while (done < length) {
                val s = minOf(stepM, length - done)
                val p = Geo.destination(lat, lon, s, bearing)
                lat = p.lat; lon = p.lon; done += s; t += (s / 10.0 * 1e9).toLong()
                val jitter = if (jitterM == 0.0) 0.0 else if (out.size % 2 == 0) jitterM else -jitterM
                val q = Geo.destination(lat, lon, kotlin.math.abs(jitter), if (jitter >= 0) bearing + 90 else bearing - 90)
                out += RoutePoint(t, q.lat, q.lon)
            }
        }
        return out
    }

    @Test
    fun `a straight line collapses to its two ends`() {
        val r = route(90.0 to 1000.0)
        val s = RoutePreview.simplify(r, 3.0)
        assertEquals(2, s.size)
        assertEquals(r.first(), s.first())
        assertEquals(r.last(), s.last())
    }

    @Test
    fun `a corner is kept`() {
        val r = route(0.0 to 500.0, 90.0 to 500.0)
        val s = RoutePreview.simplify(r, 3.0)
        assertEquals(3, s.size)
        // The middle point is the corner, 500 m from the start.
        assertEquals(500.0, Geo.haversineM(lat0, lon0, s[1].lat, s[1].lon), 15.0)
    }

    @Test
    fun `one metre of jitter is removed but a thirty metre detour stays`() {
        val jittery = route(90.0 to 1000.0, jitterM = 1.0)
        assertEquals(2, RoutePreview.simplify(jittery, 5.0).size)
        val detour = route(90.0 to 400.0, 0.0 to 30.0, 180.0 to 30.0, 90.0 to 400.0)
        assertTrue(RoutePreview.simplify(detour, 5.0).size >= 5)
    }

    @Test
    fun `the length of two legs is their sum`() {
        val r = route(0.0 to 100.0, 90.0 to 100.0)
        assertEquals(200.0, RoutePreview.lengthM(r), 2.0)
        assertEquals(20.0, RoutePreview.durationS(r), 0.5)
    }

    @Test
    fun `simplifyTo caps the number of points and keeps the ends`() {
        val r = route(0.0 to 300.0, 90.0 to 300.0, 180.0 to 300.0, 270.0 to 300.0, 0.0 to 150.0, stepM = 2.0, jitterM = 0.5)
        val s = RoutePreview.simplifyTo(r, 8)
        assertTrue(s.size <= 8, "was ${s.size}")
        assertEquals(r.first(), s.first())
        assertEquals(r.last(), s.last())
    }

    @Test
    fun `fewer than three points come back as they are`() {
        assertEquals(emptyList<RoutePoint>(), RoutePreview.simplify(emptyList(), 3.0))
        val two = route(0.0 to 10.0)
        assertEquals(two, RoutePreview.simplify(two, 3.0))
        assertEquals(0.0, RoutePreview.lengthM(emptyList()), 0.0)
    }
}
