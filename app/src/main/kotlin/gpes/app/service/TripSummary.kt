package gpes.app.service

import android.content.Context
import gpes.core.geo.RoutePreview
import gpes.recording.DriveReader
import gpes.recording.db.DriveDatabase
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** What the recordings list shows about a drive (D-064): the route, thinned, and its duration and length. */
data class TripSummary(
    val route: List<MapPoint>,
    val durationS: Long,
    val distanceM: Double,
    /** False when the recording has no estimate and the route is made of the raw GNSS/fused fixes (a record-only recording). */
    val fromEstimates: Boolean,
) {
    val hasRoute: Boolean get() = route.size >= 2
}

object TripSummaries {
    private const val MAX_POINTS = 1500
    private val cache = ConcurrentHashMap<String, TripSummary>()

    /** Reads one finished recording (three columns of one table) on the calling thread; remembered until the file changes. */
    fun load(ctx: Context, file: File): TripSummary =
        cache.getOrPut("${file.name}:${file.length()}:${file.lastModified()}") { compute(ctx, file) }

    private fun compute(ctx: Context, file: File): TripSummary {
        val empty = TripSummary(emptyList(), 0, 0.0, true)
        val driver = runCatching { DriveStorage.open(ctx, file) }.getOrNull() ?: return empty
        return try {
            val route = DriveReader(DriveDatabase(driver)).route()
            val thinned = RoutePreview.simplifyTo(route.points, MAX_POINTS)
            TripSummary(
                route = thinned.map { MapPoint(it.lat, it.lon) },
                durationS = RoutePreview.durationS(route.points).toLong(),
                distanceM = RoutePreview.lengthM(thinned),
                fromEstimates = route.fromEstimates,
            )
        } catch (_: Exception) {
            empty // a damaged or foreign file: the row just has no route
        } finally {
            driver.close()
        }
    }
}
