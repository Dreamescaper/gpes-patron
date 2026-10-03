package gpes.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gpes.app.R
import gpes.app.road.RoadCache
import gpes.app.service.MapPoint
import gpes.app.service.TripSummary
import gpes.core.geo.LatLon
import gpes.core.geo.MapView
import gpes.core.road.RoadNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** The route of a recording (D-064): a small picture in the list, and a full map on a tap. */

private class Bounds(val latMin: Double, val latMax: Double, val lonMin: Double, val lonMax: Double) {
    val latMid get() = (latMin + latMax) / 2
    val lonMid get() = (lonMin + lonMax) / 2
    /** East-west and north-south extent in metres. */
    val widthM get() = (lonMax - lonMin) * 111_320.0 * cos(Math.toRadians(latMid))
    val heightM get() = (latMax - latMin) * 111_320.0
}

private fun boundsOf(route: List<MapPoint>) =
    Bounds(route.minOf { it.lat }, route.maxOf { it.lat }, route.minOf { it.lon }, route.maxOf { it.lon })

/** The route as a thumbnail: its shape fitted into the square, a green dot at the start and a red one at the end. */
@Composable
internal fun RouteThumb(route: List<MapPoint>?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = LocalStatusColors.current
    val line = scheme.primary
    Canvas(modifier.size(56.dp).background(scheme.surfaceContainerHigh, RoundedCornerShape(10.dp))) {
        if (route == null || route.size < 2) {
            drawLine(scheme.onSurfaceVariant.copy(alpha = 0.4f), Offset(size.width * 0.32f, size.height / 2), Offset(size.width * 0.68f, size.height / 2), 2.dp.toPx(), StrokeCap.Round)
            return@Canvas
        }
        val b = boundsOf(route)
        if (b.widthM < 20 && b.heightM < 20) { // parked: no shape to draw, one dot in the middle
            drawCircle(colors.bad, 3.5.dp.toPx(), Offset(size.width / 2, size.height / 2))
            return@Canvas
        }
        val pad = 9.dp.toPx()
        val kx = cos(Math.toRadians(b.latMid))
        val w = max((b.lonMax - b.lonMin) * kx, 1e-6)
        val h = max(b.latMax - b.latMin, 1e-6)
        val scale = min((size.width - 2 * pad) / w, (size.height - 2 * pad) / h).toDouble()
        val offX = ((size.width - w * scale) / 2).toFloat()
        val offY = ((size.height - h * scale) / 2).toFloat()
        fun at(p: MapPoint) = Offset(offX + ((p.lon - b.lonMin) * kx * scale).toFloat(), offY + ((b.latMax - p.lat) * scale).toFloat())
        val path = Path()
        route.forEachIndexed { i, p -> at(p).let { o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) } }
        drawPath(path, line, style = roundStroke(2f, this))
        drawCircle(colors.good, 3.5.dp.toPx(), at(route.first()))
        drawCircle(colors.bad, 3.5.dp.toPx(), at(route.last()))
    }
}

/** "12 min", "1 h 05 min". */
@Composable
internal fun formatDuration(seconds: Long): String {
    val minutes = (seconds + 30) / 60
    return when {
        seconds < 60 -> stringResource(R.string.trip_lt_min)
        minutes < 60 -> stringResource(R.string.trip_min, minutes.toInt())
        else -> stringResource(R.string.trip_h_min, (minutes / 60).toInt(), (minutes % 60).toInt())
    }
}

@Composable
internal fun tripStats(s: TripSummary): String =
    if (!s.hasRoute) stringResource(R.string.trip_no_route)
    else formatDuration(s.durationS) + " · " + stringResource(R.string.trip_km, s.distanceM / 1000.0)

/** A recording opened from the list: stats on top, the route on the map below. Back returns to the list. */
@Composable
fun TripMapScreen(modifier: Modifier, file: File, summary: TripSummary, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("‹ " + stringResource(R.string.trip_back)) }
            Column(Modifier.weight(1f)) {
                Text(driveTitle(file), fontWeight = FontWeight.Medium, fontSize = 16.sp)
                Text(tripStats(summary), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!summary.fromEstimates) {
            Text(
                stringResource(R.string.trip_from_fixes), Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
        TripRouteMap(Modifier.weight(1f), summary.route)
    }
}

@Composable
private fun TripRouteMap(modifier: Modifier, route: List<MapPoint>) {
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    val scheme = MaterialTheme.colorScheme
    val colors = LocalStatusColors.current
    // clipToBounds: the map must not paint over the header above it.
    BoxWithConstraints(modifier.clipToBounds().background(scheme.surfaceContainer)) {
        val wDp = maxWidth.value.toDouble()
        val hDp = maxHeight.value.toDouble()
        val b = remember(route) { boundsOf(route) }
        val fit = remember(route, wDp, hDp) { max(b.widthM / (wDp * 0.8), b.heightM / (hDp * 0.8)).coerceIn(0.5, 60.0) }
        var mppDp by remember(route) { mutableStateOf(fit) }
        var panX by remember(route) { mutableFloatStateOf(0f) }
        var panY by remember(route) { mutableFloatStateOf(0f) }

        // Roads only from the tiles already on the phone: no download for an old route.
        val network by produceState<RoadNetwork?>(null, route) {
            val radius = (hypot(b.widthM, b.heightM) / 2 + 1_500.0).coerceAtMost(15_000.0)
            value = withContext(Dispatchers.IO) { runCatching { RoadCache.load(ctx, b.latMid, b.lonMid, radius) }.getOrNull() }
        }

        val center = MapView(b.latMid, b.lonMid, mppDp / density).toLatLon(-panX.toDouble(), -panY.toDouble())
        val viewRadiusM = hypot(wDp, hDp) * mppDp / 2 + 300.0
        val keyLat = (center.lat * 1000).roundToLong()
        val keyLon = (center.lon * 1000 * cos(Math.toRadians(center.lat))).roundToLong()
        val segments = remember(network, keyLat, keyLon, mppDp) {
            network?.segmentsWithin(LatLon(center.lat, center.lon), viewRadiusM).orEmpty()
        }
        val roadMinor = scheme.onSurface.copy(alpha = 0.18f)
        val roadMajor = scheme.onSurface.copy(alpha = 0.34f)
        val accent = scheme.primary

        Canvas(
            Modifier.fillMaxSize().pointerInput(route) {
                detectTransformGestures { _, pan, zoom, _ ->
                    panX += pan.x; panY += pan.y
                    mppDp = (mppDp / zoom).coerceIn(0.2, 60.0)
                }
            },
        ) {
            val view = MapView(center.lat, center.lon, mppDp / density)
            val mid = Offset(size.width / 2, size.height / 2)
            fun at(lat: Double, lon: Double) = Offset(mid.x + view.x(lat, lon).toFloat(), mid.y + view.y(lat, lon).toFloat())
            drawRoads(segments, ::at, roadMinor, roadMajor)
            drawPolyline(route, ::at, accent, 4f)
            for ((p, c) in listOf(route.first() to colors.good, route.last() to colors.bad)) {
                val o = at(p.lat, p.lon)
                drawCircle(Color.White, 9.dp.toPx(), o)
                drawCircle(c, 6.5.dp.toPx(), o)
            }
        }

        if (network != null) OsmAttribution(Modifier.align(Alignment.TopEnd).padding(12.dp))
        ScaleBar(mppDp, density, Modifier.align(Alignment.BottomStart).padding(12.dp))
        Column(Modifier.align(Alignment.CenterEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MapButton("+", stringResource(R.string.map_zoom_in)) { mppDp = (mppDp / 1.6).coerceAtLeast(0.2) }
            MapButton("−", stringResource(R.string.map_zoom_out)) { mppDp = (mppDp * 1.6).coerceAtMost(60.0) }
            MapButton("◎", stringResource(R.string.trip_fit)) { mppDp = fit; panX = 0f; panY = 0f }
        }
        Surface(
            Modifier.align(Alignment.BottomEnd).padding(12.dp),
            shape = RoundedCornerShape(10.dp), color = scheme.surface.copy(alpha = 0.92f),
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                LegendDot(colors.good, stringResource(R.string.trip_start))
                LegendDot(colors.bad, stringResource(R.string.trip_end))
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(Modifier.size(10.dp), shape = CircleShape, color = color) {}
        Text(label, fontSize = 11.sp)
    }
}
