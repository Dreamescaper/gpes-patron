package gpes.core.road

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.cos
import kotlin.math.floor

/**
 * A cell of the fixed road-tile grid: [TILE_LAT_DEG] × [TILE_LON_DEG] (≈ 5.6 × 5.7 km at 50° N). The app
 * downloads the tiles around its location; a tile holds every road way that intersects it, with full
 * geometry, so ways crossing a border appear in both tiles and are merged by id (D-041).
 */
data class RoadTile(val ix: Int, val iy: Int) {
    val south: Double get() = iy * TILE_LAT_DEG
    val west: Double get() = ix * TILE_LON_DEG
    val north: Double get() = south + TILE_LAT_DEG
    val east: Double get() = west + TILE_LON_DEG

    /** File name stem, e.g. `r1009_0381`. */
    val key: String get() = "r${iy}_$ix"

    /** Overpass QL for the drivable ways of this tile (with their nodes, for joining across tiles). */
    fun overpassQuery(timeoutS: Int = 90): String {
        val classes = OsmRoadFilter.CLASSES.sorted().joinToString("|")
        return "[out:json][timeout:$timeoutS];way[\"highway\"~\"^($classes)$\"]($south,$west,$north,$east);out body;>;out skel qt;"
    }

    companion object {
        const val TILE_LAT_DEG = 0.05
        const val TILE_LON_DEG = 0.08

        fun of(lat: Double, lon: Double) = RoadTile(floor(lon / TILE_LON_DEG).toInt(), floor(lat / TILE_LAT_DEG).toInt())

        /** Tiles that intersect the square of half-side [radiusM] around the point, nearest first. */
        fun around(lat: Double, lon: Double, radiusM: Double): List<RoadTile> {
            val dLat = radiusM / 111_195.0
            val dLon = radiusM / (111_195.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.01))
            val a = of(lat - dLat, lon - dLon); val b = of(lat + dLat, lon + dLon)
            val c = of(lat, lon)
            val out = ArrayList<RoadTile>()
            for (iy in a.iy..b.iy) for (ix in a.ix..b.ix) out += RoadTile(ix, iy)
            return out.sortedWith(compareBy<RoadTile> { maxOf(kotlin.math.abs(it.ix - c.ix), kotlin.math.abs(it.iy - c.iy)) }.thenBy { it.iy }.thenBy { it.ix })
        }
    }
}

/** Binary, gzipped file of [OsmWay]s (one tile, or any set). Coordinates are stored as 1e-7 degrees. */
object RoadTileCodec {
    private const val MAGIC = 0x47505244 // "GPRD"
    const val VERSION = 1

    fun write(ways: List<OsmWay>, out: OutputStream) {
        val gz = GZIPOutputStream(out)
        val d = DataOutputStream(gz)
        d.writeInt(MAGIC); d.writeInt(VERSION); d.writeInt(ways.size)
        for (w in ways.sortedBy { it.id }) {
            d.writeLong(w.id); d.writeUTF(w.roadClass); d.writeBoolean(w.oneway)
            d.writeByte(w.lanes); d.writeByte(w.layer); d.writeUTF(w.name)
            d.writeInt(w.nodeIds.size)
            for (i in w.nodeIds.indices) {
                d.writeLong(w.nodeIds[i]); d.writeInt(e7(w.lats[i])); d.writeInt(e7(w.lons[i]))
            }
        }
        d.flush(); gz.finish()
    }

    fun read(input: InputStream): List<OsmWay> {
        val d = DataInputStream(GZIPInputStream(input))
        require(d.readInt() == MAGIC) { "not a road tile" }
        val v = d.readInt()
        require(v == VERSION) { "road tile version $v, expected $VERSION" }
        return List(d.readInt()) {
            val id = d.readLong(); val cls = d.readUTF(); val oneway = d.readBoolean()
            val lanes = d.readByte().toInt(); val layer = d.readByte().toInt(); val name = d.readUTF()
            val n = d.readInt()
            val ids = LongArray(n); val lat = DoubleArray(n); val lon = DoubleArray(n)
            for (i in 0 until n) { ids[i] = d.readLong(); lat[i] = d.readInt() / 1e7; lon[i] = d.readInt() / 1e7 }
            OsmWay(id, ids, lat, lon, cls, oneway, lanes, layer, name)
        }
    }

    private fun e7(deg: Double) = Math.round(deg * 1e7).toInt()
}

/** Tile files on disk: `<dir>/<tile.key>.roads`. */
object RoadTileFiles {
    const val EXT = ".roads"

    /** A tile file older than this is downloaded again when the car is near it (roads change; D-065). */
    const val MAX_AGE_MS = 90L * 24 * 60 * 60 * 1000

    /**
     * Whether a tile file written at [lastModifiedMs] is due for a refresh at [nowMs]. A file with an unknown time (0) is due; one dated in the
     * future (a clock that was wrong) is not.
     */
    fun isStale(lastModifiedMs: Long, nowMs: Long, maxAgeMs: Long = MAX_AGE_MS): Boolean =
        lastModifiedMs <= 0 || nowMs - lastModifiedMs > maxAgeMs

    /** Every tile a way passes through (vertices, and edges sampled every ~1 km). */
    fun tilesOf(w: OsmWay): Set<RoadTile> {
        val out = HashSet<RoadTile>()
        for (i in w.lats.indices) {
            out += RoadTile.of(w.lats[i], w.lons[i])
            if (i == 0) continue
            val steps = (maxOf(kotlin.math.abs(w.lats[i] - w.lats[i - 1]) / RoadTile.TILE_LAT_DEG,
                kotlin.math.abs(w.lons[i] - w.lons[i - 1]) / RoadTile.TILE_LON_DEG) * 6).toInt()
            for (k in 1..steps) {
                val f = k.toDouble() / (steps + 1)
                out += RoadTile.of(w.lats[i - 1] + f * (w.lats[i] - w.lats[i - 1]), w.lons[i - 1] + f * (w.lons[i] - w.lons[i - 1]))
            }
        }
        return out
    }

    fun splitByTile(ways: List<OsmWay>): Map<RoadTile, List<OsmWay>> {
        val m = HashMap<RoadTile, MutableList<OsmWay>>()
        for (w in ways) for (t in tilesOf(w)) m.getOrPut(t) { ArrayList() } += w
        return m
    }

    fun write(dir: java.io.File, tile: RoadTile, ways: List<OsmWay>) {
        dir.mkdirs()
        java.io.File(dir, tile.key + EXT).outputStream().use { RoadTileCodec.write(ways, it) }
    }

    /** All ways from every tile file in [dir] (a way present in several tiles is returned once). */
    fun loadDir(dir: java.io.File): List<OsmWay> =
        (dir.listFiles { f -> f.name.endsWith(EXT) } ?: emptyArray()).sortedBy { it.name }
            .flatMap { f -> f.inputStream().use { RoadTileCodec.read(it) } }
            .associateBy { it.id }.values.sortedBy { it.id }
}
