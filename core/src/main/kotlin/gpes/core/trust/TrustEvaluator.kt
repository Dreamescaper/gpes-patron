package gpes.core.trust

import gpes.core.geo.Geo
import gpes.core.geo.LocalFrame
import gpes.core.model.Cov2
import gpes.core.model.GnssStatusSnapshot
import gpes.core.model.LocSource
import gpes.core.model.LocationMeasurement
import gpes.core.model.Measurement
import gpes.core.model.PositionEstimate
import gpes.core.model.ProviderEvent
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustReason
import gpes.core.model.TrustState
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Read-only view of IMU-derived motion that trust checks may consult. */
interface MotionView {
    fun stationaryForS(): Double
    /** Bearing change measured by the gyro between t1 and t2 (rad, clockwise positive), or null. */
    fun bearingChange(t1: Long, t2: Long): Double?
}

data class TrustContext(
    /** The estimator's prediction at the measurement time. It excludes the measurement being assessed. */
    val predicted: PositionEstimate?,
    val motion: MotionView?,
)

interface LocationTrustEvaluator {
    /** Observe non-location measurements (GNSS status, provider events, ...). */
    fun observe(m: Measurement)

    fun assess(m: LocationMeasurement, ctx: TrustContext): TrustAssessment

    /** Availability of a source at time [tNs]: UNAVAILABLE if it is silent or overridden, else its last state. */
    fun sourceState(tNs: Long, source: LocSource): TrustState

    fun snapshot(): Any
    fun restore(snapshot: Any)
}

@Serializable
data class TrustConfig(
    val maxVehicleSpeedMps: Double = 70.0,
    val maxAccelMps2: Double = 8.0,
    val staleQuestionableS: Double = 2.0,
    val staleRejectS: Double = 10.0,
    val accQuestionableM: Double = 30.0,
    val accRejectM: Double = 150.0,
    val networkMaxAccM: Double = 5000.0,
    val networkStaleS: Double = 30.0,
    /** χ²(2 dof) gates on the innovation against the estimator prediction. */
    val gateQuestionableNis: Double = 13.8,
    val gateRejectNis: Double = 50.0,
    val courseCheckMinSpeedMps: Double = 5.0,
    val courseCheckMaxDtS: Double = 5.0,
    val courseMismatchDeg: Double = 25.0,
    val courseMismatchDegPerS: Double = 10.0,
    val stationaryMinS: Double = 3.0,
    val stationaryMaxSpeedMps: Double = 3.0,
    val networkMaxAgeS: Double = 120.0,
    val networkK: Double = 2.0,
    val networkImpossibleM: Double = 20_000.0,
    val minSatsUsed: Int = 4,
    val cn0UniformStdDb: Double = 1.0,
    val cn0UniformMinSats: Int = 6,
    /** Consecutive clean fixes required after a rejection before TRUSTED again. */
    val recoveryConsecutive: Int = 5,
    /**
     * A self-consistent stream of rejected fixes lasting this long is accepted as a reset, when nothing
     * independent (network) contradicts it. This is PX4-style reset-on-glitch. It is needed when DR has
     * drifted during a long outage, and it is also the point where a patient, consistent spoofer wins.
     * null disables it.
     */
    val resetAfterConsistentS: Double? = 120.0,
    /** Same as [resetAfterConsistentS], but when a fresh network fix agrees with the stream. */
    val resetWithNetworkS: Double? = 15.0,
    val unavailableAfterS: Double = 5.0,
    /** Baseline for comparing position displacement with integrated reported velocity. */
    val velocityWindowS: Double = 10.0,
    val velocityMismatchM: Double = 15.0,
)

/**
 * Phase 1 heuristic GNSS trust evaluator. Each check is independent and returns a reason with a
 * severity. The combined state is the worst severity, with PX4-like hysteresis on recovery.
 */
class DefaultTrustEvaluator(private val cfg: TrustConfig = TrustConfig()) : LocationTrustEvaluator {

    private data class SourceState(
        val prev: LocationMeasurement? = null,
        val lastTrusted: LocationMeasurement? = null,
        val lastState: TrustState = TrustState.UNAVAILABLE,
        val recoveryNeeded: Int = 0,
        val streamStart: LocationMeasurement? = null,
        val streamLast: LocationMeasurement? = null,
        /** Recent fixes of this source (any trust), newest last, for window consistency checks. */
        val recent: List<LocationMeasurement> = emptyList(),
    )

    private data class Snap(
        val sources: Map<LocSource, SourceState>,
        val lastNetwork: LocationMeasurement?,
        val lastStatus: GnssStatusSnapshot?,
        val overridden: Set<String>,
    )

    private data class Hit(val reason: TrustReason, val severity: TrustState, val factor: Double = 1.0)

    private val sources = HashMap<LocSource, SourceState>()
    private var lastNetwork: LocationMeasurement? = null
    private var lastStatus: GnssStatusSnapshot? = null
    private val overridden = HashSet<String>()

    override fun observe(m: Measurement) {
        when (m) {
            is GnssStatusSnapshot -> lastStatus = m
            is ProviderEvent -> when (m.event) {
                ProviderEvent.Kind.OVERRIDDEN -> overridden += m.provider
                ProviderEvent.Kind.RESTORED -> overridden -= m.provider
                else -> Unit
            }
            else -> Unit
        }
    }

    override fun assess(m: LocationMeasurement, ctx: TrustContext): TrustAssessment {
        val st = sources[m.source] ?: SourceState()
        val hits = ArrayList<Hit>()
        var nis: Double? = null
        var impliedSpeed: Double? = null
        var networkAgrees = false

        if (m.isSynthetic) hits += Hit(TrustReason.SYNTHETIC_INPUT, TrustState.REJECTED)
        if (m.provider in overridden) hits += Hit(TrustReason.SOURCE_OVERRIDDEN, TrustState.UNAVAILABLE)

        val latencyS = (m.receivedNs - m.tNs) / 1e9
        when (m.source) {
            LocSource.NETWORK -> {
                if (latencyS > cfg.networkStaleS) hits += Hit(TrustReason.STALE, TrustState.QUESTIONABLE, 0.5)
                val acc = m.hAccM
                if (acc == null) hits += Hit(TrustReason.NO_ACCURACY, TrustState.REJECTED)
                else if (acc > cfg.networkMaxAccM) hits += Hit(TrustReason.POOR_ACCURACY, TrustState.REJECTED)
            }
            LocSource.GNSS, LocSource.FUSED -> {
                if (latencyS > cfg.staleRejectS) hits += Hit(TrustReason.STALE, TrustState.REJECTED)
                else if (latencyS > cfg.staleQuestionableS) hits += Hit(TrustReason.STALE, TrustState.QUESTIONABLE, 0.7)

                val acc = m.hAccM
                if (acc == null) hits += Hit(TrustReason.NO_ACCURACY, TrustState.QUESTIONABLE, 0.5)
                else if (acc > cfg.accRejectM) hits += Hit(TrustReason.POOR_ACCURACY, TrustState.REJECTED)
                else if (acc > cfg.accQuestionableM) hits += Hit(TrustReason.POOR_ACCURACY, TrustState.QUESTIONABLE, 0.6)

                impliedSpeed = checkKinematics(m, st, hits)
                nis = checkInnovation(m, ctx.predicted, hits)
                checkCourse(m, st.prev, ctx.motion, hits)
                checkVelocityConsistency(m, st.recent, hits)
                checkStationary(m, ctx.motion, hits)
                networkAgrees = checkNetwork(m, hits)
                if (m.source == LocSource.GNSS) checkRawGnss(m, hits)
            }
            else -> Unit
        }

        var state = hits.maxOfOrNull { it.severity.ordinal }?.let { TrustState.entries[it] } ?: TrustState.TRUSTED
        var reasons = hits.map { it.reason }.toMutableSet()
        val cutoff = m.tNs - (cfg.velocityWindowS * 1.5 * 1e9).toLong()
        var newSt = st.copy(prev = m, recent = (st.recent + m).filter { it.tNs >= cutoff })

        // Hysteresis and reset-after-consistent-stream (GNSS-like sources only).
        if (m.source == LocSource.GNSS || m.source == LocSource.FUSED) {
            when (state) {
                TrustState.REJECTED -> {
                    val consistent = st.streamLast != null && isConsistent(st.streamLast, m)
                    val start = if (consistent) st.streamStart!! else m
                    newSt = newSt.copy(recoveryNeeded = cfg.recoveryConsecutive, streamStart = start, streamLast = m)
                    val resettable = setOf(
                        TrustReason.INNOVATION_GATE, TrustReason.IMPOSSIBLE_ACCELERATION,
                        TrustReason.RECOVERING, TrustReason.CN0_UNIFORM, TrustReason.COURSE_GYRO_MISMATCH, TrustReason.VELOCITY_POSITION_MISMATCH,
                    )
                    // Never reset onto a physically unreachable position (IMPOSSIBLE_VELOCITY is not resettable).
                    // Independent agreement from a coarse fix shortens the wait.
                    val reset = if (networkAgrees) cfg.resetWithNetworkS else cfg.resetAfterConsistentS
                    if (reset != null && reasons.all { it in resettable } &&
                        (m.tNs - start.tNs) / 1e9 >= reset
                    ) {
                        state = TrustState.TRUSTED
                        reasons = mutableSetOf(TrustReason.RESET_AFTER_CONSISTENT_STREAM)
                        newSt = newSt.copy(recoveryNeeded = 0, streamStart = null, streamLast = null)
                    }
                }
                TrustState.TRUSTED -> {
                    newSt = newSt.copy(streamStart = null, streamLast = null)
                    if (st.recoveryNeeded > 0) {
                        state = TrustState.QUESTIONABLE
                        reasons += TrustReason.RECOVERING
                        newSt = newSt.copy(recoveryNeeded = st.recoveryNeeded - 1)
                    }
                }
                else -> Unit
            }
        }

        if (state == TrustState.TRUSTED) newSt = newSt.copy(lastTrusted = m)
        newSt = newSt.copy(lastState = state)
        sources[m.source] = newSt
        if (m.source == LocSource.NETWORK && state != TrustState.REJECTED && !m.isSynthetic) lastNetwork = m

        val base = when (state) {
            TrustState.TRUSTED -> 1.0
            TrustState.QUESTIONABLE -> 0.5
            TrustState.REJECTED -> 0.05
            TrustState.UNAVAILABLE -> 0.0
        }
        val confidence = hits.fold(base) { acc, h -> acc * h.factor }.coerceIn(0.0, 1.0)
        return TrustAssessment(m.tNs, m.source, m.provider, state, confidence, reasons, nis, impliedSpeed)
    }

    override fun sourceState(tNs: Long, source: LocSource): TrustState {
        val st = sources[source] ?: return TrustState.UNAVAILABLE
        val prev = st.prev ?: return TrustState.UNAVAILABLE
        if (prev.provider in overridden) return TrustState.UNAVAILABLE
        if ((tNs - prev.tNs) / 1e9 > cfg.unavailableAfterS) return TrustState.UNAVAILABLE
        return st.lastState
    }

    // ------------------------------------------------------------------------------------------

    private fun allowance(a: LocationMeasurement, b: LocationMeasurement) =
        2.0 * ((a.hAccM ?: 50.0) + (b.hAccM ?: 50.0))

    private fun isConsistent(a: LocationMeasurement, b: LocationMeasurement): Boolean {
        val dt = (b.tNs - a.tNs) / 1e9
        if (dt <= 0 || dt > 10) return false
        val d = Geo.haversineM(a.lat, a.lon, b.lat, b.lon)
        return (d - allowance(a, b)).coerceAtLeast(0.0) / dt <= cfg.maxVehicleSpeedMps
    }

    private fun checkKinematics(m: LocationMeasurement, st: SourceState, hits: MutableList<Hit>): Double? {
        val lt = st.lastTrusted ?: return null
        val dt = (m.tNs - lt.tNs) / 1e9
        if (dt <= 0) return null
        val d = Geo.haversineM(lt.lat, lt.lon, m.lat, m.lon)
        val implied = (d - allowance(lt, m)).coerceAtLeast(0.0) / max(dt, 0.5)
        if (implied > cfg.maxVehicleSpeedMps) hits += Hit(TrustReason.IMPOSSIBLE_VELOCITY, TrustState.REJECTED)
        val s0 = lt.speedMps
        val s1 = m.speedMps
        if (s0 != null && s1 != null && dt <= 5.0 && abs(s1 - s0) / max(dt, 0.2) > cfg.maxAccelMps2) {
            hits += Hit(TrustReason.IMPOSSIBLE_ACCELERATION, TrustState.QUESTIONABLE, 0.6)
        }
        return implied
    }

    private fun checkInnovation(m: LocationMeasurement, pred: PositionEstimate?, hits: MutableList<Hit>): Double? {
        if (pred == null) return null
        val acc = m.hAccM ?: return null
        val sigma = acc / Cov2.R68_PER_SIGMA
        val r = sigma * sigma
        val v = LocalFrame(pred.lat, pred.lon).toEnu(m.lat, m.lon)
        val a = pred.cov.ee + r
        val b = pred.cov.en
        val c = pred.cov.nn + r
        val det = a * c - b * b
        if (det <= 0) return null
        val nis = (c * v.e * v.e - 2 * b * v.e * v.n + a * v.n * v.n) / det
        if (nis > cfg.gateRejectNis) hits += Hit(TrustReason.INNOVATION_GATE, TrustState.REJECTED)
        else if (nis > cfg.gateQuestionableNis) hits += Hit(TrustReason.INNOVATION_GATE, TrustState.QUESTIONABLE, 0.6)
        return nis
    }

    private fun checkCourse(m: LocationMeasurement, prev: LocationMeasurement?, motion: MotionView?, hits: MutableList<Hit>) {
        if (prev == null || motion == null) return
        val b0 = prev.bearingDeg ?: return
        val b1 = m.bearingDeg ?: return
        if ((prev.speedMps ?: 0.0) < cfg.courseCheckMinSpeedMps || (m.speedMps ?: 0.0) < cfg.courseCheckMinSpeedMps) return
        val dt = (m.tNs - prev.tNs) / 1e9
        if (dt <= 0 || dt > cfg.courseCheckMaxDtS) return
        val gyro = motion.bearingChange(prev.tNs, m.tNs) ?: return
        val gnss = Geo.wrapDeg(b1 - b0)
        val diff = abs(Geo.wrapDeg(gnss - Math.toDegrees(gyro)))
        if (diff > cfg.courseMismatchDeg + cfg.courseMismatchDegPerS * dt) {
            hits += Hit(TrustReason.COURSE_GYRO_MISMATCH, TrustState.QUESTIONABLE, 0.6)
        }
    }

    /**
     * Position displacement over ~[TrustConfig.velocityWindowS] should match the integral of the
     * reported (Doppler) velocity. A spoofer or transform that moves positions without matching
     * velocity shows up here long before the implied-speed check notices.
     */
    private fun checkVelocityConsistency(m: LocationMeasurement, recent: List<LocationMeasurement>, hits: MutableList<Hit>) {
        val all = recent + m
        val startIdx = all.indexOfFirst { (m.tNs - it.tNs) / 1e9 <= cfg.velocityWindowS * 1.2 }
        if (startIdx < 0 || startIdx >= all.size - 1) return
        val start = all[startIdx]
        if ((m.tNs - start.tNs) / 1e9 < cfg.velocityWindowS * 0.6) return
        var pe = 0.0
        var pn = 0.0
        for (i in startIdx until all.size - 1) {
            val a = all[i]
            val b = all[i + 1]
            val dt = (b.tNs - a.tNs) / 1e9
            if (dt <= 0 || dt > 3.0) return
            val sa = a.speedMps ?: return
            val sb = b.speedMps ?: return
            val ba = if (sa < 1.0) 0.0 else Math.toRadians(a.bearingDeg ?: return)
            val bb = if (sb < 1.0) 0.0 else Math.toRadians(b.bearingDeg ?: return)
            pe += 0.5 * (sa * kotlin.math.sin(ba) + sb * kotlin.math.sin(bb)) * dt
            pn += 0.5 * (sa * kotlin.math.cos(ba) + sb * kotlin.math.cos(bb)) * dt
        }
        val actual = LocalFrame(start.lat, start.lon).toEnu(m.lat, m.lon)
        val diff = kotlin.math.hypot(actual.e - pe, actual.n - pn)
        val allow = cfg.velocityMismatchM + 2 * ((start.hAccM ?: 20.0) + (m.hAccM ?: 20.0))
        if (diff > allow) hits += Hit(TrustReason.VELOCITY_POSITION_MISMATCH, TrustState.QUESTIONABLE, 0.6)
    }

    private fun checkStationary(m: LocationMeasurement, motion: MotionView?, hits: MutableList<Hit>) {
        if (motion == null) return
        if (motion.stationaryForS() >= cfg.stationaryMinS && (m.speedMps ?: 0.0) > cfg.stationaryMaxSpeedMps) {
            hits += Hit(TrustReason.MOVING_WHILE_STATIONARY, TrustState.QUESTIONABLE, 0.6)
        }
    }

    /** Returns true when a fresh coarse fix independently agrees with [m]. */
    private fun checkNetwork(m: LocationMeasurement, hits: MutableList<Hit>): Boolean {
        val net = lastNetwork ?: return false
        val ageS = abs(m.tNs - net.tNs) / 1e9
        if (ageS > cfg.networkMaxAgeS) return false
        val d = Geo.haversineM(net.lat, net.lon, m.lat, m.lon)
        val allow = cfg.networkK * ((net.hAccM ?: 1000.0) + (m.hAccM ?: 50.0)) + ageS * 30.0
        if (d > allow + cfg.networkImpossibleM) hits += Hit(TrustReason.GEOGRAPHICALLY_IMPOSSIBLE, TrustState.REJECTED)
        else if (d > allow) hits += Hit(TrustReason.NETWORK_DISAGREEMENT, TrustState.QUESTIONABLE, 0.5)
        return d <= allow
    }

    private fun checkRawGnss(m: LocationMeasurement, hits: MutableList<Hit>) {
        val s = lastStatus ?: return
        if (abs(m.tNs - s.tNs) > 2_000_000_000L) return
        val used = s.sats.filter { it.usedInFix }
        if (used.size < cfg.minSatsUsed) hits += Hit(TrustReason.FEW_SATELLITES, TrustState.QUESTIONABLE, 0.7)
        if (used.size >= cfg.cn0UniformMinSats) {
            val mean = used.sumOf { it.cn0DbHz } / used.size
            val std = sqrt(used.sumOf { (it.cn0DbHz - mean) * (it.cn0DbHz - mean) } / (used.size - 1))
            // Low weight in Phase 1: informational, only lowers confidence.
            if (std < cfg.cn0UniformStdDb) hits += Hit(TrustReason.CN0_UNIFORM, TrustState.TRUSTED, 0.8)
        }
    }

    override fun snapshot(): Any = Snap(HashMap(sources), lastNetwork, lastStatus, HashSet(overridden))

    override fun restore(snapshot: Any) {
        val s = snapshot as Snap
        sources.clear(); sources.putAll(s.sources)
        lastNetwork = s.lastNetwork; lastStatus = s.lastStatus
        overridden.clear(); overridden.addAll(s.overridden)
    }
}
