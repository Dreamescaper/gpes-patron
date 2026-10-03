package gpes.core.estimator

import gpes.core.road.RoadMatcher
import gpes.core.road.RoadConstraintConfig
import gpes.core.road.RoadMatcherConfig
import gpes.core.road.RoadNetwork
import gpes.core.road.RoadStepInput

import gpes.core.geo.Geo
import gpes.core.geo.LocalFrame
import gpes.core.model.Cov2
import gpes.core.model.EstimatorMode
import gpes.core.model.GeomagneticReference
import gpes.core.model.Hypothesis
import gpes.core.model.ImuSample
import gpes.core.model.PowerState
import gpes.core.model.LocSource
import gpes.core.model.LocationMeasurement
import gpes.core.model.Measurement
import gpes.core.model.PositionEstimate
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustState
import gpes.core.model.VehicleSpeedMeasurement
import gpes.core.motion.MotionUpdate
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class CompassStatus(val tNs: Long, val quality: CompassQuality, val reading: CompassReading?)

@Serializable
data class BaselineConfig(
    /** Minimum GNSS speed at which its course is trusted for heading (m/s). */
    val minCourseSpeedMps: Double = 5.0,
    /** Longitudinal speed random walk (m/s per √s). Controls how fast speed becomes unknown. */
    val speedRandomWalk: Double = 0.7,
    /** Speed assumed right after leaving a stop when no speed source exists (with [unknownSpeedStd]). */
    val pullAwaySpeedMps: Double = 8.0,
    /** Position process noise (m/√s), for lateral slip and model error. */
    val posRandomWalk: Double = 0.3,
    /** Heading process noise (rad/√s): gyro noise plus mounting wobble. */
    val headingRandomWalk: Double = 0.01,
    /** Gyro scale-factor uncertainty (fraction) applied to the turn rate. */
    val gyroScaleError: Double = 0.02,
    val biasRandomWalk: Double = 2e-4,
    val initialBiasStd: Double = 0.01,
    /** NIS above this against a TRUSTED GNSS fix → reset instead of fuse (PX4-style reset on glitch recovery). */
    val gnssResetNis: Double = 25.0,
    /** Coarse fixes are biased and correlated; inflate their reported accuracy. */
    val networkInflation: Double = 1.5,
    /** Skip a coarse update unless our position sigma exceeds this fraction of the coarse sigma. */
    val networkUsefulFraction: Double = 0.5,
    /**
     * Also fuse a coarse fix when at least this long and this far (odometry) since the last fused one.
     * Coarse errors are correlated in time and space; spacing them out lets many fixes average along a
     * well-known path without pretending repeated fixes at one spot are independent.
     */
    val networkMinIntervalS: Double = 15.0,
    val networkMinDistanceM: Double = 150.0,
    val networkResetNis: Double = 50.0,
    /**
     * Robust coarse updates (D-034): above this NIS (χ², 2 dof; 9.21 = 99%) a coarse fix is a
     * *candidate*; its R is scaled by NIS/threshold, so it moves position and heading only a little.
     * null = plain updates with the old reset at [networkResetNis].
     */
    val coarseRobustNis: Double? = 9.21,
    /** Candidates in a row whose spacing matches the odometry chord → reset onto them. */
    val coarseStreamMin: Int = 3,
    val coarseStreamMinSpanS: Double = 20.0,
    val coarseStreamK: Double = 3.0,
    /**
     * Fraction of the Kalman heading (and gyro-bias) correction that a coarse fix may apply (1 = full).
     * A lateral offset of a correctly shaped track is otherwise mostly explained as a heading error after
     * a long DR stretch, which turns the track off the road (R-008 at 358 s). The covariance stays
     * consistent for the reduced gain (Joseph form).
     */
    val coarseHeadingGain: Double = 1.0,
    /** A stream reset keeps the heading if GNSS was used this recently (s). */
    val coarseStreamGnssRecentS: Double = 60.0,
    /** Use QUESTIONABLE GNSS with R inflated by this factor; null = don't use. */
    val questionableRScale: Double? = null,
    val useFused: Boolean = false,
    val gnssTrackingWindowS: Double = 3.0,
    /** Speed std above which speed is "unknown" for mode reporting. */
    val speedKnownStd: Double = 3.0,
    /** Initial speed std when starting without GNSS (m/s). */
    val unknownSpeedStd: Double = 10.0,
    /** Vehicle-speed (OBD) scale error: initial std (fraction) and random walk (per √s). */
    val speedScaleInitialStd: Double = 0.03,
    val speedScaleRandomWalk: Double = 2e-5,
    /** Magnetometer heading (deviation-card calibrated, gated). See [Compass]. */
    val compass: CompassConfig = CompassConfig(),
    /** Absolute heading from coarse fixes + gyro + speed while no other heading source exists. See [HeadingBank]. */
    val headingBank: HeadingBankConfig = HeadingBankConfig(),
    /** Road matcher (Phase 2, docs/road-constraint.md); active only when a road network is supplied. */
    val road: RoadMatcherConfig = RoadMatcherConfig(),
    val roadConstraint: RoadConstraintConfig = RoadConstraintConfig(),
    /**
     * Vehicle-speed latency (s): an ELM327 reading describes the speed ~0.8 s earlier (R-007). The reading is
     * advanced by latency × the slope of the recent readings (least squares over [obdSlopeWindowS]). 0 = off.
     */
    val obdLatencyS: Double = 0.0,
    val obdSlopeWindowS: Double = 1.5,
    /** Speed without OBD from the longitudinal accelerometer, anchored by ZUPT and turns (D-050). */
    val accelSpeed: AccelSpeedConfig = AccelSpeedConfig(),
)

/**
 * Phase 1 baseline: a 2-D EKF in a local ENU frame with state `[e, n, ψ, v, b, s]`
 * (s = vehicle-speed/OBD scale error, learned against trusted GNSS).
 *  - ψ is the course (bearing, radians clockwise from north), propagated with the yaw rate from the
 *    gyro projected on gravity. There is no absolute heading source except GNSS course.
 *  - v is the speed along the course. This is the non-holonomic assumption: no lateral or vertical
 *    velocity. It is observed by GNSS speed, [VehicleSpeedMeasurement] (future OBD) and ZUPT.
 *    The accelerometer is **never** double-integrated.
 *  - b is the gyro bias about "up", observed while stationary and through GNSS course.
 *
 * While heading is unknown (for example at startup from a network fix only), position is not
 * propagated along a direction. Instead its covariance grows by the worst-case distance
 * travelled in an unknown direction. Meanwhile a [HeadingBank] tests heading hypotheses against the
 * coarse fixes; once it converges, the EKF takes its heading and position and dead-reckons (D-032).
 */
/**
 * Speed without OBD (D-036, D-050): while no vehicle speed is fresh, v is driven by the longitudinal specific
 * force (minus a learned bias state) instead of a random walk; never open-loop: ZUPT (real-car stop rule),
 * centripetal speed |a_lat|/|ω| in turns, coarse fixes and GNSS bound it.
 */
@Serializable
data class AccelSpeedConfig(
    val enabled: Boolean = true,
    /** Vehicle speed counts as present while its last reading is younger than this (s). */
    val obdFreshS: Double = 2.0,
    /** White specific-force noise driving v (m/s per √s). */
    val accelNoise: Double = 0.8,
    val biasInitialStd: Double = 0.5,
    /** v follows the accelerometer only while the bias σ is at most this (m/s²). */
    val biasMaxStd: Double = 0.3,
    val biasRandomWalk: Double = 0.05,
    /** ZUPT with the real-car stop rule (MotionUpdate.stillLoose) when there is no vehicle speed. */
    val zuptLoose: Boolean = true,
    /** … only while the speed estimate allows a stop: v − 2σ below this (m/s). */
    val zuptMaxSpeedMps: Double = 1.5,
    /** … and only without trusted GNSS for this long (s): with GNSS there is a speed measurement already. */
    val zuptNoGnssS: Double = 5.0,
    /** … and not while the longitudinal specific force (minus bias) exceeds this (m/s²). */
    val zuptMaxAccel: Double = 0.3,
    /** Bias learning at rest: only after this long stopped (s), with |a_long| below [biasMaxAccel], σ [biasZuptStd]. */
    val biasMinStillS: Double = 2.0,
    val biasMaxAccel: Double = 0.5,
    val biasZuptStd: Double = 0.2,
    /** Accelerometer speed only while a position fix (hAcc ≤ [anchorMaxAccM]) was used within this long (s). */
    val maxNoFixS: Double = 30.0,
    val anchorMaxAccM: Double = 200.0,
    val centripetal: Boolean = true,
    val centripetalPeriodS: Double = 1.0,
    val centripetalMinYawRate: Double = 0.07,
    /** σ of a centripetal speed as a fraction of it, plus an absolute part (R-026: |v_c − v_OBD| median 0.32 m/s). */
    val centripetalRelStd: Double = 0.1,
    val centripetalAbsStd: Double = 0.5,
    /**
     * An unsteady mount (a hand-held phone) moves the up vector and the forward axis, so the "bias" of the longitudinal
     * force wanders much faster than on a holder (drive 20261003-140822: −1.8 → +1.0 m/s² in 3 min). The bias random walk
     * is scaled by max(1, r / this), where r is the horizontal-axis angular rate RMS ([MotionUpdate.tiltRateRms]) low-passed
     * over [unsteadyTauS] while moving: moving medians 0.13 rad/s in the hand, 0.045–0.067 on holders. null = off.
     */
    val unsteadyTiltRate: Double? = 0.07,
    val unsteadyTauS: Double = 60.0,
    val unsteadyMaxScale: Double = 4.0,
    /**
     * On an unsteady mount the up vector's tilt error leaks g·θ into a_lat, i.e. g·θ/|ω| of centripetal speed (hand-held:
     * 30–38 m/s at a true ~18 in gentle turns). The centripetal σ gets that term with θ = this × (scale − 1) degrees,
     * scale from [unsteadyTiltRate]; on a holder (scale 1) nothing changes (a fixed 2° cost holder drives 15 % of p95).
     */
    val unsteadyCentripetalTiltDeg: Double = 2.0,
)

class BaselineDrEstimator(
    private val cfg: BaselineConfig = BaselineConfig(),
    /**
     * Road network supplier (Phase 2). It may return a new network when more tiles are loaded; the
     * matcher then restarts on it. Null = no road constraint.
     */
    private val roads: (() -> RoadNetwork?)? = null,
    /** Internal: the road-free twin that runs the matcher (no road updates, so no feedback loop). */
    private val isFreeTwin: Boolean = false,
) : PositionEstimator {
    override val name = "baseline"

    private data class Snap(
        val initialized: Boolean, val frame: LocalFrame?, val x: DoubleArray, val p: DoubleArray,
        val headingKnown: Boolean, val lastT: Long, val lastYawRate: Double, val stationary: Boolean,
        val lastGnssT: Long, val dirlessDist: Double, val compass: Compass, val nextCompassT: Long,
        val odoM: Double, val lastNetT: Long, val lastNetOdo: Double, val bank: HeadingBank,
        val relE: Double, val relN: Double, val candidates: List<Candidate>,
        val matcher: RoadMatcher?, val lastRoadOdo: Double, val gyroTurn: Double,
        val recentTurns: List<Double>, val lastRoadHeadingOdo: Double, val lastRoadCrossOdo: Double,
        val roadSteps: Int, val seenFreeSteps: Int, val roadRejects: Int, val free: Any?,
        val gyroHist: List<Pair<Double, Double>>, val cumGyro: Double, val lastRoadAlongOdo: Double,
        val heldOffset: Double?, val heldT: Long, val obdHist: List<Pair<Long, Double>>, val lastCornerOdo: Double,
        val lastALong: Double?, val lastObdT: Long, val centLat: Double, val centYaw: Double, val centN: Int, val centT: Long,
        val stillSinceT: Long, val lastFixT: Long, val tiltRateLp: Double?,
    )

    /** A coarse fix that disagreed strongly with the prediction. */
    private data class Candidate(
        val tNs: Long, val lat: Double, val lon: Double, val r: Double,
        val relE: Double, val relN: Double, val odo: Double,
    )

    private var initialized = false
    private var frame: LocalFrame? = null
    private val x = DoubleArray(N)
    private var p = Mat.diag(
        1.0, 1.0, PI * PI, cfg.unknownSpeedStd * cfg.unknownSpeedStd, cfg.initialBiasStd * cfg.initialBiasStd,
        cfg.speedScaleInitialStd * cfg.speedScaleInitialStd,
        cfg.accelSpeed.biasInitialStd * cfg.accelSpeed.biasInitialStd,
    )
    private var headingKnown = false
    private var lastT = Long.MIN_VALUE
    private var lastYawRate = 0.0
    /** Latest longitudinal / lateral specific force from the motion tracker (null until the forward axis is learned). */
    private var lastALong: Double? = null
    private var lastObdT = Long.MIN_VALUE
    private var centLat = 0.0
    private var centYaw = 0.0
    private var centN = 0
    private var centT = Long.MIN_VALUE
    private var stillSinceT = Long.MIN_VALUE
    /** [MotionUpdate.tiltRateRms] low-passed over [AccelSpeedConfig.unsteadyTauS] while moving (null until the first). */
    private var tiltRateLp: Double? = null
    /** Last position fix (coarse or GNSS) the estimator used. */
    private var lastFixT = Long.MIN_VALUE
    private var stationary = false
    private var lastGnssT = Long.MIN_VALUE
    private var dirlessDist = 0.0
    private var compass = Compass(cfg.compass)
    private var nextCompassT = Long.MIN_VALUE
    /** Latest speedometer scale estimate (fraction, std), for UI; safe to read from another thread. */
    @Volatile var speedScaleStatus: Pair<Double, Double>? = null
        private set

    /** Latest compass state for UI/diagnostics (published at 1 Hz; safe to read from another thread). */
    @Volatile var compassStatus: CompassStatus? = null
        private set

    private var odoM = 0.0
    private var lastNetT = Long.MIN_VALUE
    private var lastNetOdo = 0.0
    private var bank = HeadingBank(cfg.headingBank)
    /** Dead-reckoned path in the estimator's heading frame (for candidate consistency; only differences matter). */
    private var relE = 0.0
    private var relN = 0.0
    private var candidates: List<Candidate> = emptyList()
    private var matcher: RoadMatcher? = null
    /**
     * With a road network, a second estimator gets the same inputs but never uses the road. The matcher
     * runs on *its* pose, so road updates in this estimator cannot confirm themselves (R-020: matching on
     * the road-constrained pose locked onto a wrong road). This estimator applies the matched road.
     */
    private val free: BaselineDrEstimator? =
        if (roads != null && !isFreeTwin && cfg.road.enabled) BaselineDrEstimator(cfg, roads, isFreeTwin = true) else null
    private var roadSteps = 0
    private var seenFreeSteps = 0
    private var roadRejects = 0
    /** A coarse fix held because it lies far to the side of a confidently matched road (its offset, m). */
    private var heldOffset: Double? = null
    /** Recent vehicle-speed readings (t, m/s) for the latency slope. */
    private var obdHist: List<Pair<Long, Double>> = emptyList()
    private var heldT = Long.MIN_VALUE
    private var lastRoadOdo = 0.0
    /** Heading change from the gyro alone since the last road step (rad, clockwise positive). */
    private var gyroTurn = 0.0
    /** Gyro turn of the last few road steps (rad), for the "driving straight" condition. */
    private var recentTurns: List<Double> = emptyList()
    /** (odometer, cumulative gyro heading) at each road step, for M4 shape matching. */
    private var gyroHist: List<Pair<Double, Double>> = emptyList()
    private var cumGyro = 0.0
    private var lastRoadAlongOdo = Double.NEGATIVE_INFINITY
    private var lastCornerOdo = Double.NEGATIVE_INFINITY
    private var lastRoadHeadingOdo = Double.NEGATIVE_INFINITY
    private var lastRoadCrossOdo = Double.NEGATIVE_INFINITY
    /** Road updates applied / gated out, for diagnostics. */
    var roadStats = IntArray(4)
        private set

    // ------------------------------------------------------------------------------------------

    override fun onMotion(u: MotionUpdate) {
        free?.onMotion(u)
        propagateTo(u.tNs, lastYawRate)
        lastYawRate = u.yawRateUp
        lastALong = u.longitudinalAccel
        if (!stationary && !u.stillLoose) tiltRateLp = tiltRateLp?.let { it + (u.tiltRateRms - it) * (u.dtS / cfg.accelSpeed.unsteadyTauS).coerceAtMost(1.0) } ?: u.tiltRateRms
        val accelMode = accelReady(u.tNs)
        // The real-car stop rule only where a stop is plausible: a car cannot be at 15 m/s one moment and stopped
        // the next; braking shows in the specific force first (also keeps the quiet simulator from "stopping").
        // (and the estimate itself low: with a large σ the first test alone would let a stop rule glitch zero a
        // cruising car's speed)
        val plausibleStop = x[IDX_V] - 2 * sqrt(p[IDX_V, IDX_V]) < cfg.accelSpeed.zuptMaxSpeedMps &&
            x[IDX_V] < 2 * cfg.accelSpeed.zuptMaxSpeedMps
        val noGnss = lastGnssT == Long.MIN_VALUE || (u.tNs - lastGnssT) / 1e9 > cfg.accelSpeed.zuptNoGnssS
        // An accelerating car is not stopped: pulling away (~1 m/s²) breaks a stop that the vibration rule would
        // otherwise keep (a smooth road, a quiet car; in the simulator it kept v at 0 for minutes).
        val notAccelerating = u.longitudinalAccel?.let { abs(it - x[IDX_BA]) < cfg.accelSpeed.zuptMaxAccel } ?: true
        val still = u.stationary || (accelMode && cfg.accelSpeed.zuptLoose && u.stillLoose && plausibleStop && noGnss && notAccelerating)
        if (stationary && !still && !accelMode) {
            // Pulling away: ZUPT pinned v to 0 with tiny variance, which is now meaningless.
            // Without a speed source the new speed is genuinely unknown. (With accelerometer speed, v = 0 is
            // the right start and the specific force takes over.)
            for (i in 0 until N) { p[IDX_V, i] = 0.0; p[i, IDX_V] = 0.0 }
            p[IDX_V, IDX_V] = cfg.unknownSpeedStd * cfg.unknownSpeedStd
            x[IDX_V] = cfg.pullAwaySpeedMps
        }
        stillSinceT = if (still) (if (stationary) stillSinceT else u.tNs) else Long.MIN_VALUE
        stationary = still
        // At rest a_long is the bias, but only well inside a stop: the loose stop rule also fires while creeping
        // away, and an accelerating car taken as bias (seen: 0.6 m/s²) drags the speed to zero afterwards.
        if (still && accelMode && stillSinceT != Long.MIN_VALUE && (u.tNs - stillSinceT) / 1e9 >= cfg.accelSpeed.biasMinStillS) {
            u.longitudinalAccel?.takeIf { abs(it) < cfg.accelSpeed.biasMaxAccel }?.let {
                updateLocal(IDX_BA, it, cfg.accelSpeed.biasZuptStd * cfg.accelSpeed.biasZuptStd)
            }
        }
        centripetal(u, accelMode)
        if (still) {
            // ZUPT: speed is zero; the measured yaw rate is pure bias. These are *local* updates (only
            // v and b move): after a long outage, P couples bias to position with a huge lever (heading
            // drift × distance), and one noisy bias sample would otherwise teleport the position by
            // hundreds of metres (seen in replay: 2 m → 178 m at the first stop). See D-028.
            updateLocal(IDX_V, 0.0, 0.05 * 0.05)
            updateLocal(IDX_B, u.yawRateUp, 0.003 * 0.003)
        }
        compass.onMotion(u, x[IDX_B])
        if (cfg.compass.enabled && u.tNs >= nextCompassT) {
            nextCompassT = u.tNs + (cfg.compass.periodS * 1e9).toLong()
            val r = compass.heading(u.tNs)
            r?.let(::applyCompass)
            compassStatus = CompassStatus(u.tNs, compass.quality(), r)
        }
        roadStep()
    }

    /**
     * Road check for a coarse fix (see [RoadConstraintConfig.holdOffRoadFixM]). Both the twin and this
     * estimator decide from the twin's matcher, which onMeasurement does not change, so they agree.
     */
    private fun roadAllowsFix(m: LocationMeasurement): Boolean {
        val thr = cfg.roadConstraint.holdOffRoadFixM ?: return true
        val off = roadOffset(m)
        val held = heldOffset?.takeIf { (m.tNs - heldT) / 1e9 <= 60.0 }
        if (off != null && abs(off) > thr) {
            if (held != null && held * off > 0 && abs(held) > cfg.roadConstraint.holdConfirmM) {
                heldOffset = null; return true // confirmed: the car really is off that road
            }
            heldOffset = off; heldT = m.tNs; return false
        }
        heldOffset = null
        return true
    }

    /** Signed offset of a fix from the matched road, when the matcher is confident and the road is straight. */
    private fun roadOffset(m: LocationMeasurement): Double? {
        val mm = (if (isFreeTwin) matcher else free?.matcher) ?: return null
        val h = mm.bestHyp() ?: return null
        val rc = cfg.roadConstraint
        if (mm.probabilityOf(h) < 0.95 || h.confidentM < rc.minConfidentM || mm.pOffRoad() > rc.maxPOffRoad) return null
        val net = mm.network
        val len = net.lengthOf(h.seg)
        val b = net.bearingAt(h.seg, h.d)
        val w = 60.0
        if (abs(Geo.wrapDeg(net.bearingAt(h.seg, maxOf(0.0, h.d - w)) - b)) > rc.straightMaxDeg ||
            abs(Geo.wrapDeg(net.bearingAt(h.seg, minOf(len, h.d + w)) - b)) > rc.straightMaxDeg) return null
        val p = net.projectOnto(h.seg, m.lat, m.lon)
        if (p.distanceM > abs(p.crossTrackM) + 1.0) return null // beyond the segment ends: cannot judge
        return p.crossTrackM
    }

    /** Corner fix (see [RoadConstraintConfig.cornerFix]). */
    private fun cornerFix(m: RoadMatcher, h: RoadMatcher.Hyp, gHist: List<Pair<Double, Double>>, gOdo: Double) {
        val rc = cfg.roadConstraint
        if (!rc.cornerFix || gHist.size < 10 || odoM - lastCornerOdo < 100.0) return
        if (m.probabilityOf(h) < rc.cornerMinProbability || m.pOffRoad() > rc.maxPOffRoad) return
        val sMax = sqrt(maxOf(p[0, 0], p[1, 1]) + abs(p[0, 1]))
        if (sMax > 2 * rc.maxPoseSigmaM || abs(x[IDX_V]) < rc.minSpeedMps || sqrt(p[IDX_V, IDX_V]) > rc.maxSpeedStdMps) return
        // A completed turn: ≥ cornerMinTurnDeg over the last 8 steps, the last 2 steps straight.
        val n = gHist.size
        val turn = Math.toDegrees(abs(gHist[n - 1].second - gHist[maxOf(0, n - 9)].second))
        val tail = Math.toDegrees(abs(gHist[n - 1].second - gHist[n - 3].second))
        if (turn < rc.cornerMinTurnDeg || tail > 5.0) return
        val profile = m.roadProfile(h)
        if (profile.isEmpty() || profile.last().back < rc.cornerTrailM) return
        val gyro = gHist.reversed().map { (o, c) -> gpes.core.road.AlongTrackMatch.Sample(gOdo - o, c) }
        val res = gpes.core.road.AlongTrackMatch.match(gyro, profile, gpes.core.road.AlongTrackMatch.Config(turnInsetM = 20.0, minTurnDeg = rc.cornerMinTurnDeg))
            ?: return
        val net = m.network
        val len = net.lengthOf(h.seg)
        val dir = if (h.fwd) 1.0 else -1.0
        val dTarget = h.d + dir * res.shiftM
        val segBearing = Math.toRadians(net.bearingAt(h.seg, h.d.coerceIn(0.0, len)))
        val travel = if (h.fwd) segBearing else segBearing + PI
        // Road point at the shifted position (extrapolated straight beyond the segment ends).
        val base = net.toLatLon(gpes.core.model.RoadState(h.seg, dTarget.coerceIn(0.0, len), h.fwd))
        val over = when { dTarget < 0 -> dTarget; dTarget > len -> dTarget - len; else -> 0.0 } * dir
        val zb = frame!!.toEnu(base.lat, base.lon)
        val ze = zb.e + over * sin(travel); val zn = zb.n + over * cos(travel)
        val half = gpes.core.road.RoadWidth.halfWidthM(net.segment(h.seg)!!)
        val r = maxOf(res.sigmaM, half).let { it * it } + cfg.road.osmGeometryStdM * cfg.road.osmGeometryStdM
        if (hypot(ze - x[0], zn - x[1]) <= rc.cornerMinSigmas * sqrt(r)) { lastCornerOdo = odoM; return }
        // Earlier road updates may have shrunk the position covariance without along-track evidence:
        // start from at least the road-free twin's position covariance.
        free?.let { f -> if (f.p[0, 0] + f.p[1, 1] > p[0, 0] + p[1, 1]) for (i in 0..1) for (j in 0..1) p[i, j] = f.p[i, j] }
        if (nis2(ze, zn, r) <= rc.cornerGateNis) { updatePos(ze, zn, r); roadStats[1] += 1_000_000 }
        lastCornerOdo = odoM
    }

    /** Least-squares slope (m/s²) of the recent vehicle-speed readings; 0 with fewer than 3. */
    private fun obdSlope(): Double {
        if (cfg.obdLatencyS == 0.0 || obdHist.size < 3) return 0.0
        val t0 = obdHist.first().first
        val ts = obdHist.map { (it.first - t0) / 1e9 }; val vs = obdHist.map { it.second }
        val mt = ts.average(); val mv = vs.average()
        val den = ts.sumOf { (it - mt) * (it - mt) }
        if (den <= 1e-6) return 0.0
        return ts.indices.sumOf { (ts[it] - mt) * (vs[it] - mv) } / den
    }

    /** Take the road-free twin's navigation state (after repeated road-update rejections). */
    private fun syncFrom(f: BaselineDrEstimator) {
        val keepSeen = seenFreeSteps
        restore(f.snapshot())
        seenFreeSteps = keepSeen; roadRejects = 0
        lastRoadHeadingOdo = Double.NEGATIVE_INFINITY; lastRoadCrossOdo = Double.NEGATIVE_INFINITY
        lastRoadAlongOdo = Double.NEGATIVE_INFINITY
        roadStats[3] += 1000 // count resyncs in the thousands
    }

    /**
     * Accelerometer speed drives v only once the bias is known (σ ≤ [AccelSpeedConfig.biasMaxStd]); before
     * that, v keeps the random-walk model while the bias is learned (drive B: an unlearned bias pushed v to
     * 27–31 m/s at a true 17 within a minute of losing GNSS).
     */
    private fun accelMode(tNs: Long): Boolean = accelReady(tNs) &&
        // With fresh trusted GNSS, its speed bounds v and teaches the bias, so the bias need not be known yet.
        (sqrt(p[IDX_BA, IDX_BA]) <= cfg.accelSpeed.biasMaxStd || (lastGnssT != Long.MIN_VALUE && (tNs - lastGnssT) / 1e9 <= 2.0))

    /** No fresh vehicle speed, forward axis known, a recent fix: the bias may be learned, ZUPT may apply. */
    private fun accelReady(tNs: Long): Boolean =
        cfg.accelSpeed.enabled && lastALong != null &&
            (lastObdT == Long.MIN_VALUE || (tNs - lastObdT) / 1e9 > cfg.accelSpeed.obdFreshS) &&
            // Never open-loop: only while position fixes still bound the speed (R-026: without any fix for 25 min
            // the accelerometer speed drifted and its σ was optimistic; the random-walk model is the honest one).
            lastFixT != Long.MIN_VALUE && (tNs - lastFixT) / 1e9 <= cfg.accelSpeed.maxNoFixS

    /** Bias random-walk scale for an unsteady mount (see [AccelSpeedConfig.unsteadyTiltRate]). */
    private fun unsteadyScale(): Double {
        val ref = cfg.accelSpeed.unsteadyTiltRate ?: return 1.0
        val r = tiltRateLp ?: return 1.0
        return (r / ref).coerceIn(1.0, cfg.accelSpeed.unsteadyMaxScale)
    }

    /** Centripetal speed |a_lat| / |ω| over [AccelSpeedConfig.centripetalPeriodS], as a speed measurement. */
    private fun centripetal(u: MotionUpdate, accelMode: Boolean) {
        val ac = cfg.accelSpeed
        val aLat = u.lateralAccel ?: return
        if (centT == Long.MIN_VALUE) centT = u.tNs
        centLat += aLat * u.dtS; centYaw += u.yawRateUp * u.dtS; centN++
        val dur = (u.tNs - centT) / 1e9
        if (dur < ac.centripetalPeriodS) return
        val a = centLat / dur; val w = centYaw / dur
        centLat = 0.0; centYaw = 0.0; centN = 0; centT = u.tNs
        if (!ac.centripetal || !accelMode || stationary || !initialized || abs(w) < ac.centripetalMinYawRate) return
        // Left turn: ω > 0 (CCW) and the centripetal force points left (a_lat > 0), so v = a_lat / ω > 0.
        val z = a / w
        if (z < 1.0 || z > 40.0) return
        val tilt = Math.toRadians(ac.unsteadyCentripetalTiltDeg * (unsteadyScale() - 1))
        val sd = ac.centripetalRelStd * z + ac.centripetalAbsStd + GRAVITY * tilt / abs(w)
        val r = sd * sd
        val innov = z - x[IDX_V]
        if (innov * innov / (p[IDX_V, IDX_V] + r) <= 9.0) update1(IDX_V, z, r)
    }

    /** One road-matcher step per [RoadMatcherConfig.stepM] of driving, once the heading is known. */
    private fun roadStep() {
        val f = free
        if (f != null) {
            val fm = f.matcher
            if (f.roadSteps != seenFreeSteps && fm != null) {
                seenFreeSteps = f.roadSteps
                if (initialized && headingKnown) applyRoad(fm, f.recentTurns, f.gyroHist, f.odoM)
            }
            return
        }
        if (!isFreeTwin || !cfg.road.enabled || roads == null || !initialized || !headingKnown) return
        val net = roads.invoke() ?: return
        var m = matcher
        if (m == null || m.network !== net) { m = RoadMatcher(cfg.road, net); matcher = m; lastRoadOdo = odoM; gyroTurn = 0.0 }
        val ds = odoM - lastRoadOdo
        if (ds < cfg.road.stepM) return
        val ll = frame!!.toLatLon(x[0], x[1])
        m.step(
            RoadStepInput(
                ll.lat, ll.lon, Cov2(p[0, 0], p[0, 1], p[1, 1]), x[IDX_PSI], sqrt(p[IDX_PSI, IDX_PSI]),
                ds, gyroTurn, abs(x[IDX_V]),
            ),
        )
        recentTurns = (recentTurns + gyroTurn).takeLast(cfg.roadConstraint.gyroWindowSteps)
        cumGyro += gyroTurn
        gyroHist = (gyroHist + (odoM to cumGyro)).takeLast(cfg.road.trailSteps)
        lastRoadOdo = odoM; gyroTurn = 0.0
        roadSteps++
    }

    /**
     * Road pseudo-measurements (M2 heading, M3 cross-track; docs/road-constraint.md). Applied only when the
     * matcher is confident, the pose is good, and the car is moving; each is a soft, gated EKF update.
     */
    private fun applyRoad(m: RoadMatcher, turns: List<Double>, gHist: List<Pair<Double, Double>>, gOdo: Double) {
        val rc = cfg.roadConstraint
        val h = m.bestHyp() ?: return
        cornerFix(m, h, gHist, gOdo)
        if (m.probabilityOf(h) < rc.minProbability || h.confidentM < rc.minConfidentM || m.pOffRoad() > rc.maxPOffRoad) return
        val sMax = sqrt(maxOf(p[0, 0], p[1, 1]) + abs(p[0, 1]))
        if (sMax > rc.maxPoseSigmaM || abs(x[IDX_V]) < rc.minSpeedMps || sqrt(p[IDX_V, IDX_V]) > rc.maxSpeedStdMps) return
        val net = m.network
        val len = net.lengthOf(h.seg)
        val segBearing = net.bearingAt(h.seg, h.d)
        val travel = Math.toRadians(if (h.fwd) segBearing else segBearing + 180)
        if (rc.heading && odoM - lastRoadHeadingOdo >= rc.everyM) {
            val w = rc.straightWindowM
            val straight = len >= w && h.d >= 0 && h.d <= len &&
                abs(Geo.wrapDeg(net.bearingAt(h.seg, maxOf(0.0, h.d - w)) - segBearing)) < rc.straightMaxDeg &&
                abs(Geo.wrapDeg(net.bearingAt(h.seg, minOf(len, h.d + w)) - segBearing)) < rc.straightMaxDeg
            val gyroStraight = turns.size >= rc.gyroWindowSteps &&
                abs(Math.toDegrees(turns.sum())) < rc.gyroMaxDeg && turns.all { abs(Math.toDegrees(it)) < rc.gyroMaxDeg }
            val r = Math.toRadians(rc.headingStdDeg).let { it * it }
            // Repeated road headings are one piece of evidence (P5): never push the variance below R.
            if (straight && gyroStraight && p[IDX_PSI, IDX_PSI] > r) {
                val innov = Geo.wrapRad(travel - x[IDX_PSI])
                if (innov * innov / (p[IDX_PSI, IDX_PSI] + r) <= rc.gateNis) {
                    update1(IDX_PSI, travel, r, angular = true); roadStats[0]++
                } else roadStats[1]++
                lastRoadHeadingOdo = odoM
            }
        }
        if (rc.alongTrack && odoM - lastRoadAlongOdo >= rc.alongEveryM && gHist.size >= 3) {
            val gyro = gHist.reversed().map { (o, c) -> gpes.core.road.AlongTrackMatch.Sample(gOdo - o, c) }
            val res = gpes.core.road.AlongTrackMatch.match(gyro, m.roadProfile(h), gpes.core.road.AlongTrackMatch.Config(minTurnDeg = rc.alongMinTurnDeg))
            if (res != null) {
                val pt = net.toLatLon(gpes.core.model.RoadState(h.seg, h.d, h.fwd))
                val z = frame!!.toEnu(pt.lat, pt.lon)
                val ax = sin(travel); val ay = cos(travel)
                val hRow = DoubleArray(N).also { it[0] = ax; it[1] = ay }
                val innov = ax * (z.e - x[0]) + ay * (z.n - x[1]) + res.shiftM
                val r = res.sigmaM * res.sigmaM + cfg.road.osmGeometryStdM * cfg.road.osmGeometryStdM
                var prior = ax * ax * p[0, 0] + 2 * ax * ay * p[0, 1] + ay * ay * p[1, 1]
                // Cross-track updates on differently oriented streets shrink the along-track variance without
                // real along-track evidence; restore it to the road-free twin's before using the shape.
                free?.let { f ->
                    val fa = ax * ax * f.p[0, 0] + 2 * ax * ay * f.p[0, 1] + ay * ay * f.p[1, 1]
                    if (fa > prior) {
                        val add = fa - prior
                        p[0, 0] += add * ax * ax; p[0, 1] += add * ax * ay; p[1, 0] += add * ax * ay; p[1, 1] += add * ay * ay
                        prior = fa
                    }
                }
                if (prior > r && innov * innov / (prior + r) <= rc.gateNis) { updateH(hRow, innov, r); roadStats[0] += 1_000_000 }
                lastRoadAlongOdo = odoM
            }
        }
        // Near a segment end we may already be on the next street, whose normal is our old along-track axis.
        val endMargin = rc.endMarginM + rc.endMarginSigmas * sMax
        val awayFromEnds = h.d >= endMargin && h.d <= len - endMargin
        if (rc.crossTrack && awayFromEnds && odoM - lastRoadCrossOdo >= rc.everyM) {
            val pt = net.toLatLon(gpes.core.model.RoadState(h.seg, h.d, h.fwd))
            val z = frame!!.toEnu(pt.lat, pt.lon)
            val b = Math.toRadians(segBearing)
            val nx = cos(b); val ny = -sin(b) // right of the segment's forward direction
            val hRow = DoubleArray(N).also { it[0] = nx; it[1] = ny }
            val innov = nx * (z.e - x[0]) + ny * (z.n - x[1])
            val half = gpes.core.road.RoadWidth.halfWidthM(net.segment(h.seg)!!)
            val r = half * half / 3 + cfg.road.osmGeometryStdM * cfg.road.osmGeometryStdM
            val prior = nx * nx * p[0, 0] + 2 * nx * ny * p[0, 1] + ny * ny * p[1, 1]
            if (prior > r) { // same floor as the heading (P5)
                if (innov * innov / (prior + r) <= rc.gateNis) {
                    updateH(hRow, innov, r); roadStats[2]++; roadRejects = 0
                } else {
                    roadStats[3]++
                    // The matcher (on the road-free twin) moved to another road, or we drifted off it: resync.
                    if (++roadRejects >= rc.resyncAfterRejects) free?.let { syncFrom(it) }
                }
                lastRoadCrossOdo = odoM
            }
        }
    }

    /** Compass heading: initializes an unknown heading, otherwise a weak, gated update (errors are time-correlated). */
    private fun applyCompass(r: CompassReading) {
        val r2 = r.sigmaRad * r.sigmaRad
        if (!headingKnown) {
            x[IDX_PSI] = Geo.wrapRad(r.bearingRad)
            for (i in 0 until N) { p[IDX_PSI, i] = 0.0; p[i, IDX_PSI] = 0.0 }
            p[IDX_PSI, IDX_PSI] = r2
            headingKnown = true
            dirlessDist = 0.0
            bank.reset()
            return
        }
        if (sqrt(p[IDX_PSI, IDX_PSI]) < cfg.compass.usefulFraction * r.sigmaRad) return
        val innov = Geo.wrapRad(r.bearingRad - x[IDX_PSI])
        if (innov * innov / (p[IDX_PSI, IDX_PSI] + r2) > cfg.compass.gateNis) return
        update1(IDX_PSI, r.bearingRad, r2, angular = true)
    }

    override fun onMeasurement(m: Measurement, trust: TrustAssessment?) {
        free?.onMeasurement(m, trust)
        when (m) {
            is LocationMeasurement -> onLocation(m, trust)
            is ImuSample -> compass.onMag(m)
            is GeomagneticReference -> compass.onReference(m)
            is PowerState -> compass.onPower(m)
            is VehicleSpeedMeasurement -> {
                propagateTo(m.tNs, lastYawRate)
                lastObdT = m.tNs
                obdHist = (obdHist + (m.tNs to m.speedMps)).filter { (m.tNs - it.first) / 1e9 <= cfg.obdSlopeWindowS }
                val zSpeed = m.speedMps + cfg.obdLatencyS * obdSlope()
                // Speedometer model: z = v·(1 + s). The scale error s (tyre wear, tyre size, OEM
                // over-reading) is learned while trusted GNSS speed is available, then kept during outages.
                val h = DoubleArray(N).also { it[IDX_V] = 1 + x[IDX_S]; it[IDX_S] = x[IDX_V] }
                updateH(h, zSpeed - x[IDX_V] * (1 + x[IDX_S]), max(m.stdMps, 0.05).let { it * it })
                clampSpeed()
                speedScaleStatus = x[IDX_S] to sqrt(p[IDX_S, IDX_S])
            }
            else -> Unit
        }
    }

    private fun onLocation(m: LocationMeasurement, trust: TrustAssessment?) {
        val state = trust?.state ?: return
        val usable = when (m.source) {
            LocSource.GNSS -> true
            LocSource.FUSED -> cfg.useFused
            LocSource.NETWORK -> true
            else -> false
        }
        if (!usable || m.isSynthetic) return
        propagateTo(m.tNs, lastYawRate)

        val isCoarse = m.source == LocSource.NETWORK
        val rScale = when (state) {
            TrustState.TRUSTED -> 1.0
            TrustState.QUESTIONABLE -> if (isCoarse) 1.0 else cfg.questionableRScale ?: return
            else -> return
        }
        val acc = m.hAccM ?: return
        if (acc <= cfg.accelSpeed.anchorMaxAccM) lastFixT = m.tNs
        var sigma = acc / Cov2.R68_PER_SIGMA
        if (isCoarse) sigma *= cfg.networkInflation
        val r = sigma * sigma * rScale

        if (!initialized) {
            initAt(m.lat, m.lon, r)
            if (!isCoarse) applyGnssVelocity(m, rScale)
            if (!isCoarse) lastGnssT = m.tNs
            return
        }

        if (isCoarse && !headingKnown && cfg.headingBank.enabled) {
            bank.onFix(m.lat, m.lon, r)
            if (bank.converged()) {
                takeBankHeading()
                return
            }
        }

        if (isCoarse && !roadAllowsFix(m)) return

        val z = frame!!.toEnu(m.lat, m.lon)
        if (isCoarse) {
            val posSigma = sqrt((p[0, 0] + p[1, 1]) / 2)
            val spaced = lastNetT == Long.MIN_VALUE ||
                ((m.tNs - lastNetT) / 1e9 >= cfg.networkMinIntervalS && odoM - lastNetOdo >= cfg.networkMinDistanceM)
            if (posSigma < cfg.networkUsefulFraction * sigma && !spaced) return
            lastNetT = m.tNs; lastNetOdo = odoM
            val nis = nis2(z.e, z.n, r)
            val robust = cfg.coarseRobustNis
            when {
                robust == null -> if (nis > cfg.networkResetNis) resetPosition(m.lat, m.lon, r) else updatePos(z.e, z.n, r)
                nis <= robust -> { candidates = emptyList(); updatePos(z.e, z.n, r, coarse = true) }
                else -> onCoarseCandidate(m, z.e, z.n, r, nis, robust)
            }
        } else {
            val nis = nis2(z.e, z.n, r)
            if (nis > cfg.gnssResetNis && state == TrustState.TRUSTED) resetPosition(m.lat, m.lon, r)
            else updatePos(z.e, z.n, r)
            applyGnssVelocity(m, rScale)
            lastGnssT = m.tNs
        }
        reanchorIfNeeded()
    }

    private fun applyGnssVelocity(m: LocationMeasurement, rScale: Double) {
        val speed = m.speedMps ?: return
        val sAcc = max(m.speedAccMps ?: 0.5, 0.2)
        update1(IDX_V, speed, sAcc * sAcc * rScale)
        clampSpeed()
        val bearing = m.bearingDeg ?: return
        if (speed < cfg.minCourseSpeedMps) return
        val bAcc = Math.toRadians(max(m.bearingAccDeg ?: 3.0, 1.0))
        val zPsi = Math.toRadians(bearing)
        if (rScale == 1.0) compass.addCalibration(m.tNs, zPsi, speed, lastYawRate)
        if (!headingKnown || abs(Geo.wrapRad(zPsi - x[IDX_PSI])) > Math.toRadians(60.0)) {
            // (Re)initialize heading: decorrelate it from everything else.
            x[IDX_PSI] = zPsi
            for (i in 0 until N) { p[IDX_PSI, i] = 0.0; p[i, IDX_PSI] = 0.0 }
            p[IDX_PSI, IDX_PSI] = bAcc * bAcc * rScale
            headingKnown = true
            dirlessDist = 0.0
            bank.reset()
        } else {
            update1(IDX_PSI, zPsi, bAcc * bAcc * rScale, angular = true)
        }
    }

    /** Adopt the bank's mixture: heading, position and their joint covariance; decorrelate from the rest. */
    private fun takeBankHeading() {
        val (lat, lon, c) = bank.mixture() ?: return
        val z = frame!!.toEnu(lat, lon)
        x[0] = z.e; x[1] = z.n
        x[IDX_PSI] = bank.heading()!!.first
        val idx = intArrayOf(0, 1, IDX_PSI)
        for (i in idx) for (j in 0 until N) { p[i, j] = 0.0; p[j, i] = 0.0 }
        val k = cfg.headingBank.handoverPosStdScale
        val scale = doubleArrayOf(k, k, 1.0)
        for (a in 0..2) for (b in 0..2) p[idx[a], idx[b]] = c[a * 3 + b] * scale[a] * scale[b]
        headingKnown = true
        dirlessDist = 0.0
        bank.reset()
    }

    /**
     * A coarse fix far from the prediction. One such fix is most likely wrong: update with R scaled by
     * NIS/threshold (Huber-like), so it barely moves position or heading. But if several in a row agree
     * with each other (their spacing matches the odometry chord), it is our estimate that is off: reset
     * onto the latest fix (and, after a long GNSS-free stretch, restart the heading search). The offset
     * from us is deliberately not required to be constant: under GNSS spoofing our estimate is dragged
     * between fixes, and that criterion blocked the recovery (R-012).
     */
    private fun onCoarseCandidate(m: LocationMeasurement, ze: Double, zn: Double, r: Double, nis: Double, threshold: Double) {
        val c = Candidate(m.tNs, m.lat, m.lon, r, relE, relN, odoM)
        var list = candidates + c
        // Keep only the tail in which consecutive candidates agree.
        var start = 0
        for (i in 1 until list.size) if (!agree(list[i - 1], list[i])) start = i
        list = list.drop(start)
        if (list.size >= cfg.coarseStreamMin && (list.last().tNs - list.first().tNs) / 1e9 >= cfg.coarseStreamMinSpanS) {
            candidates = emptyList()
            resetPosition(m.lat, m.lon, r)
            // After a long dead-reckoning stretch, a drift this large almost always means a wrong heading:
            // search again. With recent GNSS the drift came from GNSS (spoofing), and the heading is fine.
            val gnssRecent = lastGnssT != Long.MIN_VALUE && (m.tNs - lastGnssT) / 1e9 <= cfg.coarseStreamGnssRecentS
            if (cfg.headingBank.enabled && !gnssRecent) {
                headingKnown = false
                for (i in 0 until N) { p[IDX_PSI, i] = 0.0; p[i, IDX_PSI] = 0.0 }
                p[IDX_PSI, IDX_PSI] = PI * PI
                bank.reset()
                bank.onFix(m.lat, m.lon, r)
            }
            return
        }
        candidates = list
        updatePos(ze, zn, r * nis / threshold, coarse = true)
    }

    private fun agree(a: Candidate, b: Candidate): Boolean {
        val sig = sqrt(a.r + b.r)
        val odo = b.odo - a.odo
        val d = Geo.haversineM(a.lat, a.lon, b.lat, b.lon)
        val chord = hypot(b.relE - a.relE, b.relN - a.relN)
        return kotlin.math.abs(d - chord) <= cfg.coarseStreamK * sig + 0.05 * odo + 10.0
    }

    // ------------------------------------------------------------------------------------------
    // Propagation

    private fun propagateTo(tNs: Long, yawRate: Double) {
        if (lastT == Long.MIN_VALUE) { lastT = tNs; return }
        val dt = (tNs - lastT) / 1e9
        if (dt <= 0) return
        propagate(dt, yawRate)
        lastT = tNs
    }

    private fun propagate(dt: Double, yawRate: Double) {
        val psi = x[IDX_PSI]
        val v = x[IDX_V]
        odoM += abs(v) * dt
        relE += v * sin(psi) * dt
        relN += v * cos(psi) * dt
        val f = Mat.identity(N)
        val q = Mat(N, N)

        x[IDX_PSI] = Geo.wrapRad(psi - (yawRate - x[IDX_B]) * dt)
        gyroTurn -= (yawRate - x[IDX_B]) * dt
        f[IDX_PSI, IDX_B] = dt
        val turn = (yawRate - x[IDX_B]) * dt * cfg.gyroScaleError
        q[IDX_PSI, IDX_PSI] = cfg.headingRandomWalk * cfg.headingRandomWalk * dt + turn * turn
        val aL = lastALong
        if (aL != null && accelMode(lastT)) {
            // Specific force along the vehicle drives the speed; its bias is a state (never open-loop, D-050).
            x[IDX_V] = v + (aL - x[IDX_BA]) * dt
            f[IDX_V, IDX_BA] = -dt
            q[IDX_V, IDX_V] = cfg.accelSpeed.accelNoise * cfg.accelSpeed.accelNoise * dt
            val rw = cfg.accelSpeed.biasRandomWalk * unsteadyScale()
            q[IDX_BA, IDX_BA] = rw * rw * dt
        } else {
            q[IDX_V, IDX_V] = cfg.speedRandomWalk * cfg.speedRandomWalk * dt
        }
        q[IDX_B, IDX_B] = cfg.biasRandomWalk * cfg.biasRandomWalk * dt
        q[IDX_S, IDX_S] = cfg.speedScaleRandomWalk * cfg.speedScaleRandomWalk * dt

        if (initialized && headingKnown) {
            x[0] += v * sin(psi) * dt
            x[1] += v * cos(psi) * dt
            f[0, IDX_PSI] = v * cos(psi) * dt; f[0, IDX_V] = sin(psi) * dt
            f[1, IDX_PSI] = -v * sin(psi) * dt; f[1, IDX_V] = cos(psi) * dt
            q[0, 0] = cfg.posRandomWalk * cfg.posRandomWalk * dt
            q[1, 1] = q[0, 0]
        }
        p = (f * p * f.t() + q).symmetrize()
        if (x[IDX_V] < 0 && aL != null) x[IDX_V] = 0.0 // no reverse detection yet: OBD has no sign either

        if (initialized && !headingKnown) {
            bank.propagate(dt, yawRate - x[IDX_B], v, p[IDX_V, IDX_V])
            // Unknown direction: grow isotropically with a conservative distance bound.
            val ds = (abs(v) + 2 * sqrt(p[IDX_V, IDX_V])) * dt
            val d0 = dirlessDist
            dirlessDist += ds
            val dVar = (dirlessDist * dirlessDist - d0 * d0) / 2
            p[0, 0] += dVar; p[1, 1] += dVar
        }
        if (headingKnown && p[IDX_PSI, IDX_PSI] > PI * PI) { headingKnown = false; bank.reset() }
    }

    // ------------------------------------------------------------------------------------------
    // Updates

    /** Scalar update with a general measurement row [h] and innovation [innov]. */
    private fun updateH(h: DoubleArray, innov: Double, r: Double) {
        val ph = DoubleArray(N) { i -> (0 until N).sumOf { j -> p[i, j] * h[j] } }
        val s = (0 until N).sumOf { h[it] * ph[it] } + r
        val k = DoubleArray(N) { ph[it] / s }
        for (i in 0 until N) x[i] += k[i] * innov
        x[IDX_PSI] = Geo.wrapRad(x[IDX_PSI])
        val newP = p.copy()
        for (i in 0 until N) for (j in 0 until N) newP[i, j] = p[i, j] - k[i] * ph[j]
        p = newP.symmetrize()
    }

    /**
     * Scalar update of state [idx] only (a Schmidt-style "consider" update): the other states keep their
     * values, and P is updated with the Joseph form for that suboptimal gain, so it stays consistent.
     */
    private fun updateLocal(idx: Int, z: Double, r: Double) {
        val k = p[idx, idx] / (p[idx, idx] + r)
        x[idx] += k * (z - x[idx])
        // A = I − K·H with K = k·e_idx, H = e_idx: only row idx of A differs from I.
        val a = Mat.identity(N).also { it[idx, idx] = 1 - k }
        val kk = Mat(N, N).also { it[idx, idx] = k * k * r }
        p = (a * p * a.t() + kk).symmetrize()
    }

    /** Current speedometer scale estimate (fraction) and its std, for diagnostics. */
    fun speedScale(): Pair<Double, Double> = x[IDX_S] to sqrt(p[IDX_S, IDX_S])

    private fun update1(idx: Int, z: Double, r: Double, angular: Boolean = false) {
        var innov = z - x[idx]
        if (angular) innov = Geo.wrapRad(innov)
        val s = p[idx, idx] + r
        val k = DoubleArray(N) { p[it, idx] / s }
        for (i in 0 until N) x[i] += k[i] * innov
        x[IDX_PSI] = Geo.wrapRad(x[IDX_PSI])
        val newP = p.copy()
        for (i in 0 until N) for (j in 0 until N) newP[i, j] = p[i, j] - k[i] * p[idx, j]
        p = newP.symmetrize()
    }

    private fun nis2(ze: Double, zn: Double, r: Double): Double {
        val s = Mat(2, 2, doubleArrayOf(p[0, 0] + r, p[0, 1], p[1, 0], p[1, 1] + r))
        val v = Mat(2, 1, doubleArrayOf(ze - x[0], zn - x[1]))
        return (v.t() * s.inv() * v)[0, 0]
    }

    private fun updatePos(ze: Double, zn: Double, r: Double, coarse: Boolean = false) {
        val h = Mat(2, N).also { it[0, 0] = 1.0; it[1, 1] = 1.0 }
        val s = h * p * h.t() + Mat.diag(r, r)
        val k = p * h.t() * s.inv()
        if (coarse && cfg.coarseHeadingGain != 1.0) {
            for (c in 0..1) { k[IDX_PSI, c] *= cfg.coarseHeadingGain; k[IDX_B, c] *= cfg.coarseHeadingGain }
        }
        val innov = Mat(2, 1, doubleArrayOf(ze - x[0], zn - x[1]))
        val dx = k * innov
        for (i in 0 until N) x[i] += dx[i, 0]
        x[IDX_PSI] = Geo.wrapRad(x[IDX_PSI])
        // Joseph form for stability.
        val ikh = Mat.identity(N) - k * h
        p = (ikh * p * ikh.t() + k * Mat.diag(r, r) * k.t()).symmetrize()
        dirlessDist = 0.0
    }

    private fun initAt(lat: Double, lon: Double, r: Double) {
        frame = LocalFrame(lat, lon)
        x[0] = 0.0; x[1] = 0.0
        for (i in 0 until N) { p[0, i] = 0.0; p[i, 0] = 0.0; p[1, i] = 0.0; p[i, 1] = 0.0 }
        p[0, 0] = r; p[1, 1] = r
        initialized = true
        dirlessDist = 0.0
    }

    private fun resetPosition(lat: Double, lon: Double, r: Double) = initAt(lat, lon, r)

    private fun clampSpeed() { if (x[IDX_V] < 0) x[IDX_V] = 0.0 }

    private fun reanchorIfNeeded() {
        val fr = frame ?: return
        if (hypot(x[0], x[1]) < Geo.REANCHOR_DISTANCE_M) return
        val ll = fr.toLatLon(x[0], x[1])
        frame = LocalFrame(ll.lat, ll.lon)
        x[0] = 0.0; x[1] = 0.0
    }

    // ------------------------------------------------------------------------------------------

    override fun estimate(tNs: Long): PositionEstimate? {
        if (!initialized) return null
        val saved = snapshot()
        try {
            propagateTo(tNs, lastYawRate)
            reanchorIfNeeded()
            val ll = frame!!.toLatLon(x[0], x[1])
            val cov = roadHonestCov(Cov2(p[0, 0], p[0, 1], p[1, 1]), tNs)
            val speedStd = sqrt(p[IDX_V, IDX_V])
            val mode = when {
                lastGnssT != Long.MIN_VALUE && (tNs - lastGnssT) / 1e9 <= cfg.gnssTrackingWindowS -> EstimatorMode.GNSS_TRACKING
                stationary -> EstimatorMode.STATIONARY
                headingKnown && speedStd < cfg.speedKnownStd -> EstimatorMode.DEAD_RECKONING
                else -> EstimatorMode.COARSE_ONLY
            }
            val confidence = 1.0 / (1.0 + cov.r68 / 50.0)
            return PositionEstimate(
                tNs = tNs, estimator = name, lat = ll.lat, lon = ll.lon, cov = cov,
                headingRad = if (headingKnown) (x[IDX_PSI] + 2 * PI) % (2 * PI) else null,
                headingStdRad = if (headingKnown) sqrt(p[IDX_PSI, IDX_PSI]) else null,
                speedMps = x[IDX_V], speedStdMps = speedStd,
                mode = mode, confidence = confidence,
                hypotheses = listOf(Hypothesis(1.0, ll.lat, ll.lon, cov)),
                road = (free?.matcher ?: matcher)?.best(),
            )
        } finally {
            restore(saved)
        }
    }

    /**
     * Reported uncertainty with the road constraint: the road-free twin's covariance. The road improves the
     * position, but the matcher sometimes picks a wrong road while confident (4–10 %, R-020), and repeated
     * road updates are correlated; narrowing the radius gave within95 0.18–0.76. Until real-drive
     * calibration says otherwise, the radius stays as conservative as without the road (honest uncertainty).
     */
    private fun roadHonestCov(c: Cov2, tNs: Long): Cov2 {
        val f = free ?: return c
        val fc = f.estimate(tNs)?.cov ?: return c
        return if (fc.r68 >= c.r68) fc else c
    }

    override fun snapshot(): Any = Snap(
        initialized, frame, x.copyOf(), p.a.copyOf(), headingKnown, lastT, lastYawRate, stationary, lastGnssT, dirlessDist,
        compass.copy(), nextCompassT, odoM, lastNetT, lastNetOdo, bank.copy(), relE, relN, candidates,
        matcher?.copy(), lastRoadOdo, gyroTurn, recentTurns, lastRoadHeadingOdo, lastRoadCrossOdo,
        roadSteps, seenFreeSteps, roadRejects, free?.snapshot(), gyroHist, cumGyro, lastRoadAlongOdo,
        heldOffset, heldT, obdHist, lastCornerOdo,
        lastALong, lastObdT, centLat, centYaw, centN, centT, stillSinceT, lastFixT, tiltRateLp,
    )

    override fun restore(snapshot: Any) {
        val s = snapshot as Snap
        initialized = s.initialized; frame = s.frame
        s.x.copyInto(x); p = Mat(N, N, s.p.copyOf())
        headingKnown = s.headingKnown; lastT = s.lastT; lastYawRate = s.lastYawRate
        stationary = s.stationary; lastGnssT = s.lastGnssT; dirlessDist = s.dirlessDist
        compass = s.compass.copy(); nextCompassT = s.nextCompassT
        odoM = s.odoM; lastNetT = s.lastNetT; lastNetOdo = s.lastNetOdo
        bank = s.bank.copy()
        relE = s.relE; relN = s.relN; candidates = s.candidates
        matcher = s.matcher?.copy(); lastRoadOdo = s.lastRoadOdo; gyroTurn = s.gyroTurn
        recentTurns = s.recentTurns; lastRoadHeadingOdo = s.lastRoadHeadingOdo; lastRoadCrossOdo = s.lastRoadCrossOdo
        roadSteps = s.roadSteps; seenFreeSteps = s.seenFreeSteps; roadRejects = s.roadRejects
        gyroHist = s.gyroHist; cumGyro = s.cumGyro; lastRoadAlongOdo = s.lastRoadAlongOdo
        heldOffset = s.heldOffset; heldT = s.heldT; obdHist = s.obdHist; lastCornerOdo = s.lastCornerOdo
        lastALong = s.lastALong; lastObdT = s.lastObdT; centLat = s.centLat; centYaw = s.centYaw; centN = s.centN; centT = s.centT
        stillSinceT = s.stillSinceT; lastFixT = s.lastFixT; tiltRateLp = s.tiltRateLp
        if (free != null && s.free != null) free.restore(s.free)
    }

    private companion object {
        const val N = 7
        const val IDX_PSI = 2
        const val IDX_V = 3
        const val IDX_B = 4
        const val IDX_S = 5
        /** Bias of the longitudinal specific force (m/s²), used only in accelerometer-speed mode. */
        const val IDX_BA = 6
        const val GRAVITY = 9.80665
    }
}
