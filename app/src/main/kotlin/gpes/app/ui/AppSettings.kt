package gpes.app.ui

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import gpes.app.service.RunMode

/** User choices that survive restarts. Observable from Compose; every setter writes through to preferences. */
class AppSettings(private val prefs: SharedPreferences) {
    private var modeState by mutableStateOf(RunMode.entries.firstOrNull { it.name == prefs.getString("mode", null) } ?: RunMode.RECORD_ONLY)
    private var fusedState by mutableStateOf(prefs.getBoolean("target_fused", false))
    private var gpsState by mutableStateOf(prefs.getBoolean("target_gps", true))
    private var networkState by mutableStateOf(prefs.getBoolean("target_network", false))
    private var questionableState by mutableStateOf(prefs.getBoolean("use_questionable", false))
    private var obdEnabledState by mutableStateOf(prefs.getBoolean("obd_enabled", false))
    private var obdAddressState by mutableStateOf(prefs.getString("obd_address", null))
    private var recordState by mutableStateOf(prefs.getBoolean("record", false))
    private var developerState by mutableStateOf(prefs.getBoolean("developer", false))
    private var probeState by mutableStateOf(prefs.getBoolean("gps_probe", true))
    private var roadsState by mutableStateOf(prefs.getBoolean("roads_enabled", true))

    var mode: RunMode
        get() = modeState
        set(v) { modeState = v; prefs.edit().putString("mode", v.name).apply() }
    var fused: Boolean
        get() = fusedState
        set(v) { fusedState = v; prefs.edit().putBoolean("target_fused", v).apply() }
    var gps: Boolean
        get() = gpsState
        set(v) { gpsState = v; prefs.edit().putBoolean("target_gps", v).apply() }
    var network: Boolean
        get() = networkState
        set(v) { networkState = v; prefs.edit().putBoolean("target_network", v).apply() }
    var useQuestionable: Boolean
        get() = questionableState
        set(v) { questionableState = v; prefs.edit().putBoolean("use_questionable", v).apply() }
    var obdEnabled: Boolean
        get() = obdEnabledState
        set(v) { obdEnabledState = v; prefs.edit().putBoolean("obd_enabled", v).apply() }
    var obdAddress: String?
        get() = obdAddressState
        set(v) { obdAddressState = v; prefs.edit().putString("obd_address", v).apply() }
    /** Optional: save the drive to a file so it can be sent to the developer (D-053). */
    var record: Boolean
        get() = recordState
        set(v) { recordState = v; prefs.edit().putBoolean("record", v).apply() }
    /** Shows the developer's tools: diagnostics, start modes, event marks. */
    var developer: Boolean
        get() = developerState
        set(v) { developerState = v; prefs.edit().putBoolean("developer", v).apply() }
    /** Ordinary users only ever run the position replacement (estimation runs by itself, D-058); recording alone is a developer option. */
    val effectiveMode: RunMode get() = if (developerState && modeState == RunMode.RECORD_ONLY) RunMode.RECORD_ONLY else RunMode.MOCK_OUTPUT
    var probe: Boolean
        get() = probeState
        set(v) { probeState = v; prefs.edit().putBoolean("gps_probe", v).apply() }
    var roads: Boolean
        get() = roadsState
        set(v) { roadsState = v; prefs.edit().putBoolean("roads_enabled", v).apply() }
}
