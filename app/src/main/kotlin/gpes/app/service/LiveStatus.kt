package gpes.app.service

import androidx.annotation.StringRes
import gpes.app.R
import gpes.app.mock.MockTarget
import gpes.core.estimator.CompassStatus
import gpes.app.source.ObdStatus
import gpes.app.road.RoadMapStatus
import gpes.core.model.LocSource
import gpes.core.model.PositionEstimate
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class RunMode(@StringRes val labelRes: Int, val estimate: Boolean, val mock: Boolean) {
    RECORD_ONLY(R.string.mode_record_only, false, false),
    ESTIMATE_ONLY(R.string.mode_estimate_only, true, false),
    MOCK_OUTPUT(R.string.mode_mock_output, true, true),
}

data class Status(
    val running: Boolean = false,
    val mode: RunMode = RunMode.RECORD_ONLY,
    val mockTargets: Set<MockTarget> = emptySet(),
    val sessionId: String? = null,
    val startedElapsedNs: Long = 0,
    val counts: Map<String, Long> = emptyMap(),
    val ratesHz: Map<String, Double> = emptyMap(),
    val sourceStates: Map<LocSource, TrustState> = emptyMap(),
    val lastTrust: Map<LocSource, TrustAssessment> = emptyMap(),
    val estimate: PositionEstimate? = null,
    val satsUsed: Int = 0,
    val satsVisible: Int = 0,
    val meanCn0: Double? = null,
    val gnssMeasurements: String = "unknown",
    val mockPublished: Long = 0,
    val mockError: String? = null,
    val lateMeasurements: Long = 0,
    /** (cell count, serving cell description) of the latest cell scan. */
    val cells: Pair<Int, String?>? = null,
    /** (AP count in latest emission, its age in s). */
    val wifiAps: Pair<Int, Long>? = null,
    /** (scans requested OK, requests throttled). */
    val wifiScans: Pair<Int, Int> = 0 to 0,
    val compass: CompassStatus? = null,
    val obd: ObdStatus? = null,
    /** Speedometer scale error estimate (fraction, std). */
    val speedScale: Pair<Double, Double>? = null,
    /** Road tiles (null when the road map is off). */
    val roadMap: RoadMapStatus? = null,
    val notes: List<String> = emptyList(),
)

/** Process-wide live status, observed by the UI. The service is the only writer. */
object LiveStatus {
    private val state = MutableStateFlow(Status())
    val flow: StateFlow<Status> = state

    fun set(s: Status) { state.value = s }
    fun update(f: (Status) -> Status) = state.update(f)
}
