package gpes.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gpes.app.R
import gpes.app.service.MapPoint
import gpes.core.future.RoadSegment
import gpes.core.geo.MapView
import kotlin.math.roundToInt

/** Pieces shared by the live map ([MapTab]) and the route map of a recording ([TripMapScreen]). */

internal val MAJOR_ROADS = setOf(
    "motorway", "trunk", "primary", "secondary", "tertiary",
    "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
)

internal fun roundStroke(widthDp: Float, scope: DrawScope) =
    with(scope) { Stroke(width = widthDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round) }

/** Roads in two weights: main roads thicker and darker, the rest thin. [at] converts a map point to a screen offset. */
internal fun DrawScope.drawRoads(segments: List<RoadSegment>, at: (Double, Double) -> Offset, minorColor: Color, majorColor: Color) {
    val minor = Path(); val major = Path()
    for (s in segments) {
        val path = if (s.roadClass in MAJOR_ROADS) major else minor
        s.geometry.forEachIndexed { i, p -> at(p.lat, p.lon).let { o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) } }
    }
    drawPath(minor, minorColor, style = roundStroke(1.5f, this))
    drawPath(major, majorColor, style = roundStroke(3.5f, this))
}

internal fun DrawScope.drawPolyline(points: List<MapPoint>, at: (Double, Double) -> Offset, color: Color, widthDp: Float) {
    if (points.size < 2) return
    val path = Path()
    points.forEachIndexed { i, p -> at(p.lat, p.lon).let { o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) } }
    drawPath(path, color, style = roundStroke(widthDp, this))
}

/** A round scale bar for the current zoom ([mppDp] is metres per dp). */
@Composable
internal fun ScaleBar(mppDp: Double, density: Float, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val scaleM = MapView.niceScaleM(mppDp / density, 80.0 * density)
    val widthDp = (scaleM / mppDp).toFloat()
    Surface(modifier, shape = RoundedCornerShape(8.dp), color = scheme.surface.copy(alpha = 0.92f)) {
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

/** "© OpenStreetMap contributors", wherever OSM data is shown (ODbL); a tap opens the copyright page. */
@Composable
internal fun OsmAttribution(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    Text(
        stringResource(R.string.osm_attribution),
        modifier
            .background(scheme.surface.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
            .clickable { openUrl(ctx, R.string.osm_url) }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        fontSize = 11.sp, color = scheme.primary,
    )
}

@Composable
internal fun MapButton(text: String, description: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp).semantics { contentDescription = description },
        contentPadding = PaddingValues(0.dp),
    ) { Text(text, fontSize = 20.sp) }
}
