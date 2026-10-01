package gpes.core.road

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.long

/**
 * A drivable OSM way, reduced to what the road constraint needs. Node ids are kept so that ways from
 * different tiles join at shared intersections. [oneway] is normalised: a `oneway=-1` way is stored
 * reversed with `oneway = true`.
 */
class OsmWay(
    val id: Long,
    val nodeIds: LongArray,
    val lats: DoubleArray,
    val lons: DoubleArray,
    val roadClass: String,
    val oneway: Boolean,
    /** Number of lanes in total, or 0 when OSM has no `lanes` tag. */
    val lanes: Int,
    val layer: Int,
    val name: String,
) {
    init {
        require(nodeIds.size == lats.size && lats.size == lons.size && nodeIds.size >= 2)
    }
}

/** Which OSM ways count as roads for a car, and how their tags are normalised (D-041). */
object OsmRoadFilter {
    val CLASSES = setOf(
        "motorway", "motorway_link", "trunk", "trunk_link", "primary", "primary_link",
        "secondary", "secondary_link", "tertiary", "tertiary_link", "unclassified", "residential",
        "living_street", "service", "road",
    )

    /**
     * Service ways inside car parks and to single buildings are left out: driving there is free
     * manoeuvring, so they count as off-road (no road constraint), not as weak roads.
     */
    val EXCLUDED_SERVICE = setOf("parking_aisle", "driveway", "drive-through", "emergency_access")

    fun accepts(tags: Map<String, String>): Boolean {
        val h = tags["highway"] ?: return false
        if (h !in CLASSES) return false
        if (h == "service" && tags["service"] in EXCLUDED_SERVICE) return false
        if (tags["area"] == "yes") return false
        if (tags["access"] == "no" && tags["motor_vehicle"] == null && tags["motorcar"] == null) return false
        return true
    }

    /** +1 forward one-way, −1 reverse one-way, 0 two-way. */
    fun onewayDirection(tags: Map<String, String>): Int = when (tags["oneway"]) {
        "yes", "true", "1" -> 1
        "-1", "reverse" -> -1
        "no", "false", "0" -> 0
        else -> if (tags["junction"] == "roundabout" || tags["junction"] == "circular" ||
            tags["highway"] == "motorway" || tags["highway"] == "motorway_link"
        ) 1 else 0
    }

    fun lanes(tags: Map<String, String>): Int = tags["lanes"]?.trim()?.substringBefore(';')?.toIntOrNull()?.coerceIn(0, 20) ?: 0

    fun layer(tags: Map<String, String>): Int = tags["layer"]?.trim()?.toIntOrNull()?.coerceIn(-5, 5) ?: 0
}

/**
 * Reads Overpass API JSON: either ways with `geometry` (`out geom`), or ways with `nodes` plus the node
 * elements (`out body; >; out skel qt;`). Ways that fail [OsmRoadFilter] are dropped.
 */
object OverpassImport {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<OsmWay> {
        val elements = json.parseToJsonElement(text).jsonObject["elements"]?.jsonArray ?: return emptyList()
        val nodes = HashMap<Long, Pair<Double, Double>>()
        for (e in elements) {
            val o = e.jsonObject
            if (o["type"]?.jsonPrimitive?.content == "node") {
                nodes[o["id"]!!.jsonPrimitive.long] = o["lat"]!!.jsonPrimitive.double to o["lon"]!!.jsonPrimitive.double
            }
        }
        val out = ArrayList<OsmWay>()
        for (e in elements) {
            val o = e.jsonObject
            if (o["type"]?.jsonPrimitive?.content != "way") continue
            val tags = (o["tags"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: continue
            if (!OsmRoadFilter.accepts(tags)) continue
            val ids = o["nodes"]?.jsonArray?.map { it.jsonPrimitive.long } ?: continue
            val geom = o["geometry"]?.jsonArray
            val lat = DoubleArray(ids.size); val lon = DoubleArray(ids.size)
            var ok = true
            for (i in ids.indices) {
                val g = geom?.getOrNull(i)?.jsonObject
                if (g != null) {
                    lat[i] = g["lat"]!!.jsonPrimitive.double; lon[i] = g["lon"]!!.jsonPrimitive.double
                } else {
                    val n = nodes[ids[i]] ?: run { ok = false; null } ?: break
                    lat[i] = n.first; lon[i] = n.second
                }
            }
            if (!ok || ids.size < 2) continue
            val dir = OsmRoadFilter.onewayDirection(tags)
            val idArr = ids.toLongArray()
            if (dir < 0) { idArr.reverse(); lat.reverse(); lon.reverse() }
            out += OsmWay(
                o["id"]!!.jsonPrimitive.long, idArr, lat, lon, tags["highway"]!!, dir != 0,
                OsmRoadFilter.lanes(tags), OsmRoadFilter.layer(tags), tags["name"] ?: "",
            )
        }
        return out.sortedBy { it.id }
    }
}
