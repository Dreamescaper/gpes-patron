package gpes.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import gpes.app.R
import gpes.app.service.DriveStorage
import gpes.app.service.TripSummaries
import gpes.app.service.TripSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Recordings, one compact row each. A long press starts selecting; then a tap toggles a row, and the header offers
 * share and delete for the whole selection. The recording in progress cannot be selected.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun TripsTab(modifier: Modifier, refresh: Int, running: Boolean) {
    val ctx = LocalContext.current
    var changed by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var openTrip by remember { mutableStateOf<Pair<File, TripSummary>?>(null) }
    val files = remember(refresh, changed, running) { DriveStorage.list(ctx) }
    val current = if (running) files.firstOrNull()?.name else null
    val selectable = files.filter { it.name != current }
    // Files that are gone (deleted, or a finished session) drop out of the selection.
    val selected = picked.filter { n -> selectable.any { it.name == n } }.toSet()
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { picked = emptySet() }
    openTrip?.let { (file, summary) ->
        TripMapScreen(modifier, file, summary) { openTrip = null }
        return
    }

    Column(modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (selecting) {
                Text(stringResource(R.string.sel_count, selected.size), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { share(ctx, selectable.filter { it.name in selected }) }) { Text(stringResource(R.string.sel_share)) }
                    TextButton(onClick = { confirmDelete = true }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { picked = selectable.map { it.name }.toSet() }) { Text(stringResource(R.string.sel_all)) }
                    TextButton(onClick = { picked = emptySet() }) { Text(stringResource(R.string.cancel)) }
                }
            } else {
                Text(stringResource(R.string.recorded_drives, files.size), style = MaterialTheme.typography.headlineSmall)
                if (files.isNotEmpty()) Text(stringResource(R.string.trips_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider()
        if (files.isEmpty()) {
            Text(stringResource(R.string.trips_empty), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.weight(1f)) {
            items(files, key = { it.name }) { f ->
                val isCurrent = f.name == current
                val isSelected = f.name in selected
                // Read lazily, as the row scrolls into view; the recording in progress has no finished route to show yet.
                val summary by produceState<TripSummary?>(null, f.name, f.length(), f.lastModified(), isCurrent) {
                    value = if (isCurrent) null else withContext(Dispatchers.IO) { TripSummaries.load(ctx, f) }
                }
                val toggle = { picked = if (isSelected) selected - f.name else selected + f.name }
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.background)
                        .combinedClickable(
                            enabled = !isCurrent,
                            onClick = { if (selecting) toggle() else summary?.takeIf { it.hasRoute }?.let { openTrip = f to it } },
                            onLongClick = { toggle() },
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (selecting) Checkbox(checked = isSelected, onCheckedChange = null, enabled = !isCurrent)
                    RouteThumb(summary?.route)
                    Column(Modifier.weight(1f)) {
                        Text(driveTitle(f), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        if (isCurrent) Text(stringResource(R.string.drive_recording), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        else summary?.let { Text(tripStats(it), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Text(stringResource(R.string.size_mb, f.length() / 1e6), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
        Text(
            stringResource(R.string.stored_in, DriveStorage.dir(ctx).toString()),
            Modifier.padding(16.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (confirmDelete) {
        val victims = selectable.filter { it.name in selected }
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(pluralStringResource(R.plurals.delete_batch_title, victims.size, victims.size)) },
            text = { Text(stringResource(R.string.delete_batch_text, victims.sumOf { it.length() } / 1e6)) },
            confirmButton = {
                TextButton(onClick = {
                    victims.forEach(DriveStorage::delete)
                    confirmDelete = false; picked = emptySet(); changed++
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** "yyyyMMdd-HHmmss.db" → a localized date and time; the file name when it does not parse. */
internal fun driveTitle(f: File): String {
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
