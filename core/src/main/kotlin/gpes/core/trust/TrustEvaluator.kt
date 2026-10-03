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
import gpes.core.model.VehicleSpeedMeasurement
import gpes.core.motion.Odometry
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.sqrt

/** Read-only view of IMU-derived motion that trust checks may consult. */
interface MotionView {
    fun stationaryForS(): Double
    /** Bearing change measured by the gyro between t1 and t2 (rad, clockwise positive), or null. */
    fun bearingChange(t1: Long, t2: Long): Double?
    /** Path driven between t1 and t2 from vehicle speed and the gyro, or null without vehicle speed. */
    fun odometry(t1: Long, t2: Long): Odometry? = null
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
    /**
     * A self-consistent stream of fixes that are QUESTIONABLE *only* because of the innovation gate
     * (NIS 13.8–50) is accepted after this long (D-038). The estimator ignores QUESTIONABLE GNSS, so its
     * prediction cannot converge to a returning GNSS by itself: after a long outage the NIS can sit just
     * above the gate for minutes (R-007: 500 s). Only for GNSS *returning after an outage* (no fix of this
     * source at all for [questionableResetOutageS] before the stream started): a spoofer taking over
     * during normal tracking must not be accepted this way. null disables.
     */
    val questionableResetS: Double? = 10.0,
    val questionableResetOutageS: Double = 30.0,
    val unavailableAfterS: Double = 5.0,
    /** Baseline for comparing position displacement with integrated reported velocity. */
    val velocityWindowS: Double = 10.0,
    val velocityMismatchM: Double = 15.0,
    /**
     * GNSS speed vs independent vehicle speed (OBD): allowed |Δ| = abs + rel·v. The relative part covers
     * speedometer scale error and the adapter's latency during acceleration.
     */
    val obdSpeedAbsMps: Double = 1.5,
    val obdSpeedRel: Double = 0.08,
    val obdMaxAgeS: Double = 1.5,
    /**
     * Coarse fix vs distance driven: the displacement from the last trusted coarse fix must match the
     * odometry chord (vehicle speed along the gyro bearing) within
     * K·√(σ₁²+σ₂²) + rel·distance + abs, with σ = hAcc / 1.515. null disables the check.
     */
    val coarseOdoK: Double? = 3.0,
    val coarseOdoRel: Double = 0.05,
    val coarseOdoAbsM: Double = 10.0,
    val coarseOdoMaxAgeS: Double = 180.0,
    /** Number of recent trusted coarse fixes that vote. */
    val coarseOdoVoters: Int = 3,
    /** Weight each vote by 1/(σ₁²+σ₂²) instead of one vote each (D-040). */
    val coarseOdoWeighted: Boolean = true,
    /**
     * Floor on each σ in the vote weight (m), so a fix that claims a small hAcc but is wrong cannot
     * outvote the others alone; the weighting only takes weight away from vague voters.
     */
    val coarseOdoWeightMinSigmaM: Double = 50.0,
    /**
     * Vector form (D-039): when the estimator's heading std is at most this (degrees), the displacement
     * between fixes is compared with the odometry displacement rotated to the estimated heading, not only
     * its length, so a fix the right distance away in the wrong direction is caught. null disables.
     */
    val coarseOdoVectorMaxHeadingStdDeg: Double? = 15.0,
    /** Floor on the heading std used in the vector tolerance (degrees): the EKF heading std is optimistic. */
    val coarseOdoVectorMinHeadingStdDeg: Double = 5.0,
    /** K for the vector form; its residual is 2-D, so the same K is looser than for the scalar form. */
    val coarseOdoVectorK: Double = 2.5,
    /**
     * Use the vector form only when no GNSS/fused fix was trusted for this long (s). With GNSS the EKF
     * heading comes from GNSS, so a spoofer could steer it and get honest coarse fixes rejected.
     */
    val coarseOdoVectorNoGnssS: Double = 180.0,
    /**
     * A fix this close (m) to the estimator's own prediction, with hAcc ≤ [accQuestionableM], is trusted
     * even if the innovation gate, implied acceleration or a "moving while stationary" verdict says
     * otherwise, and it skips the post-rejection recovery count (D-069). It never overrides hard
     * evidence (impossible speed or place, OBD or network disagreement, velocity–position or course
     * mismatch). null disables.
     */
    val agreeWithEstimateM: Double? = 100.0,
    /** The agreement rule is off while vehicle speed (OBD) is this fresh (s): the estimate is then precise enough to gate strictly. */
    val agreeObdFreshS: Double = 10.0,
    /**
     * The innovation gate REJECTS (NIS > [gateRejectNis]) only a fix at least this far (m) from the prediction; closer
     * ones are QUESTIONABLE, so the D-038 stream rule can accept GNSS that returns after an outage within 10 s (D-071).
     * A drifted dead-reckoned estimate is often 0.3–1.3 km off (drive 20261003-140822), and a window of the GNSS
     * probe lasts at most 20 s, less than the 120 s a REJECTED stream needs. null: always REJECTED.
     */
    val gateRejectMinDistM: Double? = 5000.0,
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
        /** Recent TRUSTED fixes of this source, newest last (coarse odometry voting). */
        val trustedRecent: List<LocationMeasurement> = emptyList(),
        /**
         * Questionable-stream reset (D-038), tracked separately from the rejected-stream fields above so
         * that it cannot change when the older rule fires: start, last fix, and whether the stream began
         * right after a gap with no fixes of this source at all (an outage).
         */
        val qStreamStart: LocationMeasurement? = null,
        val qStreamLast: LocationMeasurement? = null,
        val qStreamAfterOutage: Boolean = false,
        /**
         * The current run of fixes (no gap ≥ [TrustConfig.questionableResetOutageS]) began after an outage and has had no
         * TRUSTED fix yet. A questionable stream interrupted by another reason (a wrong network fix) and restarted keeps its
         * outage status through this; otherwise the restarted stream never qualified (drive 20261003-140822, 1781–1800 s).
         */
        val runAfterOutage: Boolean = false,
    )

    private data class Snap(
        val sources: Map<LocSource, SourceState>,
        val lastNetwork: LocationMeasurement?,
        val lastStatus: GnssStatusSnapshot?,
        val overridden: Set<String>,
        val lastObd: VehicleSpeedMeasurement?,
    )

    private companion object {
        /** Checks that closeness to our own estimate outweighs (D-069). */
        val AGREEMENT_OVERRIDES = setOf(
            TrustReason.INNOVATION_GATE, TrustReason.IMPOSSIBLE_ACCELERATION, TrustReason.MOVING_WHILE_STATIONARY,
        )
    }

    private data class Hit(val reason: TrustReason, val severity: TrustState, val factor: Double = 1.0)

    private val sources = HashMap<LocSource, SourceState>()
    private var lastNetwork: LocationMeasurement? = null
    private var lastStatus: GnssStatusSnapshot? = null
    private val overridden = HashSet<String>()
    private var lastObd: VehicleSpeedMeasurement? = null

    override fun observe(m: Measurement) {
        when (m) {
            is GnssStatusSnapshot -> lastStatus = m
            is VehicleSpeedMeasurement -> lastObd = m
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
        var agreed = false

        if (m.isSynthetic) hits += Hit(TrustReason.SYNTHETIC_INPUT, TrustState.REJECTED)
        if (m.provider in overridden) hits += Hit(TrustReason.SOURCE_OVERRIDDEN, TrustState.UNAVAILABLE)
        if (hits.isNotEmpty()) {
            // Our own output (or a replaced provider's fixes) must not touch this source's history: the
            // first real fix of a GNSS probe window would be compared with our mock track (D-069).
            val worst = TrustState.entries[hits.maxOf { it.severity.ordinal }]
            return TrustAssessment(m.tNs, m.source, m.provider, worst, 0.0, hits.map { it.reason }.toSet())
        }

        val latencyS = (m.receivedNs - m.tNs) / 1e9
        when (m.source) {
            LocSource.NETWORK -> {
                if (latencyS > cfg.networkStaleS) hits += Hit(TrustReason.STALE, TrustState.QUESTIONABLE, 0.5)
                val acc = m.hAccM
                if (acc == null) hits += Hit(TrustReason.NO_ACCURACY, TrustState.REJECTED)
                else if (acc > cfg.networkMaxAccM) hits += Hit(TrustReason.POOR_ACCURACY, TrustState.REJECTED)
                checkCoarseOdometry(m, st, ctx.motion, ctx.predicted, hits)
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
                checkObdSpeed(m, hits)
                checkStationary(m, ctx.motion, hits)
                networkAgrees = checkNetwork(m, hits)
                if (m.source == LocSource.GNSS) checkRawGnss(m, hits)
                agreed = agreesWithEstimate(m, ctx.predicted)
                if (agreed && hits.removeAll { it.reason in AGREEMENT_OVERRIDES }) hits += Hit(TrustReason.AGREES_WITH_ESTIMATE, TrustState.TRUSTED)
            }
            else -> Unit
        }

        var state = hits.maxOfOrNull { it.severity.ordinal }?.let { TrustState.entries[it] } ?: TrustState.TRUSTED
        var reasons = hits.map { it.reason }.toMutableSet()
        val cutoff = m.tNs - (cfg.velocityWindowS * 1.5 * 1e9).toLong()
        val gapBefore = st.prev?.let { (m.tNs - it.tNs) / 1e9 } ?: Double.MAX_VALUE
        val runAfterOutage = gapBefore >= cfg.questionableResetOutageS || st.runAfterOutage
        var newSt = st.copy(prev = m, recent = (st.recent + m).filter { it.tNs >= cutoff }, runAfterOutage = runAfterOutage)

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
                TrustState.QUESTIONABLE -> {
                    val gateOnly = reasons == setOf(TrustReason.INNOVATION_GATE)
                    // Would have been REJECTED before D-071 (NIS > reject gate, but nearer than gateRejectMinDistM): such a
                    // stream keeps the old 120 s / 15 s (network agrees) reset when it did not follow an outage.
                    val gateDowngraded = gateOnly && nis != null && nis > cfg.gateRejectNis
                    val limit = cfg.questionableResetS
                    if (!gateOnly || limit == null) {
                        newSt = newSt.copy(qStreamStart = null, qStreamLast = null, qStreamAfterOutage = false)
                    } else {
                        val consistent = st.qStreamLast != null && isConsistent(st.qStreamLast, m)
                        val start = if (consistent) st.qStreamStart!! else m
                        val afterOutage = if (consistent) st.qStreamAfterOutage else runAfterOutage
                        newSt = newSt.copy(qStreamStart = start, qStreamLast = m, qStreamAfterOutage = afterOutage)
                        val wait = when {
                            afterOutage -> limit
                            gateDowngraded -> if (networkAgrees) cfg.resetWithNetworkS else cfg.resetAfterConsistentS
                            else -> null
                        }
                        if (wait != null && (m.tNs - start.tNs) / 1e9 >= wait) {
                            state = TrustState.TRUSTED
                            reasons = mutableSetOf(TrustReason.RESET_AFTER_CONSISTENT_STREAM)
                            newSt = newSt.copy(qStreamStart = null, qStreamLast = null, qStreamAfterOutage = false)
                        }
                    }
                }
                TrustState.TRUSTED -> {
                    newSt = newSt.copy(streamStart = null, streamLast = null, qStreamStart = null, qStreamLast = null, qStreamAfterOutage = false)
                    if (st.recoveryNeeded > 0 && agreed) {
                        newSt = newSt.copy(recoveryNeeded = 0)
                        reasons += TrustReason.AGREES_WITH_ESTIMATE
                    } else if (st.recoveryNeeded > 0) {
                        state = TrustState.QUESTIONABLE
                        reasons += TrustReason.RECOVERING
                        newSt = newSt.copy(recoveryNeeded = st.recoveryNeeded - 1)
                    }
                }
                else -> Unit
            }
        }

        if (state == TrustState.TRUSTED) newSt = newSt.copy(lastTrusted = m, trustedRecent = (st.trustedRecent + m).takeLast(cfg.coarseOdoVoters), runAfterOutage = false)
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
        if (nis > cfg.gateRejectNis && (cfg.gateRejectMinDistM == null || hypot(v.e, v.n) >= cfg.gateRejectMinDistM)) {
            hits += Hit(TrustReason.INNOVATION_GATE, TrustState.REJECTED)
        } else if (nis > cfg.gateQuestionableNis) {
            hits += Hit(TrustReason.INNOVATION_GATE, TrustState.QUESTIONABLE, 0.6)
        }
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

    /** A spoofer can fake a consistent GNSS track, but not the car's own speedometer. */
    private fun checkObdSpeed(m: LocationMeasurement, hits: MutableList<Hit>) {
        val obd = lastObd ?: return
        val v = m.speedMps ?: return
        if (abs(m.tNs - obd.tNs) / 1e9 > cfg.obdMaxAgeS) return
        if (abs(v - obd.speedMps) > cfg.obdSpeedAbsMps + cfg.obdSpeedRel * obd.speedMps) {
            hits += Hit(TrustReason.SPEED_OBD_MISMATCH, TrustState.QUESTIONABLE, 0.5)
        }
    }

    /**
     * A coarse fix must be about as far from recent trusted ones as the car actually drove. The
     * odometry chord needs no absolute heading, so this works with no GNSS and no compass. It catches
     * fixes that jump too far and stale fixes that stay put while the car moves; a fix at the right
     * distance in the wrong direction passes. The last few trusted fixes vote, and the fix is rejected
     * only when most of them disagree, so one bad (but accepted) reference cannot reject the good fixes
     * after it. A fix that agrees with the previous, rejected fix is also accepted.
     */
    private fun checkCoarseOdometry(
        m: LocationMeasurement, st: SourceState, motion: MotionView?, pred: PositionEstimate?, hits: MutableList<Hit>,
    ) {
        if (motion == null || cfg.coarseOdoK == null) return
        val votes = st.trustedRecent
            .filter { (m.tNs - it.tNs) / 1e9 <= cfg.coarseOdoMaxAgeS }
            .mapNotNull { odometryAgrees(it, m, motion, pred) }
        // Weighted by 1/(σ₁²+σ₂²) (D-040): a vague voter (hAcc 700 m) agrees with almost anything.
        val wFor = votes.filter { it.agrees }.sumOf { it.weight }
        val wAgainst = votes.filter { !it.agrees }.sumOf { it.weight }
        if (votes.isEmpty() || wAgainst <= wFor) return
        val prev = st.prev
        if (prev != null && prev !== st.lastTrusted && odometryAgrees(prev, m, motion, pred)?.agrees == true) return
        hits += Hit(TrustReason.COARSE_ODOMETRY_MISMATCH, TrustState.REJECTED)
    }

    /**
     * The vote of fix [a] on fix [b], or null when odometry or accuracy is unavailable. [pred] is the estimate at [b]'s time;
     * with a known heading and no recent GNSS the displacement vector is compared (D-039), otherwise only
     * its length.
     */
    private class Vote(val agrees: Boolean, val weight: Double)

    private fun odometryAgrees(a: LocationMeasurement, b: LocationMeasurement, motion: MotionView, pred: PositionEstimate?): Vote? {
        val k = cfg.coarseOdoK ?: return null
        val odo = motion.odometry(a.tNs, b.tNs) ?: return null
        val sa = (a.hAccM ?: return null) / Cov2.R68_PER_SIGMA
        val sb = (b.hAccM ?: return null) / Cov2.R68_PER_SIGMA
        val slack = cfg.coarseOdoRel * odo.distanceM + cfg.coarseOdoAbsM
        val weight = if (cfg.coarseOdoWeighted) {
            val wa = max(sa, cfg.coarseOdoWeightMinSigmaM); val wb = max(sb, cfg.coarseOdoWeightMinSigmaM)
            1.0 / (wa * wa + wb * wb)
        } else 1.0
        val heading = pred?.headingRad
        val headingStd = pred?.headingStdRad
        val maxStd = cfg.coarseOdoVectorMaxHeadingStdDeg
        if (heading != null && headingStd != null && maxStd != null && odo.relBearingEnd != null &&
            headingStd <= Math.toRadians(maxStd) && !gnssTrustedWithin(b.tNs, cfg.coarseOdoVectorNoGnssS)
        ) {
            // Rotate the gyro-frame displacement onto the estimated heading.
            val off = heading - odo.relBearingEnd
            val oe = odo.dE * cos(off) + odo.dN * sin(off)
            val on = -odo.dE * sin(off) + odo.dN * cos(off)
            val z = LocalFrame(a.lat, a.lon).toEnu(b.lat, b.lon)
            val sPsi = odo.chordM * max(headingStd, Math.toRadians(cfg.coarseOdoVectorMinHeadingStdDeg))
            return Vote(hypot(z.e - oe, z.n - on) <= cfg.coarseOdoVectorK * sqrt(sa * sa + sb * sb + sPsi * sPsi) + slack, weight)
        }
        val d = Geo.haversineM(a.lat, a.lon, b.lat, b.lon)
        return Vote(abs(d - odo.chordM) <= k * sqrt(sa * sa + sb * sb) + slack, weight)
    }

    private fun gnssTrustedWithin(tNs: Long, s: Double) = listOf(LocSource.GNSS, LocSource.FUSED).any { src ->
        val t = sources[src]?.lastTrusted?.tNs
        t != null && (tNs - t) / 1e9 <= s
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

    /** D-069: the fix lies within [TrustConfig.agreeWithEstimateM] of the estimator's prediction and claims a usable accuracy. */
    private fun agreesWithEstimate(m: LocationMeasurement, pred: PositionEstimate?): Boolean {
        val radius = cfg.agreeWithEstimateM ?: return false
        val acc = m.hAccM ?: return false
        if (pred == null || acc > cfg.accQuestionableM) return false
        // With a fresh vehicle speed the estimate is a precise reference and the innovation gate is the
        // defence against a Doppler-consistent drift (R-002), so the strict rules stay.
        val obd = lastObd
        if (obd != null && abs(m.tNs - obd.tNs) / 1e9 <= cfg.agreeObdFreshS) return false
        return Geo.haversineM(pred.lat, pred.lon, m.lat, m.lon) <= radius
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

    override fun snapshot(): Any = Snap(HashMap(sources), lastNetwork, lastStatus, HashSet(overridden), lastObd)

    override fun restore(snapshot: Any) {
        val s = snapshot as Snap
        sources.clear(); sources.putAll(s.sources)
        lastNetwork = s.lastNetwork; lastStatus = s.lastStatus
        overridden.clear(); overridden.addAll(s.overridden)
        lastObd = s.lastObd
    }
}
