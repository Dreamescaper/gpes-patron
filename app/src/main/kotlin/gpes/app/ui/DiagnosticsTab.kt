package gpes.app.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gpes.app.R
import gpes.app.service.Status
import gpes.app.source.ObdState
import gpes.core.estimator.CompassMode
import gpes.core.estimator.CompassVerdict
import gpes.core.model.LocSource
import gpes.core.model.TrustState

/** The researcher's view: raw trust codes, sensors, rates. Unchanged content, moved off the driver's screen. */
@Composable
fun DiagnosticsTab(modifier: Modifier, status: Status) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_diagnostics), style = MaterialTheme.typography.headlineSmall)
        if (!status.running) Text(stringResource(R.string.diag_idle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LivePanel(status)
    }
}

/** GnssRawSource reports English status codes; show them localized. */
@Composable
private fun rawStatusLabel(code: String): String = when (code) {
    "ready" -> stringResource(R.string.raw_ready)
    "not supported" -> stringResource(R.string.raw_not_supported)
    "location disabled" -> stringResource(R.string.raw_location_disabled)
    "not allowed" -> stringResource(R.string.raw_not_allowed)
    "unknown" -> stringResource(R.string.unknown)
    else -> code
}

@Composable
private fun stateColor(s: TrustState?): Color {
    val c = LocalStatusColors.current
    return when (s) {
        TrustState.TRUSTED -> c.good
        TrustState.QUESTIONABLE -> c.warn
        TrustState.REJECTED -> c.bad
        else -> c.idle
    }
}

@Composable
private fun LivePanel(s: Status) {
    val c = LocalStatusColors.current
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val elapsed = (SystemClock.elapsedRealtimeNanos() - s.startedElapsedNs) / 1_000_000_000
            Text(stringResource(R.string.session_header, s.sessionId.orEmpty(), stringResource(s.mode.labelRes), elapsed / 60, elapsed % 60), fontWeight = FontWeight.Bold)
            HorizontalDivider()
            Text(stringResource(R.string.sources), fontWeight = FontWeight.Bold)
            for (src in listOf(LocSource.GNSS, LocSource.FUSED, LocSource.NETWORK)) {
                val st = s.sourceStates[src]
                val a = s.lastTrust[src]
                Text(
                    "${src.name.padEnd(8)} ${st?.let { stringResource(trustLabel(it)) } ?: "–"}  ${a?.reasons?.joinToString(",").orEmpty()}",
                    color = stateColor(st), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                )
            }
            Text(
                stringResource(R.string.sats_line, s.satsUsed, s.satsVisible, s.meanCn0?.let { "%.1f".format(it) } ?: "–", rawStatusLabel(s.gnssMeasurements)),
                fontSize = 12.sp,
            )
            Text(
                stringResource(R.string.cells_line, s.cells?.first ?: 0, s.cells?.second ?: "–") + "\n" +
                    stringResource(
                        R.string.wifi_line,
                        s.wifiAps?.let { pluralStringResource(R.plurals.wifi_aps, it.first, it.first, it.second.toInt()) } ?: "–",
                        s.wifiScans.first, s.wifiScans.second,
                    ),
                fontSize = 12.sp,
            )
            s.compass?.let { comp ->
                val reading = comp.reading?.let { r ->
                    val mode = when (r.mode) {
                        CompassMode.GNSS_ALIGNED -> R.string.compass_mode_gnss
                        CompassMode.FORWARD_ALIGNED -> R.string.compass_mode_forward
                        CompassMode.UNCORRECTED -> R.string.compass_mode_uncorrected
                    }
                    stringResource(R.string.compass_reading, Math.toDegrees(r.bearingRad), Math.toDegrees(r.sigmaRad), stringResource(mode))
                } ?: stringResource(R.string.compass_no_reading)
                Text(
                    stringResource(R.string.compass_line, stringResource(compassVerdictLabel(comp.quality.verdict)), reading, comp.quality.reasons.joinToString(",")),
                    color = when (comp.quality.verdict) {
                        CompassVerdict.USABLE -> c.good
                        CompassVerdict.MARGINAL -> c.warn
                        CompassVerdict.UNUSABLE -> c.bad
                        else -> c.idle
                    },
                    fontSize = 12.sp,
                )
            }
            s.obd?.let { o ->
                val state = when (o.state) {
                    ObdState.CONNECTING -> R.string.obd_state_connecting
                    ObdState.INITIALIZING -> R.string.obd_state_initializing
                    ObdState.POLLING -> R.string.obd_state_polling
                    ObdState.RETRYING -> R.string.obd_state_retrying
                    ObdState.STOPPED -> R.string.obd_state_stopped
                }
                Text(
                    stringResource(
                        R.string.obd_line, stringResource(state), o.version ?: o.device, o.protocol ?: "", o.lastKmh?.toString() ?: "–",
                        o.samples.toInt(), o.noData.toInt(),
                    ) + (o.error?.let { "\n$it" } ?: ""),
                    color = if (o.state == ObdState.POLLING) c.good else c.warn, fontSize = 12.sp,
                )
                s.speedScale?.let { (k, sd) -> Text(stringResource(R.string.obd_scale, k * 100, sd * 100), fontSize = 12.sp) }
            }
            HorizontalDivider()
            val e = s.estimate
            if (s.mode.estimate) {
                Text(stringResource(R.string.estimate), fontWeight = FontWeight.Bold)
                if (e == null) Text(stringResource(R.string.estimate_uninitialized), fontSize = 12.sp)
                else Text(
                    stringResource(
                        R.string.estimate_line,
                        stringResource(modeLabel(e.mode)), e.lat, e.lon, e.accuracyM,
                        e.headingRad?.let { stringResource(R.string.heading_value, Math.toDegrees(it), Math.toDegrees(e.headingStdRad ?: 0.0)) }
                            ?: stringResource(R.string.unknown),
                        e.speedMps?.let { stringResource(R.string.speed_value, it, e.speedStdMps ?: 0.0) } ?: stringResource(R.string.unknown),
                    ),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                )
            }
            s.roadMap?.let { r ->
                Text(
                    stringResource(R.string.roads_tiles, r.tilesLoaded, r.segments, r.downloading) + (r.lastError?.let { "\n$it" } ?: ""),
                    color = if (r.lastError != null) c.warn else Color.Unspecified, fontSize = 12.sp,
                )
                e?.road?.let { rs ->
                    Text(
                        if (rs.pOffRoad > 0.5) stringResource(R.string.roads_off, rs.pOffRoad * 100)
                        else stringResource(R.string.roads_on, rs.roadName.ifEmpty { "–" }, rs.probability * 100, rs.confidentM),
                        fontSize = 12.sp,
                    )
                }
            }
            if (s.mode.mock) {
                Text(stringResource(R.string.mock_published, s.mockPublished.toInt(), s.mockTargets.joinToString()), fontSize = 12.sp)
                s.mockError?.let { Text(it, color = c.bad, fontSize = 12.sp) }
            }
            HorizontalDivider()
            Text(stringResource(R.string.recorded_rates), fontWeight = FontWeight.Bold)
            Text(
                s.counts.entries.sortedBy { it.key }.joinToString("\n") { (k, v) -> "%-24s %8d  %6.1f".format(k, v, s.ratesHz[k] ?: 0.0) },
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
            if (s.lateMeasurements > 0) Text(stringResource(R.string.late_measurements, s.lateMeasurements.toInt()), fontSize = 11.sp)
        }
    }
}
