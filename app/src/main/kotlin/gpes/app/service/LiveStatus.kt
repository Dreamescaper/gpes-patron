package gpes.app.service

import gpes.app.mock.MockTarget
import gpes.core.model.LocSource
import gpes.core.model.PositionEstimate
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class RunMode(val label: String, val estimate: Boolean, val mock: Boolean) {
    RECORD_ONLY("Record only", false, false),
    ESTIMATE_ONLY("Estimate only", true, false),
    MOCK_OUTPUT("Mock location output", true, true),
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
    val notes: List<String> = emptyList(),
)

/** Process-wide live status, observed by the UI. The service is the only writer. */
object LiveStatus {
    private val state = MutableStateFlow(Status())
    val flow: StateFlow<Status> = state

    fun set(s: Status) { state.value = s }
    fun update(f: (Status) -> Status) = state.update(f)
}
