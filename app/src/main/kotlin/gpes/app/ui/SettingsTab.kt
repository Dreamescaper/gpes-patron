package gpes.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gpes.app.BuildConfig
import gpes.app.R

/** Everything that is set once and rarely touched; locked while a drive is running. */
@Composable
fun SettingsTab(modifier: Modifier, settings: AppSettings, running: Boolean) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.tab_settings), style = MaterialTheme.typography.headlineSmall)
        if (running) Text(stringResource(R.string.settings_locked), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)

        SettingsCard(stringResource(R.string.settings_roads)) {
            SwitchRow(stringResource(R.string.roads_use), stringResource(R.string.roads_hint), settings.roads, !running) { settings.roads = it }
        }

        ObdCard(
            enabled = settings.obdEnabled, address = settings.obdAddress, locked = running,
            onEnabled = { settings.obdEnabled = it }, onAddress = { settings.obdAddress = it },
        )

        SettingsCard(stringResource(R.string.mock_targets)) {
            SwitchRow(stringResource(R.string.target_fused), null, settings.fused, !running) { settings.fused = it }
            SwitchRow(stringResource(R.string.target_gps), null, settings.gps, !running) { settings.gps = it }
            SwitchRow(stringResource(R.string.target_network), null, settings.network, !running) { settings.network = it }
            Text(stringResource(R.string.mock_targets_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SwitchRow(stringResource(R.string.probe_use), stringResource(R.string.probe_hint), settings.probe, !running) { settings.probe = it }
        }

        SettingsCard(stringResource(R.string.settings_advanced)) {
            SwitchRow(stringResource(R.string.use_questionable), null, settings.useQuestionable, !running) { settings.useQuestionable = it }
        }

        SettingsCard(stringResource(R.string.settings_about)) {
            Text(stringResource(R.string.app_name) + " " + BuildConfig.VERSION_NAME, fontWeight = FontWeight.Medium)
            Text(stringResource(R.string.tagline), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
internal fun SwitchRow(label: String, description: String?, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp)
            if (description != null) Text(description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
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
    SettingsCard(stringResource(R.string.obd_title)) {
        SwitchRow(stringResource(R.string.obd_use), null, enabled, !locked, onEnabled)
        if (!enabled) return@SettingsCard
        if (!granted) {
            OutlinedButton(onClick = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text(stringResource(R.string.obd_grant)) }
            return@SettingsCard
        }
        val devices = remember(granted) {
            runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices?.toList() }.getOrNull().orEmpty()
                .sortedBy { it.name ?: it.address }
        }
        Text(stringResource(R.string.obd_pair_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (devices.isEmpty()) Text(stringResource(R.string.obd_no_paired), fontSize = 12.sp)
        devices.forEach { d ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = address == d.address, enabled = !locked, role = Role.RadioButton) { onAddress(d.address) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = address == d.address, onClick = null, enabled = !locked)
                Text("${d.name ?: "?"}  ${d.address}", Modifier.padding(start = 8.dp), fontSize = 14.sp)
            }
        }
    }
}
