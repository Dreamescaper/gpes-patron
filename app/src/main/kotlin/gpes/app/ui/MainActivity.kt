package gpes.app.ui

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gpes.app.R
import gpes.app.service.LiveStatus

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { GpesTheme { Screen() } }
    }
}

internal fun isMockAppSelected(ctx: Context): Boolean {
    val ops = ctx.getSystemService(AppOpsManager::class.java)
    return try {
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
        false
    }
}

private enum class Tab(val label: Int, val glyph: String) {
    DRIVE(R.string.tab_drive, "◉"),
    TRIPS(R.string.tab_trips, "☰"),
    DIAGNOSTICS(R.string.tab_diagnostics, "∿"),
    SETTINGS(R.string.tab_settings, "⚙"),
}

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val status by LiveStatus.flow.collectAsStateWithLifecycle()
    val settings = remember { AppSettings(ctx.getSharedPreferences("gpes", Context.MODE_PRIVATE)) }
    var tab by rememberSaveable { mutableIntStateOf(Tab.DRIVE.ordinal) }
    // Bumped when something outside Compose state changed (permissions, stopped session) so lists and checks re-read.
    var refresh by remember { mutableIntStateOf(0) }

    val perms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    LaunchedEffect(Unit) { launcher.launch(perms) }

    // A driver glances at the phone: keep it awake while a drive is running on the Drive tab.
    val keepAwake = status.running && tab == Tab.DRIVE.ordinal
    val activity = ctx as? Activity
    DisposableEffect(keepAwake) {
        if (keepAwake) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t.ordinal,
                        onClick = { tab = t.ordinal },
                        icon = { Text(t.glyph, fontSize = 20.sp) },
                        label = { Text(stringResource(t.label), fontWeight = if (tab == t.ordinal) FontWeight.Bold else FontWeight.Normal) },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (Tab.entries[tab]) {
            Tab.DRIVE -> DriveTab(m, settings, status, refresh) { refresh++ }
            Tab.TRIPS -> TripsTab(m, refresh, status.running)
            Tab.DIAGNOSTICS -> DiagnosticsTab(m, status)
            Tab.SETTINGS -> SettingsTab(m, settings, status.running)
        }
    }
}
