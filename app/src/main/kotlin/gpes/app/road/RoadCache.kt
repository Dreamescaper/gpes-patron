package gpes.app.road

import android.content.Context
import gpes.core.road.RoadNetwork
import gpes.core.road.RoadTile
import gpes.core.road.RoadTileCodec
import gpes.core.road.RoadTileFiles
import java.io.File

/** Roads from the tiles already on the phone, for showing an old route (D-064). Never downloads; null when none is cached. */
object RoadCache {
    fun load(ctx: Context, lat: Double, lon: Double, radiusM: Double): RoadNetwork? {
        val dir = RoadMapManager.dir(ctx)
        val ways = RoadTile.around(lat, lon, radiusM).sortedBy { it.key }.flatMap { t ->
            val f = File(dir, t.key + RoadTileFiles.EXT)
            if (!f.exists()) emptyList() else runCatching { f.inputStream().use { RoadTileCodec.read(it) } }.getOrDefault(emptyList())
        }
        return if (ways.isEmpty()) null else RoadNetwork.build(ways)
    }
}
