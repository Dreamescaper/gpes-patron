package gpes.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import gpes.app.R
import gpes.app.service.DriveStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripsTab(modifier: Modifier, refresh: Int, running: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var changed by remember { mutableIntStateOf(0) }
    // (message resource, argument), rendered with stringResource so it follows configuration changes.
    var busy by remember { mutableStateOf<Pair<Int, String>?>(null) }
    var toDelete by remember { mutableStateOf<File?>(null) }
    val files = remember(refresh, changed, running) { DriveStorage.list(ctx) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.recorded_drives, files.size), style = MaterialTheme.typography.headlineSmall)
        busy?.let { (res, arg) -> Text(stringResource(res, arg), fontSize = 13.sp) }
        if (files.isEmpty()) Text(stringResource(R.string.trips_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        files.forEachIndexed { i, f ->
            val isCurrent = running && i == 0
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(driveTitle(f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                    Text(
                        stringResource(R.string.drive_size, f.name, f.length() / 1e6) + if (isCurrent) " " + stringResource(R.string.drive_recording) else "",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!isCurrent) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        OutlinedButton(onClick = { share(ctx, listOf(f)) }) { Text(stringResource(R.string.share_db), fontSize = 12.sp) }
                        OutlinedButton(onClick = {
                            scope.launch {
                                busy = R.string.exporting to f.name
                                val out = runCatching { withContext(Dispatchers.IO) { DriveStorage.export(ctx, f) } }
                                busy = out.exceptionOrNull()?.let { R.string.export_failed to it.message.orEmpty() }
                                out.getOrNull()?.let { share(ctx, it) }
                            }
                        }) { Text(stringResource(R.string.export_jsonl), fontSize = 12.sp) }
                        TextButton(onClick = { toDelete = f }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        Text(stringResource(R.string.stored_in, DriveStorage.dir(ctx).toString()), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    toDelete?.let { f ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_text, driveTitle(f))) },
            confirmButton = {
                TextButton(onClick = { DriveStorage.delete(f); toDelete = null; changed++ }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** "yyyyMMdd-HHmmss.db" → a localized date and time; the file name when it does not parse. */
private fun driveTitle(f: File): String {
    val t = runCatching { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(f.nameWithoutExtension) }.getOrNull() ?: return f.name
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(t)
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
