package gpes.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gpes.app.R
import gpes.app.service.MapPoint
import gpes.app.service.Status
import gpes.core.geo.LatLon
import gpes.core.geo.MapView
import gpes.core.model.LocSource
import gpes.core.model.TrustState
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private val MAJOR_ROADS = setOf(
    "motorway", "trunk", "primary", "secondary", "tertiary",
    "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
)

/**
 * Own vector map, offline: roads from the OSM tiles the road matcher uses, our estimate with its uncertainty and
 * recent track, and the real GNSS and network fixes coloured by trust, so a lying GPS shows up next to where we think
 * the car is. Drag to pan, pinch or the buttons to zoom, the target button follows the car again.
 */
@Composable
fun MapTab(modifier: Modifier, status: Status) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val density = LocalDensity.current.density
    val cfg = LocalConfiguration.current
    val colors = LocalStatusColors.current
    val scheme = MaterialTheme.colorScheme

    var mppDp by rememberSaveable { mutableStateOf(1.5) } // metres per dp
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var anchor by remember { mutableStateOf<MapPoint?>(null) } // null: following the car

    val map = status.map
    val e = status.estimate
    val here: MapPoint? = e?.let { MapPoint(it.lat, it.lon) }
        ?: map?.trail?.lastOrNull() ?: map?.fixes?.lastOrNull()?.let { MapPoint(it.lat, it.lon) }
    val hereNow by rememberUpdatedState(here)

    Box(modifier.fillMaxSize().background(scheme.surfaceContainer)) {
        if (here == null) {
            Text(
                stringResource(if (status.running) R.string.hero_waiting else R.string.map_idle),
                Modifier.align(Alignment.Center).padding(32.dp), color = scheme.onSurfaceVariant,
            )
            return@Box
        }
        val mpp = mppDp / density
        val base = anchor ?: here
        val center = MapView(base.lat, base.lon, mpp).toLatLon(-panX.toDouble(), -panY.toDouble())

        // Roads near the view, re-queried only when the view has moved about 100 m or zoomed.
        val viewRadiusM = hypot(cfg.screenWidthDp.toDouble(), cfg.screenHeightDp.toDouble()) * mppDp / 2 + 300.0
        val keyLat = (center.lat * 1000).roundToLong()
        val keyLon = (center.lon * 1000 * cos(Math.toRadians(center.lat))).roundToLong()
        val segments = remember(map?.network, keyLat, keyLon, mppDp) {
            map?.network?.segmentsWithin(LatLon(center.lat, center.lon), viewRadiusM).orEmpty()
        }

        val roadMinor = scheme.onSurface.copy(alpha = 0.18f)
        val roadMajor = scheme.onSurface.copy(alpha = 0.34f)
        val accent = scheme.primary
        val outline = scheme.onSurfaceVariant

        Canvas(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (anchor == null) anchor = hereNow
                    panX += pan.x; panY += pan.y
                    mppDp = (mppDp / zoom).coerceIn(0.2, 60.0)
                }
            },
        ) {
            val view = MapView(center.lat, center.lon, mppDp / density)
            val mid = Offset(size.width / 2, size.height / 2)
            fun at(lat: Double, lon: Double) = Offset(mid.x + view.x(lat, lon).toFloat(), mid.y + view.y(lat, lon).toFloat())

            val minor = Path(); val major = Path()
            for (s in segments) {
                val path = if (s.roadClass in MAJOR_ROADS) major else minor
                s.geometry.forEachIndexed { i, p -> at(p.lat, p.lon).let { o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) } }
            }
            val round = { w: Float -> Stroke(width = w.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round) }
            drawPath(minor, roadMinor, style = round(1.5f))
            drawPath(major, roadMajor, style = round(3.5f))

            val trail = map?.trail.orEmpty()
            if (trail.size > 1) {
                val t = Path()
                trail.forEachIndexed { i, p -> at(p.lat, p.lon).let { o -> if (i == 0) t.moveTo(o.x, o.y) else t.lineTo(o.x, o.y) } }
                drawPath(t, accent.copy(alpha = 0.65f), style = round(3f))
            }

            // Real fixes: network as hollow rings, GNSS as dots coloured by the trust verdict.
            for (f in map?.fixes.orEmpty()) {
                val o = at(f.lat, f.lon)
                if (f.source == LocSource.NETWORK) drawCircle(outline, 5.dp.toPx(), o, style = Stroke(1.5.dp.toPx()))
                else drawCircle(
                    when (f.state) {
                        TrustState.TRUSTED -> colors.good
                        TrustState.QUESTIONABLE -> colors.warn
                        TrustState.REJECTED -> colors.bad
                        else -> colors.idle
                    },
                    4.5.dp.toPx(), o,
                )
            }

            e?.let { est ->
                val o = at(est.lat, est.lon)
                val r = (est.accuracyM / view.metersPerPx).toFloat().coerceAtLeast(8.dp.toPx())
                drawCircle(accent.copy(alpha = 0.14f), r, o)
                drawCircle(accent.copy(alpha = 0.55f), r, o, style = Stroke(1.dp.toPx()))
                est.headingRad?.let { h -> rotate(Math.toDegrees(h).toFloat(), o) { drawHeading(o, accent) } }
                drawCircle(Color.White, 9.dp.toPx(), o)
                drawCircle(accent, 6.5.dp.toPx(), o)
            }
        }

        // OpenStreetMap asks for this wherever its data is shown (ODbL).
        Text(
            stringResource(R.string.osm_attribution),
            Modifier.align(Alignment.TopEnd).padding(12.dp)
                .background(scheme.surface.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                .clickable { openUrl(ctx, R.string.osm_url) }
                .padding(horizontal = 6.dp, vertical = 2.dp),
            fontSize = 11.sp, color = scheme.primary,
        )

        // Top: how sure we are, and where.
        Surface(
            Modifier.align(Alignment.TopStart).padding(12.dp),
            shape = RoundedCornerShape(14.dp), color = scheme.surface.copy(alpha = 0.92f),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                val label = when {
                    !status.running -> stringResource(R.string.map_last_session)
                    e != null -> stringResource(R.string.accuracy_value, e.accuracyM)
                    else -> stringResource(R.string.hero_waiting)
                }
                Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                e?.road?.takeIf { it.pOffRoad <= 0.5 && it.roadName.isNotEmpty() }?.let { Text(it.roadName, fontSize = 13.sp, color = scheme.onSurfaceVariant) }
                if (status.running && map?.network == null) Text(stringResource(R.string.map_no_roads), fontSize = 12.sp, color = scheme.onSurfaceVariant)
            }
        }

        // Right: zoom and re-centre.
        Column(Modifier.align(Alignment.CenterEnd).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val zoomIn = stringResource(R.string.map_zoom_in)
            val zoomOut = stringResource(R.string.map_zoom_out)
            val recenter = stringResource(R.string.map_recenter)
            MapButton("+", zoomIn) { mppDp = (mppDp / 1.6).coerceAtLeast(0.2) }
            MapButton("−", zoomOut) { mppDp = (mppDp * 1.6).coerceAtMost(60.0) }
            if (anchor != null) MapButton("◎", recenter) { anchor = null; panX = 0f; panY = 0f }
        }

        // Bottom: scale and legend.
        Row(
            Modifier.align(Alignment.BottomStart).padding(12.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val scaleM = MapView.niceScaleM(mppDp / density, 80.0 * density)
            val widthDp = (scaleM / mppDp).toFloat()
            Surface(shape = RoundedCornerShape(8.dp), color = scheme.surface.copy(alpha = 0.92f)) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                        if (scaleM >= 1000) stringResource(R.string.map_scale_km, (scaleM / 1000).roundToInt())
                        else stringResource(R.string.map_scale_m, scaleM.roundToInt()),
                        fontSize = 12.sp,
                    )
                    Box(Modifier.width(widthDp.dp).size(width = widthDp.dp, height = 3.dp).background(scheme.onSurface))
                }
            }
        }
        if (map?.fixes?.isNotEmpty() == true) {
            Surface(
                Modifier.align(Alignment.BottomEnd).padding(12.dp),
                shape = RoundedCornerShape(10.dp), color = scheme.surface.copy(alpha = 0.92f),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    LegendRow(accent, stringResource(R.string.map_legend_estimate))
                    LegendRow(colors.good, stringResource(R.string.map_legend_gps_ok))
                    LegendRow(colors.warn, stringResource(R.string.map_legend_gps_doubt))
                    LegendRow(colors.bad, stringResource(R.string.map_legend_gps_bad))
                    LegendRow(outline, stringResource(R.string.map_legend_network), hollow = true)
                }
            }
        }
    }
}

private fun DrawScope.drawHeading(o: Offset, color: Color) {
    val p = Path().apply {
        moveTo(o.x, o.y - 22.dp.toPx())
        lineTo(o.x - 8.dp.toPx(), o.y - 8.dp.toPx())
        lineTo(o.x + 8.dp.toPx(), o.y - 8.dp.toPx())
        close()
    }
    drawPath(p, color)
}

@Composable
private fun MapButton(text: String, description: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) { Text(text, fontSize = 20.sp) }
}

@Composable
private fun LegendRow(color: Color, label: String, hollow: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(10.dp).then(
                if (hollow) Modifier.border(1.5.dp, color, CircleShape) else Modifier.background(color, CircleShape),
            ),
        )
        Text(label, fontSize = 11.sp)
    }
}
