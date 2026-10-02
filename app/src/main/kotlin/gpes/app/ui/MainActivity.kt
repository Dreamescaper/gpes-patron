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
import gpes.app.service.DriveStorage
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
    MAP(R.string.tab_map, "◈"),
    TRIPS(R.string.tab_trips, "☰"),
    DIAGNOSTICS(R.string.tab_diagnostics, "∿"),
    SETTINGS(R.string.tab_settings, "⚙"),
}

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val status by LiveStatus.flow.collectAsStateWithLifecycle()
    val settings = remember { AppSettings(ctx.getSharedPreferences("gpes", Context.MODE_PRIVATE)) }
    var tabIndex by rememberSaveable { mutableIntStateOf(Tab.DRIVE.ordinal) }
    // Bumped when something outside Compose state changed (permissions, stopped session) so lists and checks re-read.
    var refresh by remember { mutableIntStateOf(0) }

    val perms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    LaunchedEffect(Unit) { launcher.launch(perms) }

    // Recordings and diagnostics are for the developer (D-053): shown only when asked for, or when files already exist.
    val hasDrives = remember(refresh, status.running) { DriveStorage.list(ctx).isNotEmpty() }
    val visible = Tab.entries.filter {
        when (it) {
            Tab.DRIVE, Tab.MAP, Tab.SETTINGS -> true
            Tab.TRIPS -> settings.record || settings.developer || hasDrives
            Tab.DIAGNOSTICS -> settings.developer
        }
    }
    val tab = Tab.entries[tabIndex].takeIf { it in visible } ?: Tab.DRIVE

    // A driver glances at the phone: keep it awake while a drive is running on the Drive or Map tab.
    val keepAwake = status.running && (tab == Tab.DRIVE || tab == Tab.MAP)
    val activity = ctx as? Activity
    DisposableEffect(keepAwake) {
        if (keepAwake) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar {
                visible.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tabIndex = t.ordinal },
                        icon = { Text(t.glyph, fontSize = 20.sp) },
                        label = {
                            // Five tabs in developer mode: one line, a little smaller.
                            Text(
                                stringResource(t.label), maxLines = 1, softWrap = false,
                                fontSize = if (visible.size > 4) 10.sp else 12.sp,
                                fontWeight = if (tab == t) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                    )
                }
            }
        },
    ) { padding ->
        val m = Modifier.padding(padding)
        when (tab) {
            Tab.DRIVE -> DriveTab(m, settings, status, refresh) { refresh++ }
            Tab.MAP -> MapTab(m, status)
            Tab.TRIPS -> TripsTab(m, refresh, status.running)
            Tab.DIAGNOSTICS -> DiagnosticsTab(m, status)
            Tab.SETTINGS -> SettingsTab(m, settings, status.running)
        }
    }
}
