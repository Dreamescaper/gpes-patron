package gpes.app.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import gpes.app.R
import gpes.app.mock.MockTarget
import gpes.app.service.DriveService
import gpes.app.service.RunMode
import gpes.app.service.Status
import gpes.app.source.ObdState
import gpes.core.estimator.CompassVerdict
import gpes.core.model.LocSource
import gpes.core.model.TrustState

@StringRes
private fun shortLabel(m: RunMode) = when (m) {
    RunMode.RECORD_ONLY -> R.string.mode_short_record
    RunMode.ESTIMATE_ONLY -> R.string.mode_short_estimate
    RunMode.MOCK_OUTPUT -> R.string.mode_short_protect
}

@StringRes
private fun description(m: RunMode) = when (m) {
    RunMode.RECORD_ONLY -> R.string.mode_desc_record
    RunMode.ESTIMATE_ONLY -> R.string.mode_desc_estimate
    RunMode.MOCK_OUTPUT -> R.string.mode_desc_protect
}

private fun hasPermission(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

/** The driver's screen: pick what to do, start, then one glance tells how much the position can be trusted. */
@Composable
fun DriveTab(modifier: Modifier, settings: AppSettings, status: Status, refreshKey: Int, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    // The user may come back from system settings: re-read permissions and the mock-location app.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { onChanged() }
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (status.running) RunningContent(status, ctx) else IdleContent(settings, refreshKey, ctx, onChanged)
        }
        // Pinned: the way out must never scroll out of reach.
        if (status.running) Button(
            onClick = { DriveService.stop(ctx) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(64.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.surface),
        ) {
            Text(stringResource(if (status.mode.mock) R.string.stop_spoof else R.string.stop_drive), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun IdleContent(settings: AppSettings, refreshKey: Int, ctx: Context, onChanged: () -> Unit) {
    val mode = settings.mode
    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.tagline), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)

    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        RunMode.entries.forEachIndexed { i, m ->
            SegmentedButton(
                selected = mode == m,
                onClick = { settings.mode = m },
                shape = SegmentedButtonDefaults.itemShape(i, RunMode.entries.size),
                label = { Text(stringResource(shortLabel(m))) },
            )
        }
    }
    Text(stringResource(description(mode)), fontSize = 14.sp)

    // Readiness: only what blocks or weakens the chosen mode, each with the way to fix it.
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    val locationOk = remember(refreshKey) { hasPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) }
    val notificationsOk = remember(refreshKey) { Build.VERSION.SDK_INT < 33 || hasPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) }
    val mockOk = remember(refreshKey, mode) { !mode.mock || isMockAppSelected(ctx) }
    val noTarget = mode.mock && !settings.fused && !settings.gps && !settings.network

    if (!locationOk) Notice(
        stringResource(R.string.ready_location), stringResource(R.string.ready_allow), blocking = true,
    ) { permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    if (!notificationsOk && mode.mock) Notice(
        stringResource(R.string.ready_notifications), stringResource(R.string.ready_allow), blocking = false,
    ) { permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) }
    if (!mockOk) Notice(
        stringResource(R.string.ready_mock_app, ctx.packageName), stringResource(R.string.ready_open_developer), blocking = true,
    ) {
        runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(ctx, R.string.ready_developer_off, Toast.LENGTH_LONG).show() }
    }
    if (noTarget) Notice(stringResource(R.string.ready_no_target), null, blocking = true) {}

    val ready = locationOk && mockOk && !noTarget
    Button(
        onClick = {
            val targets = buildSet {
                if (settings.fused) add(MockTarget.FUSED)
                if (settings.gps) add(MockTarget.GPS)
                if (settings.network) add(MockTarget.NETWORK)
            }
            DriveService.start(ctx, mode, targets, settings.useQuestionable, settings.obdAddress.takeIf { settings.obdEnabled }, settings.roads)
        },
        enabled = ready,
        modifier = Modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(20.dp),
    ) {
        Text(stringResource(if (mode.mock) R.string.start_protect else R.string.start_drive), fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Notice(text: String, action: String?, blocking: Boolean, onAction: () -> Unit) {
    val c = LocalStatusColors.current
    val tint = if (blocking) c.bad else c.warn
    Card(colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.14f))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, fontSize = 14.sp)
            if (action != null) OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

private enum class Evidence { ON, WARN, OFF }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunningContent(s: Status, ctx: Context) {
    val c = LocalStatusColors.current
    val e = s.estimate
    val gnssSource = if (s.sourceStates.containsKey(LocSource.GNSS)) LocSource.GNSS else LocSource.FUSED
    val gnss = s.sourceStates[gnssSource]

    if (s.mode.mock) {
        Card(colors = CardDefaults.cardColors(containerColor = c.warn.copy(alpha = 0.16f))) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.spoof_active), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.spoof_active_text), fontSize = 13.sp)
                s.mockError?.let { Text(it, color = c.bad, fontSize = 13.sp) }
            }
        }
    }

    // Hero: one verdict about the GNSS signal, in words and colour.
    val (title, sub, tint) = when {
        !s.mode.estimate -> Triple(R.string.hero_recording, R.string.hero_recording_sub, MaterialTheme.colorScheme.primary)
        e == null -> Triple(R.string.hero_waiting, R.string.hero_waiting_sub, c.idle)
        gnss == TrustState.TRUSTED -> Triple(R.string.hero_trusted, R.string.hero_trusted_sub, c.good)
        gnss == TrustState.QUESTIONABLE -> Triple(R.string.hero_questionable, R.string.hero_questionable_sub, c.warn)
        gnss == TrustState.REJECTED -> Triple(R.string.hero_rejected, R.string.hero_rejected_sub, c.bad)
        else -> Triple(R.string.hero_no_gnss, R.string.hero_no_gnss_sub, c.warn)
    }
    Card(
        Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.14f)),
        shape = RoundedCornerShape(24.dp),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(16.dp).background(tint, CircleShape))
            Column {
                Text(stringResource(title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(stringResource(sub), fontSize = 14.sp)
            }
        }
    }

    val elapsed = (SystemClock.elapsedRealtimeNanos() - s.startedElapsedNs) / 1_000_000_000
    val time = if (elapsed >= 3600) "%d:%02d:%02d".format(elapsed / 3600, elapsed % 3600 / 60, elapsed % 60) else "%d:%02d".format(elapsed / 60, elapsed % 60)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s.mode.estimate) {
            Metric(e?.let { stringResource(R.string.accuracy_value, it.accuracyM) } ?: "–", stringResource(R.string.metric_accuracy))
            Metric(e?.speedMps?.let { stringResource(R.string.kmh_value, it * 3.6) } ?: "–", stringResource(R.string.metric_speed))
        } else {
            Metric("${s.satsUsed}/${s.satsVisible}", stringResource(R.string.metric_satellites))
            Metric(s.counts.values.sum().toString(), stringResource(R.string.metric_records))
        }
        Metric(time, stringResource(R.string.metric_time))
    }

    if (s.mode.estimate) {
        Text(stringResource(R.string.evidence_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            EvidenceChip(
                stringResource(R.string.ev_gps),
                when (gnss) { TrustState.TRUSTED -> Evidence.ON; TrustState.QUESTIONABLE -> Evidence.WARN; else -> Evidence.OFF },
            )
            EvidenceChip(
                stringResource(R.string.ev_network),
                when (s.sourceStates[LocSource.NETWORK]) { TrustState.TRUSTED, TrustState.QUESTIONABLE -> Evidence.ON; else -> Evidence.OFF },
            )
            s.obd?.let { EvidenceChip(stringResource(R.string.ev_obd), if (it.state == ObdState.POLLING) Evidence.ON else Evidence.WARN) }
            if (s.roadMap != null) EvidenceChip(
                stringResource(R.string.ev_roads),
                if (e?.road?.let { it.pOffRoad <= 0.5 && it.probability > 0.5 } == true) Evidence.ON else Evidence.OFF,
            )
            s.compass?.let {
                EvidenceChip(
                    stringResource(R.string.ev_compass),
                    when (it.quality.verdict) { CompassVerdict.USABLE -> Evidence.ON; CompassVerdict.MARGINAL -> Evidence.WARN; else -> Evidence.OFF },
                )
            }
        }
        val reasons = s.lastTrust[gnssSource]?.reasons.orEmpty()
        if (gnss != null && gnss != TrustState.TRUSTED && reasons.isNotEmpty()) {
            Text(stringResource(R.string.why_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            reasons.take(3).forEach { Text("• " + stringResource(reasonText(it)), fontSize = 14.sp) }
        }
    }

    Text(stringResource(R.string.annotate_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // The recorded label is a stable English code; only the button text is localized.
        listOf(
            "mark" to R.string.ann_mark, "tunnel" to R.string.ann_tunnel,
            "jamming?" to R.string.ann_jamming, "spoofing?" to R.string.ann_spoofing,
        ).forEach { (code, res) ->
            val text = stringResource(res)
            val done = stringResource(R.string.annotated, text)
            FilledTonalButton(
                onClick = {
                    DriveService.annotate(ctx, code)
                    Toast.makeText(ctx, done, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.height(52.dp),
            ) { Text(text, fontSize = 15.sp) }
        }
    }
}

@Composable
private fun RowScope.Metric(value: String, label: String) {
    Surface(Modifier.weight(1f), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(vertical = 12.dp, horizontal = 8.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 1)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** The symbol repeats the colour, so the state also reads without it. */
@Composable
private fun EvidenceChip(label: String, state: Evidence) {
    val c = LocalStatusColors.current
    val (symbol, tint) = when (state) {
        Evidence.ON -> "✓" to c.good
        Evidence.WARN -> "!" to c.warn
        Evidence.OFF -> "–" to c.idle
    }
    Surface(shape = RoundedCornerShape(50), color = tint.copy(alpha = 0.16f)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(symbol, color = tint, fontWeight = FontWeight.Bold)
            Text(label, fontSize = 14.sp, color = if (state == Evidence.OFF) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified)
        }
    }
}
