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
import gpes.app.mock.MockTarget
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
        Text("GPES Patron", style = MaterialTheme.typography.headlineSmall)
        Text("GNSS-resilient location research. GNSS is treated as untrusted evidence.", style = MaterialTheme.typography.bodySmall)

        Card {
            Column(Modifier.padding(12.dp)) {
                Text("Mode", fontWeight = FontWeight.Bold)
                RunMode.entries.forEach { m ->
                    Row(Modifier.fillMaxWidth().selectable(selected = mode == m, enabled = !status.running) { mode = m }) {
                        RadioButton(selected = mode == m, onClick = null, enabled = !status.running)
                        Text(m.label, Modifier.padding(start = 8.dp))
                    }
                }
                if (mode.estimate) {
                    CheckRow("Also fuse QUESTIONABLE GNSS (noise ×4; for emulators/quirky devices)", useQuestionable, !status.running) { useQuestionable = it }
                }
                if (mode == RunMode.MOCK_OUTPUT) {
                    Spacer(Modifier.height(4.dp))
                    Text("Mock targets", fontWeight = FontWeight.Bold)
                    CheckRow("Fused (Google Maps; keeps real GPS input)", fused, !status.running) { fused = it }
                    CheckRow("Platform GPS (overrides real GNSS Location input!)", gps, !status.running) { gps = it }
                    CheckRow("Platform network", network, !status.running) { network = it }
                    val selected = remember(refresh, status.running) { isMockAppSelected(ctx) }
                    if (!selected) {
                        Text(
                            "This app is not the selected mock location app. Developer options → Select mock location app, or:\n" +
                                "adb shell appops set ${ctx.packageName} android:mock_location allow",
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
                        DriveService.start(ctx, mode, targets, useQuestionable)
                    }) { Text("Start") }
                } else {
                    Button(onClick = { DriveService.stop(ctx); refresh++ }) { Text("Stop") }
                }
            }
        }

        if (status.running) {
            LivePanel(status)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("mark", "tunnel", "jamming?", "spoofing?").forEach { label ->
                    OutlinedButton(onClick = {
                        DriveService.annotate(ctx, label)
                        Toast.makeText(ctx, "annotated: $label", Toast.LENGTH_SHORT).show()
                    }) { Text(label, fontSize = 12.sp) }
                }
            }
        }

        Sessions(refresh, status.running)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row { Checkbox(checked, onChange, enabled = enabled); Text(label, Modifier.padding(top = 12.dp), fontSize = 13.sp) }
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
            Text("Session ${s.sessionId} · ${s.mode.label} · ${elapsed / 60}m${elapsed % 60}s", fontWeight = FontWeight.Bold)
            HorizontalDivider()
            Text("Sources", fontWeight = FontWeight.Bold)
            for (src in listOf(LocSource.GNSS, LocSource.FUSED, LocSource.NETWORK)) {
                val st = s.sourceStates[src]
                val a = s.lastTrust[src]
                Text(
                    "${src.name.padEnd(8)} ${st?.name ?: "–"}  ${a?.reasons?.joinToString(",").orEmpty()}",
                    color = stateColor(st), fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                )
            }
            Text(
                "Sats used/visible ${s.satsUsed}/${s.satsVisible}  mean C/N0 ${s.meanCn0?.let { "%.1f".format(it) } ?: "–"} dB-Hz  raw: ${s.gnssMeasurements}",
                fontSize = 12.sp,
            )
            Text(
                "Cells: ${s.cells?.first ?: 0}  serving: ${s.cells?.second ?: "–"}\n" +
                    "Wi-Fi: ${s.wifiAps?.let { "${it.first} APs, ${it.second}s ago" } ?: "–"}  scans ok/throttled ${s.wifiScans.first}/${s.wifiScans.second}",
                fontSize = 12.sp,
            )
            HorizontalDivider()
            val e = s.estimate
            if (s.mode.estimate) {
                Text("Estimate", fontWeight = FontWeight.Bold)
                if (e == null) Text("uninitialized (no usable position evidence yet)", fontSize = 12.sp)
                else Text(
                    "%s  %.6f, %.6f  ±%.0f m\nheading %s  speed %s".format(
                        e.mode, e.lat, e.lon, e.accuracyM,
                        e.headingRad?.let { "%.0f°±%.0f".format(Math.toDegrees(it), Math.toDegrees(e.headingStdRad ?: 0.0)) } ?: "unknown",
                        e.speedMps?.let { "%.1f±%.1f m/s".format(it, e.speedStdMps ?: 0.0) } ?: "unknown",
                    ),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                )
            }
            if (s.mode.mock) {
                Text("Mock: published ${s.mockPublished} to ${s.mockTargets.joinToString()}", fontSize = 12.sp)
                s.mockError?.let { Text(it, color = Color(0xFFC62828), fontSize = 12.sp) }
            }
            HorizontalDivider()
            Text("Recorded (rate Hz)", fontWeight = FontWeight.Bold)
            Text(
                s.counts.entries.sortedBy { it.key }.joinToString("\n") { (k, v) -> "%-24s %8d  %6.1f".format(k, v, s.ratesHz[k] ?: 0.0) },
                fontFamily = FontFamily.Monospace, fontSize = 11.sp,
            )
            if (s.lateMeasurements > 0) Text("late measurements: ${s.lateMeasurements}", fontSize = 11.sp)
        }
    }
}

@Composable
private fun Sessions(refresh: Int, running: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    val files = remember(refresh, running) { DriveStorage.list(ctx) }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Recorded drives (${files.size})", fontWeight = FontWeight.Bold)
            Text("Stored in ${DriveStorage.dir(ctx)}", fontSize = 11.sp)
            busy?.let { Text(it, fontSize = 12.sp) }
            files.forEachIndexed { i, f ->
                val isCurrent = running && i == 0
                Text("${f.name}  ${"%.1f".format(f.length() / 1e6)} MB${if (isCurrent) " (recording)" else ""}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                if (!isCurrent) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { share(ctx, listOf(f)) }) { Text("Share .db", fontSize = 12.sp) }
                    OutlinedButton(onClick = {
                        scope.launch {
                            busy = "Exporting ${f.name}…"
                            val out = runCatching { withContext(Dispatchers.IO) { DriveStorage.export(ctx, f) } }
                            busy = out.exceptionOrNull()?.let { "Export failed: ${it.message}" }
                            out.getOrNull()?.let { share(ctx, it) }
                        }
                    }) { Text("Export JSONL + GnssLogger", fontSize = 12.sp) }
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
    ctx.startActivity(Intent.createChooser(intent, "Share drive"))
}
