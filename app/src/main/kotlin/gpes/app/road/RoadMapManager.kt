package gpes.app.road

import android.content.Context
import gpes.app.BuildConfig
import gpes.core.geo.Geo
import gpes.core.road.OsmWay
import gpes.core.road.OverpassImport
import gpes.core.road.RoadNetwork
import gpes.core.road.RoadTile
import gpes.core.road.RoadTileCodec
import gpes.core.road.RoadTileFiles
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Snapshot for the UI. */
data class RoadMapStatus(
    val tilesLoaded: Int = 0,
    val downloading: Int = 0,
    val segments: Int = 0,
    val lastError: String? = null,
)

/**
 * Keeps road tiles around the car (D-041): on each position (our estimate, or a network fix before the
 * estimator starts) it makes sure the tiles within [radiusM] are on disk, downloading missing ones from the
 * Overpass API, and rebuilds the [RoadNetwork] from the tiles within [keepRadiusM]. All work runs on one
 * background thread; [network] is published for the estimator thread.
 *
 * Privacy: a download reveals the tile's bounding box (≈ 5.6 km), not the drive trace.
 */
class RoadMapManager(
    ctx: Context,
    private val radiusM: Double = 6_000.0,
    private val keepRadiusM: Double = 12_000.0,
    private val endpoint: String = "https://overpass-api.de/api/interpreter",
) {
    val dir: File = dir(ctx)

    @Volatile var network: RoadNetwork? = null
        private set
    @Volatile var status = RoadMapStatus()
        private set

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "gpes-roads").also { it.isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private var lastCheck: Pair<Double, Double>? = null
    private val failedAt = HashMap<RoadTile, Long>()
    private var builtFrom: Set<RoadTile> = emptySet()

    /** Call with any recent position (any thread). Cheap when nothing changed. */
    fun onPosition(lat: Double, lon: Double, nowMs: Long) {
        val prev = lastCheck
        if (prev != null && Geo.haversineM(prev.first, prev.second, lat, lon) < CHECK_EVERY_M) return
        if (!busy.compareAndSet(false, true)) return
        lastCheck = lat to lon
        exec.execute {
            try { refresh(lat, lon, nowMs) } finally { busy.set(false) }
        }
    }

    fun stop() { exec.shutdownNow() }

    private fun refresh(lat: Double, lon: Double, nowMs: Long) {
        val want = RoadTile.around(lat, lon, radiusM)
        var err: String? = status.lastError
        val missing = want.filter { !file(it).exists() && (failedAt[it]?.let { t -> nowMs - t > RETRY_MS } ?: true) }
        status = status.copy(downloading = missing.size)
        for ((i, t) in missing.withIndex()) {
            try {
                val ways = download(t)
                file(t).outputStream().use { RoadTileCodec.write(ways, it) }
                failedAt.remove(t)
                err = null
            } catch (e: Exception) {
                failedAt[t] = nowMs
                err = "${t.key}: ${e.javaClass.simpleName} ${e.message ?: ""}".take(200)
            }
            status = status.copy(downloading = missing.size - i - 1, lastError = err)
        }
        val keep = RoadTile.around(lat, lon, keepRadiusM).filter { file(it).exists() }.toSet()
        if (keep != builtFrom) {
            val ways = keep.sortedBy { it.key }.flatMap { t ->
                runCatching { file(t).inputStream().use { RoadTileCodec.read(it) } }.getOrElse { file(t).delete(); emptyList<OsmWay>() }
            }
            network = if (ways.isEmpty()) null else RoadNetwork.build(ways)
            builtFrom = keep
        }
        status = RoadMapStatus(keep.size, 0, network?.size ?: 0, err)
    }

    private fun download(t: RoadTile): List<OsmWay> {
        val c = URL(endpoint).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 20_000; c.readTimeout = 120_000
            c.doOutput = true
            // Overpass refuses requests without a User-Agent (406).
            c.setRequestProperty("User-Agent", "gpes-patron/${BuildConfig.VERSION_NAME} (Android; road tiles)")
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(("data=" + URLEncoder.encode(t.overpassQuery(), "UTF-8")).toByteArray()) }
            if (c.responseCode != 200) error("HTTP ${c.responseCode}")
            return OverpassImport.parse(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    private fun file(t: RoadTile) = File(dir, t.key + RoadTileFiles.EXT)

    companion object {
        private const val CHECK_EVERY_M = 1_000.0
        private const val RETRY_MS = 5 * 60_000L

        /** `Android/data/gpes.patron/files/roads`, next to `drives/` (pull with adb for replay `--roads`). */
        fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "roads").also { it.mkdirs() }
    }
}
