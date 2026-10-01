package gpes.core

import gpes.core.geo.Geo
import gpes.core.model.RoadState
import gpes.core.road.OverpassImport
import gpes.core.road.RoadNetwork
import gpes.core.road.RoadTile
import gpes.core.road.RoadTileCodec
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class RoadNetworkTest {

    private val lat0 = 50.40
    private val lon0 = 30.50
    private val mLat = 1 / 111_195.0
    private val mLon = 1 / (111_195.0 * kotlin.math.cos(Math.toRadians(lat0)))

    /** Node [id] at [e] m east, [n] m north of the origin. */
    private fun node(id: Long, e: Double, n: Double) = """{"type":"node","id":$id,"lat":${lat0 + n * mLat},"lon":${lon0 + e * mLon}}"""
    private fun way(id: Long, nodes: List<Long>, tags: String) = """{"type":"way","id":$id,"nodes":${nodes.joinToString(",", "[", "]")},"tags":{$tags}}"""
    private fun overpass(vararg el: String) = """{"elements":[${el.joinToString(",")}]}"""

    /**
     * A north-south avenue (1–2–3, two-way) crossed at node 2 by an east-west one-way street (4–2–5),
     * a bridge (6–7) over the avenue with no shared node, and a parking aisle and a footway that must
     * be ignored.
     */
    private val grid = overpass(
        node(1, 0.0, -300.0), node(2, 0.0, 0.0), node(3, 0.0, 300.0),
        node(4, -300.0, 0.0), node(5, 300.0, 0.0),
        node(6, -200.0, 150.0), node(7, 200.0, 150.0),
        node(8, 50.0, -50.0), node(9, 100.0, -50.0),
        way(100, listOf(1, 2, 3), """"highway":"primary","name":"Avenue","lanes":"4""""),
        way(200, listOf(4, 2, 5), """"highway":"residential","oneway":"yes""""),
        way(300, listOf(6, 7), """"highway":"secondary","bridge":"yes","layer":"1""""),
        way(400, listOf(8, 9), """"highway":"service","service":"parking_aisle""""),
        way(500, listOf(8, 9), """"highway":"footway""""),
    )

    @Test
    fun `ways are split at shared nodes and linked respecting one-way`() {
        val net = RoadNetwork.build(OverpassImport.parse(grid))
        assertEquals(5, net.size, "avenue 2 + street 2 + bridge 1; aisle and footway dropped")
        val south = net.segments.single { it.osmWayId == 100L && it.startNode == 1L }
        val north = net.segments.single { it.osmWayId == 100L && it.startNode == 2L }
        val west = net.segments.single { it.osmWayId == 200L && it.startNode == 4L }
        val east = net.segments.single { it.osmWayId == 200L && it.startNode == 2L }
        val bridge = net.segments.single { it.osmWayId == 300L }
        assertEquals(300.0, south.lengthM, 0.5)
        assertEquals(4, south.lanes)
        // Driving north on the avenue to node 2: continue north or turn east; west is one-way towards 2.
        assertEquals(setOf(north.id, east.id), south.successorsFromEnd.toSet())
        // Coming from the west on the one-way street: north, back south (two-way avenue), or east.
        assertEquals(setOf(south.id, north.id, east.id), west.successorsFromEnd.toSet())
        assertTrue(west.successorsFromStart.isEmpty(), "one-way: cannot leave through its start")
        assertEquals(1, bridge.layer)
        assertTrue(bridge.successorsFromEnd.isEmpty(), "the bridge does not connect to the avenue it crosses")
    }

    @Test
    fun `projection gives distance along, signed cross-track and bearing`() {
        val net = RoadNetwork.build(OverpassImport.parse(grid))
        // 20 m east of the avenue, 100 m north of the junction.
        val near = net.project(lat0 + 100 * mLat, lon0 + 20 * mLon, 30.0)
        val p = near.first()
        assertEquals(100L, p.segment.osmWayId)
        assertEquals(20.0, p.distanceM, 0.3)
        assertEquals(0.0, p.bearingDeg, 0.5)
        assertEquals(20.0, p.crossTrackM, 0.3, "east of a north-bound road is to its right")
        assertEquals(100.0, p.distanceAlongM, 0.5)
        val back = net.toLatLon(RoadState(p.segment.id, p.distanceAlongM, true))
        assertEquals(0.0, Geo.haversineM(back.lat, back.lon, p.point.lat, p.point.lon), 0.1)
        assertFalse(near.any { it.segment.osmWayId == 200L }, "the cross street is 100 m away, beyond 30 m")
        assertEquals(90.0, net.bearingAt(net.segments.single { it.startNode == 4L }.id, 10.0), 0.5)
    }

    @Test
    fun `a reverse one-way way is stored in its driving direction`() {
        val ways = OverpassImport.parse(overpass(node(1, 0.0, 0.0), node(2, 0.0, 100.0), way(1, listOf(1, 2), """"highway":"tertiary","oneway":"-1"""")))
        val w = ways.single()
        assertTrue(w.oneway)
        assertEquals(listOf(2L, 1L), w.nodeIds.toList())
    }

    @Test
    fun `tiles merge by way id and join at shared nodes, independent of order`() {
        val all = OverpassImport.parse(grid)
        // Tile A has the avenue and the street; tile B has the street again and the bridge.
        val a = all.filter { it.id == 100L || it.id == 200L }
        val b = all.filter { it.id == 200L || it.id == 300L }
        val n1 = RoadNetwork.build(a + b)
        val n2 = RoadNetwork.build(b + a)
        assertEquals(5, n1.size)
        assertEquals(n1.segments, n2.segments)
    }

    @Test
    fun `rebuilding from toWays keeps the topology, and a shift moves the geometry`() {
        val net = RoadNetwork.build(OverpassImport.parse(grid))
        val again = RoadNetwork.build(net.toWays())
        assertEquals(net.size, again.size)
        assertEquals(net.segments.map { it.successorsFromEnd.size }, again.segments.map { it.successorsFromEnd.size })
        val shifted = RoadNetwork.build(net.toWays { gpes.core.geo.LatLon(it.lat, it.lon + 10 * mLon) })
        val p = shifted.project(lat0 + 100 * mLat, lon0, 30.0).first()
        assertEquals(10.0, p.distanceM, 0.3)
        assertEquals(-10.0, p.crossTrackM, 0.3, "the road moved 10 m east, so we are 10 m to its left")
    }

    @Test
    fun `tile codec round-trips to 1e-7 degrees`() {
        val ways = OverpassImport.parse(grid)
        val buf = ByteArrayOutputStream().also { RoadTileCodec.write(ways, it) }
        val back = RoadTileCodec.read(ByteArrayInputStream(buf.toByteArray()))
        assertEquals(ways.map { it.id }, back.map { it.id })
        for ((x, y) in ways.zip(back)) {
            assertEquals(x.nodeIds.toList(), y.nodeIds.toList())
            assertEquals(x.name, y.name); assertEquals(x.oneway, y.oneway); assertEquals(x.lanes, y.lanes)
            for (i in x.lats.indices) { assertEquals(x.lats[i], y.lats[i], 1e-7); assertEquals(x.lons[i], y.lons[i], 1e-7) }
        }
        assertEquals(RoadNetwork.build(ways).size, RoadNetwork.build(back).size)
    }

    @Test
    fun `tiles around a point cover the radius, nearest first`() {
        val tiles = RoadTile.around(50.45, 30.52, 8000.0)
        assertEquals(RoadTile.of(50.45, 30.52), tiles.first())
        for (t in tiles) assertTrue(t.south < 50.45 + 0.08 && t.north > 50.45 - 0.08)
        assertTrue(tiles.size in 9..16, "${tiles.size}")
        val q = RoadTile.of(50.45, 30.52).overpassQuery()
        assertTrue(q.startsWith("[out:json]") && q.endsWith("out body;>;out skel qt;") && "residential" in q, q)
    }
}
