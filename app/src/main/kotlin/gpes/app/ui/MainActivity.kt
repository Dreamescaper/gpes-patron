package gpes.app.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import gpes.app.R
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import gpes.app.mock.MockTarget
import gpes.app.source.ObdState
import gpes.core.estimator.CompassMode
import gpes.core.estimator.CompassVerdict
import gpes.core.model.EstimatorMode
import gpes.app.service.DriveService
import gpes.app.service.DriveStorage
import gpes.app.service.LiveStatus
import gpes.app.service.RunMode
import gpes.app.service.Status
import gpes.core.model.LocSource
import gpes.core.model.TrustState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }
}

private fun isMockAppSelected(ctx: Context): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    return try {
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }
}

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val status by LiveStatus.flow.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(RunMode.RECORD_ONLY) }
    var fused by rememberSaveable { mutableStateOf(true) }
    var gps by rememberSaveable { mutableStateOf(false) }
    var network by rememberSaveable { mutableStateOf(false) }
    var useQuestionable by rememberSaveable { mutableStateOf(false) }
    val prefs = remember { ctx.getSharedPreferences("gpes", Context.MODE_PRIVATE) }
    var obdEnabled by remember { mutableStateOf(prefs.getBoolean("obd_enabled", false)) }
    var obdAddress by remember { mutableStateOf(prefs.getString("obd_address", null)) }
    var roadsEnabled by remember { mutableStateOf(prefs.getBoolean("roads_enabled", true)) }
    var refresh by remember { mutableIntStateOf(0) }

    val perms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    LaunchedEffect(Unit) { launcher.launch(perms) }

    Column(
        Modifier.safeDrawingPadding().padding(12.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodySmall)

        Card {
            Column(Modifier.padding(12.dp)) {
                Text(stringResource(R.string.mode_title), fontWeight = FontWeight.Bold)
                RunMode.entries.forEach { m ->
                    Row(Modifier.fillMaxWidth().selectable(selected = mode == m, enabled = !status.running) { mode = m }) {
                        RadioButton(selected = mode == m, onClick = null, enabled = !status.running)
                        Text(stringResource(m.labelRes), Modifier.padding(start = 8.dp))
                    }
                }
                if (mode.estimate) {
                    CheckRow(stringResource(R.string.use_questionable), useQuestionable, !status.running) { useQuestionable = it }
                    CheckRow(stringResource(R.string.roads_use), roadsEnabled, !status.running) {
                        roadsEnabled = it; prefs.edit().putBoolean("roads_enabled", it).apply()
                    }
                    Text(stringResource(R.string.roads_hint), fontSize = 12.sp)
                }
                if (mode == RunMode.MOCK_OUTPUT) {
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.mock_targets), fontWeight = FontWeight.Bold)
                    CheckRow(stringResource(R.string.target_fused), fused, !status.running) { fused = it }
                    CheckRow(stringResource(R.string.target_gps), gps, !status.running) { gps = it }
                    CheckRow(stringResource(R.string.target_network), network, !status.running) { network = it }
                    val selected = remember(refresh, status.running) { isMockAppSelected(ctx) }
                    if (!selected) {
                        Text(
                            stringResource(R.string.mock_app_not_selected, ctx.packageName),
                            color = Color(0xFFB00020), fontSize = 12.sp,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (!status.running) {
                    Button(onClick = {
                        val targets = buildSet {
                            if (fused) add(MockTarget.FUSED)
                            if (gps) add(MockTarget.GPS)
                            if (network) add(MockTarget.NETWORK)
                        }
                        DriveService.start(ctx, mode, targets, useQuestionable, obdAddress.takeIf { obdEnabled }, roadsEnabled)
                    }) { Text(stringResource(R.string.start)) }
                } else {
                    Button(onClick = { DriveService.stop(ctx); refresh++ }) { Text(stringResource(R.string.stop)) }
                }
            }
        }

        ObdCard(
            enabled = obdEnabled, address = obdAddress, locked = status.running,
            onEnabled = { obdEnabled = it; prefs.edit().putBoolean("obd_enabled", it).apply() },
            onAddress = { obdAddress = it; prefs.edit().putString("obd_address", it).apply() },
        )

        if (status.running) {
            LivePanel(status)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // The recorded label is a stable English code; only the button text is localized.
                listOf(
                    "mark" to R.string.ann_mark, "tunnel" to R.string.ann_tunnel,
                    "jamming?" to R.string.ann_jamming, "spoofing?" to R.string.ann_spoofing,
                ).forEach { (code, res) ->
                    val text = stringResource(res)
                    val done = stringResource(R.string.annotated, text)
                    OutlinedButton(onClick = {
                        DriveService.annotate(ctx, code)
                        Toast.makeText(ctx, done, Toast.LENGTH_SHORT).show()
                    }) { Text(text, fontSize = 12.sp) }
                }
            }
        }

        Sessions(refresh, status.running)
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun ObdCard(enabled: Boolean, address: String?, locked: Boolean, onEnabled: (Boolean) -> Unit, onAddress: (String) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(Build.VERSION.SDK_INT < 31 || ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Card {
        Column(Modifier.padding(12.dp)) {
            Text(stringResource(R.string.obd_title), fontWeight = FontWeight.Bold)
            CheckRow(stringResource(R.string.obd_use), enabled, !locked, onEnabled)
            if (!enabled) return@Column
            if (!granted) {
                OutlinedButton(onClick = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text(stringResource(R.string.obd_grant)) }
                return@Column
            }
            val devices = remember(granted) {
                runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList() }.getOrNull().orEmpty()
                    .sortedBy { it.name ?: it.address }
            }
            Text(stringResource(R.string.obd_pair_hint), fontSize = 12.sp)
            if (devices.isEmpty()) Text(stringResource(R.string.obd_no_paired), fontSize = 12.sp)
            devices.forEach { d ->
                Row(Modifier.fillMaxWidth().selectable(selected = address == d.address, enabled = !locked) { onAddress(d.address) }) {
                    RadioButton(selected = address == d.address, onClick = null, enabled = !locked)
                    Text("${d.name ?: "?"}  ${d.address}", Modifier.padding(start = 8.dp), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled, modifier = Modifier.padding(12.dp))
        Text(label, Modifier.padding(top = 12.dp), fontSize = 13.sp)
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

private fun trustLabel(s: TrustState) = when (s) {
    TrustState.TRUSTED -> R.string.trust_trusted
    TrustState.QUESTIONABLE -> R.string.trust_questionable
    TrustState.REJECTED -> R.string.trust_rejected
    TrustState.UNAVAILABLE -> R.string.trust_unavailable
}

private fun modeLabel(m: EstimatorMode) = when (m) {
    EstimatorMode.UNINITIALIZED -> R.string.est_uninitialized
    EstimatorMode.COARSE_ONLY -> R.string.est_coarse_only
    EstimatorMode.GNSS_TRACKING -> R.string.est_gnss_tracking
    EstimatorMode.DEAD_RECKONING -> R.string.est_dead_reckoning
    EstimatorMode.STATIONARY -> R.string.est_stationary
}

private fun stateColor(s: TrustState?) = when (s) {
    TrustState.TRUSTED -> Color(0xFF2E7D32)
    TrustState.QUESTIONABLE -> Color(0xFFF9A825)
    TrustState.REJECTED -> Color(0xFFC62828)
    else -> Color.Gray
}

@Composable
private fun LivePanel(s: Status) {
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
            s.compass?.let { c ->
                val verdict = when (c.quality.verdict) {
                    CompassVerdict.UNKNOWN -> R.string.compass_verdict_unknown
                    CompassVerdict.USABLE -> R.string.compass_verdict_usable
                    CompassVerdict.MARGINAL -> R.string.compass_verdict_marginal
                    CompassVerdict.UNUSABLE -> R.string.compass_verdict_unusable
                }
                val reading = c.reading?.let { r ->
                    val mode = when (r.mode) {
                        CompassMode.GNSS_ALIGNED -> R.string.compass_mode_gnss
                        CompassMode.FORWARD_ALIGNED -> R.string.compass_mode_forward
                        CompassMode.UNCORRECTED -> R.string.compass_mode_uncorrected
                    }
                    stringResource(R.string.compass_reading, Math.toDegrees(r.bearingRad), Math.toDegrees(r.sigmaRad), stringResource(mode))
                } ?: stringResource(R.string.compass_no_reading)
                Text(
                    stringResource(R.string.compass_line, stringResource(verdict), reading, c.quality.reasons.joinToString(",")),
                    color = when (c.quality.verdict) {
                        CompassVerdict.USABLE -> Color(0xFF2E7D32)
                        CompassVerdict.MARGINAL -> Color(0xFFF9A825)
                        CompassVerdict.UNUSABLE -> Color(0xFFC62828)
                        else -> Color.Gray
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
                    color = if (o.state == ObdState.POLLING) Color(0xFF2E7D32) else Color(0xFFF9A825), fontSize = 12.sp,
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
                    color = if (r.lastError != null) Color(0xFFF9A825) else Color.Unspecified, fontSize = 12.sp,
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
                s.mockError?.let { Text(it, color = Color(0xFFC62828), fontSize = 12.sp) }
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

@Composable
private fun Sessions(refresh: Int, running: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    // (message resource, argument), rendered with stringResource so it follows configuration changes.
    var busy by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val files = remember(refresh, running) { DriveStorage.list(ctx) }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.recorded_drives, files.size), fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.stored_in, DriveStorage.dir(ctx).toString()), fontSize = 11.sp)
            busy?.let { (res, arg) -> Text(stringResource(res, arg), fontSize = 12.sp) }
            files.forEachIndexed { i, f ->
                val isCurrent = running && i == 0
                Text(
                    stringResource(R.string.drive_size, f.name, f.length() / 1e6) + if (isCurrent) " " + stringResource(R.string.drive_recording) else "",
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                )
                if (!isCurrent) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { share(ctx, listOf(f)) }) { Text(stringResource(R.string.share_db), fontSize = 12.sp) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            busy = R.string.exporting to f.name
                            val out = runCatching { withContext(Dispatchers.IO) { DriveStorage.export(ctx, f) } }
                            busy = out.exceptionOrNull()?.let { R.string.export_failed to it.message.orEmpty() }
                            out.getOrNull()?.let { share(ctx, it) }
                        }
                    }) { Text(stringResource(R.string.export_jsonl), fontSize = 12.sp) }
                }
            }
        }
    }
}

private fun share(ctx: Context, files: List<File>) {
    val uris = ArrayList<Uri>(files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it) })
    val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
        type = "application/octet-stream"
        if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris[0]) else putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(intent, ctx.getString(R.string.share_drive)))
}
