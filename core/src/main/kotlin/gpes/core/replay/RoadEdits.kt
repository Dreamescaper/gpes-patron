package gpes.core.replay

import gpes.core.geo.Geo
import gpes.core.geo.LatLon
import gpes.core.road.RoadNetwork
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Changes to the road map for robustness tests (M5, docs/road-constraint.md): a bad or outdated map. */
@Serializable
sealed interface RoadEdit {
    /**
     * Remove every segment within [bufferM] of the truth track between [fromS] and [toS] (seconds from the
     * start): the car drives where OSM has no road (a new road, a car park).
     */
    @Serializable @SerialName("remove_roads_along_truth")
    data class RemoveAlongTruth(val fromS: Double, val toS: Double, val bufferM: Double = 30.0) : RoadEdit

    /** Shift the whole map, simulating misaligned OSM geometry. */
    @Serializable @SerialName("shift_roads")
    data class Shift(val eastM: Double, val northM: Double) : RoadEdit
}

object RoadEdits {
    fun apply(net: RoadNetwork, edits: List<RoadEdit>, truth: TruthTrack, t0Ns: Long): RoadNetwork {
        if (edits.isEmpty()) return net
        var cur = net
        for (e in edits) cur = when (e) {
            is RoadEdit.Shift -> RoadNetwork.build(cur.toWays { p ->
                val a = Geo.destination(p.lat, p.lon, e.northM, 0.0)
                Geo.destination(a.lat, a.lon, e.eastM, 90.0)
            })
            is RoadEdit.RemoveAlongTruth -> {
                val drop = HashSet<Long>()
                var t = e.fromS
                while (t <= e.toS) {
                    truth.at(t0Ns + (t * 1e9).toLong())?.let { s -> cur.project(s.lat, s.lon, e.bufferM).forEach { drop += it.segment.id } }
                    t += 1.0
                }
                val keep = cur.toWays().filter { (it.id - 1) !in drop }
                RoadNetwork.build(keep)
            }
        }
        return cur
    }
}
