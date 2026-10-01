package gpes.core.road

import gpes.core.future.RoadGraph
import gpes.core.future.RoadSegment
import gpes.core.geo.Geo
import gpes.core.geo.LatLon
import gpes.core.geo.LocalFrame
import gpes.core.model.RoadState
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** The closest point of one segment to a query point. */
data class RoadProjection(
    val segment: RoadSegment,
    /** Along the segment geometry from its first point (m). */
    val distanceAlongM: Double,
    /** Signed offset of the query point: positive to the right of the segment's forward direction (m). */
    val crossTrackM: Double,
    /** Distance from the query point to the road (m), = |crossTrack| except beyond the segment ends. */
    val distanceM: Double,
    /** Forward bearing of the segment at the projected point (degrees clockwise from north). */
    val bearingDeg: Double,
    val point: LatLon,
)

/**
 * The road network: OSM ways split into segments at intersections (nodes shared by two or more ways,
 * possibly from different tiles), with connectivity and a grid index. Built from [OsmWay]s; the result
 * depends only on the set of ways, not on their order (D-041).
 *
 * Geometry is kept in a local tangent plane at the centre of the data, which is accurate to ~1 m within
 * ±20 km (`LocalFrame`); tiles around the current location stay well within that.
 */
class RoadNetwork private constructor(
    private val segs: List<RoadSegment>,
    private val frame: LocalFrame,
) : RoadGraph {

    private val xs: List<DoubleArray>
    private val ys: List<DoubleArray>
    /** Cumulative distance at each vertex. */
    private val cum: List<DoubleArray>
    private val cells = HashMap<Long, MutableList<Int>>()

    init {
        xs = ArrayList(segs.size); ys = ArrayList(segs.size); cum = ArrayList(segs.size)
        for ((i, s) in segs.withIndex()) {
            val x = DoubleArray(s.geometry.size); val y = DoubleArray(s.geometry.size); val c = DoubleArray(s.geometry.size)
            for ((k, p) in s.geometry.withIndex()) {
                val e = frame.toEnu(p.lat, p.lon); x[k] = e.e; y[k] = e.n
                if (k > 0) c[k] = c[k - 1] + hypot(x[k] - x[k - 1], y[k] - y[k - 1])
            }
            xs += x; ys += y; cum += c
            val x0 = floor(x.min() / CELL_M).toInt(); val x1 = floor(x.max() / CELL_M).toInt()
            val y0 = floor(y.min() / CELL_M).toInt(); val y1 = floor(y.max() / CELL_M).toInt()
            for (cx in x0..x1) for (cy in y0..y1) cells.getOrPut(key(cx, cy)) { ArrayList() } += i
        }
    }

    val size: Int get() = segs.size
    val segments: List<RoadSegment> get() = segs

    override fun segment(id: Long): RoadSegment? = segs.getOrNull(id.toInt())

    override fun segmentsWithin(center: LatLon, radiusM: Double): List<RoadSegment> =
        project(center.lat, center.lon, radiusM).map { it.segment }

    /** The closest point of every segment within [radiusM] of the point, nearest first. */
    fun project(lat: Double, lon: Double, radiusM: Double): List<RoadProjection> {
        val q = frame.toEnu(lat, lon)
        val out = ArrayList<RoadProjection>()
        val seen = HashSet<Int>()
        val cx0 = floor((q.e - radiusM) / CELL_M).toInt(); val cx1 = floor((q.e + radiusM) / CELL_M).toInt()
        val cy0 = floor((q.n - radiusM) / CELL_M).toInt(); val cy1 = floor((q.n + radiusM) / CELL_M).toInt()
        for (cx in cx0..cx1) for (cy in cy0..cy1) {
            for (i in cells[key(cx, cy)] ?: continue) {
                if (!seen.add(i)) continue
                val p = projectOnto(i, q.e, q.n)
                if (p.distanceM <= radiusM) out += p
            }
        }
        out.sortWith(compareBy<RoadProjection> { it.distanceM }.thenBy { it.segment.id })
        return out
    }

    /** Projection of a point onto one segment (for evaluation: is the truth on the matched road?). */
    fun projectOnto(segmentId: Long, lat: Double, lon: Double): RoadProjection {
        val q = frame.toEnu(lat, lon)
        return projectOnto(segmentId.toInt(), q.e, q.n)
    }

    /** Length of the segment in the network's plane (m); distances along use the same metric. */
    fun lengthOf(segmentId: Long): Double = cum[segmentId.toInt()].last()

    /** Forward bearing (degrees) of [segmentId] at [distanceAlongM]. */
    fun bearingAt(segmentId: Long, distanceAlongM: Double): Double {
        val i = segmentId.toInt(); val c = cum[i]
        val k = piece(c, distanceAlongM)
        return bearing(xs[i][k + 1] - xs[i][k], ys[i][k + 1] - ys[i][k])
    }

    override fun toLatLon(state: RoadState): LatLon {
        val i = state.segmentId.toInt(); val c = cum[i]
        val d = state.distanceAlongM.coerceIn(0.0, c.last())
        val k = piece(c, d)
        val len = c[k + 1] - c[k]
        val f = if (len > 0) (d - c[k]) / len else 0.0
        return frame.toLatLon(xs[i][k] + f * (xs[i][k + 1] - xs[i][k]), ys[i][k] + f * (ys[i][k + 1] - ys[i][k]))
    }

    private fun projectOnto(i: Int, qe: Double, qn: Double): RoadProjection {
        val x = xs[i]; val y = ys[i]; val c = cum[i]
        var best = Double.MAX_VALUE; var bestK = 0; var bestT = 0.0
        for (k in 0 until x.size - 1) {
            val dx = x[k + 1] - x[k]; val dy = y[k + 1] - y[k]
            val l2 = dx * dx + dy * dy
            val t = if (l2 > 0) (((qe - x[k]) * dx + (qn - y[k]) * dy) / l2).coerceIn(0.0, 1.0) else 0.0
            val px = x[k] + t * dx; val py = y[k] + t * dy
            val d = hypot(qe - px, qn - py)
            if (d < best) { best = d; bestK = k; bestT = t }
        }
        val dx = x[bestK + 1] - x[bestK]; val dy = y[bestK + 1] - y[bestK]
        val px = x[bestK] + bestT * dx; val py = y[bestK] + bestT * dy
        val len = sqrt(dx * dx + dy * dy)
        // Right of forward = cross(forward, query − point) < 0 in an east-north frame.
        val cross = if (len > 0) -(dx * (qn - py) - dy * (qe - px)) / len else 0.0
        return RoadProjection(
            segs[i], c[bestK] + bestT * len, cross, best, bearing(dx, dy), frame.toLatLon(px, py),
        )
    }

    private fun piece(c: DoubleArray, d: Double): Int {
        var k = 0
        while (k < c.size - 2 && c[k + 1] < d) k++
        return k
    }

    private fun bearing(dx: Double, dy: Double) = (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0

    /**
     * The network as ways again (one per segment), for edits: rebuilding from them gives the same
     * topology, since every junction is a segment end. Inner points get synthetic negative node ids.
     */
    fun toWays(transform: (LatLon) -> LatLon = { it }): List<OsmWay> = segs.map { s ->
        val n = s.geometry.size
        val ids = LongArray(n) { k -> when (k) { 0 -> s.startNode; n - 1 -> s.endNode; else -> -(s.id * 10_000 + k) - 1 } }
        val g = s.geometry.map(transform)
        OsmWay(s.id + 1, ids, DoubleArray(n) { g[it].lat }, DoubleArray(n) { g[it].lon }, s.roadClass, s.oneway, s.lanes, s.layer, s.name)
    }

    companion object {
        const val CELL_M = 200.0

        private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)

        /**
         * Splits ways at shared nodes into segments and links them. Duplicate ways (the same way from
         * two tiles) are merged by id. Segment ids are 0 … n−1 in (way id, position) order.
         */
        fun build(ways: Collection<OsmWay>): RoadNetwork {
            val unique = ways.associateBy { it.id }.values.sortedBy { it.id }
            require(unique.isNotEmpty()) { "no roads" }
            var minLat = Double.MAX_VALUE; var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE; var maxLon = -Double.MAX_VALUE
            val uses = HashMap<Long, Int>()
            for (w in unique) {
                for (i in w.nodeIds.indices) {
                    minLat = min(minLat, w.lats[i]); maxLat = max(maxLat, w.lats[i])
                    minLon = min(minLon, w.lons[i]); maxLon = max(maxLon, w.lons[i])
                    // Ends always split; an inner node splits when another way (or this one again) uses it.
                    uses.merge(w.nodeIds[i], if (i == 0 || i == w.nodeIds.size - 1) 2 else 1, Int::plus)
                }
            }
            val frame = LocalFrame((minLat + maxLat) / 2, (minLon + maxLon) / 2)

            class Raw(val w: OsmWay, val from: Int, val to: Int)
            val raws = ArrayList<Raw>()
            for (w in unique) {
                var start = 0
                for (i in 1 until w.nodeIds.size) {
                    if (i == w.nodeIds.size - 1 || (uses[w.nodeIds[i]] ?: 0) >= 2) { raws += Raw(w, start, i); start = i }
                }
            }
            // Node → segments starting / ending there.
            val starting = HashMap<Long, MutableList<Int>>(); val ending = HashMap<Long, MutableList<Int>>()
            for ((idx, r) in raws.withIndex()) {
                starting.getOrPut(r.w.nodeIds[r.from]) { ArrayList() } += idx
                ending.getOrPut(r.w.nodeIds[r.to]) { ArrayList() } += idx
            }
            fun enterable(node: Long, self: Int): List<Long> {
                val out = sortedSetOf<Long>()
                starting[node]?.forEach { if (it != self) out += it.toLong() }
                ending[node]?.forEach { if (it != self && !raws[it].w.oneway) out += it.toLong() }
                return out.toList()
            }
            val segs = raws.mapIndexed { idx, r ->
                val geom = (r.from..r.to).map { LatLon(r.w.lats[it], r.w.lons[it]) }
                var len = 0.0
                for (k in 1 until geom.size) len += Geo.haversineM(geom[k - 1].lat, geom[k - 1].lon, geom[k].lat, geom[k].lon)
                val a = r.w.nodeIds[r.from]; val b = r.w.nodeIds[r.to]
                RoadSegment(
                    id = idx.toLong(), geometry = geom, lengthM = len, oneway = r.w.oneway, roadClass = r.w.roadClass,
                    successorsFromEnd = enterable(b, idx),
                    successorsFromStart = if (r.w.oneway) emptyList() else enterable(a, idx),
                    lanes = r.w.lanes, layer = r.w.layer, name = r.w.name, osmWayId = r.w.id, startNode = a, endNode = b,
                )
            }
            return RoadNetwork(segs, frame)
        }
    }
}
