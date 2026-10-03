package gpes.core.motion

import gpes.core.model.ImuKind
import gpes.core.model.ImuSample
import gpes.core.model.Measurement
import gpes.core.model.OrientationKind
import gpes.core.model.OrientationSample
import gpes.core.model.VehicleSpeedMeasurement
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Small immutable 3-vector (phone frame unless stated otherwise). */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val norm: Double get() = sqrt(x * x + y * y + z * z)
    fun unit(): Vec3? = norm.let { if (it < 1e-9) null else this * (1 / it) }
    /** Component perpendicular to unit vector [u]. */
    fun perp(u: Vec3) = this - u * (this dot u)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
    }
}

/**
 * Derived vehicle motion at a fixed rate. The yaw rate is the gyro projected on the gravity
 * direction, so it is independent of how the phone is mounted, and it is **counter-clockwise
 * positive** (right-hand rule about "up"). Bearing therefore changes by −yawRateUp.
 */
data class MotionUpdate(
    val tNs: Long,
    val dtS: Double,
    val yawRateUp: Double,
    val stationary: Boolean,
    val stationaryForS: Double,
    val gyroNormMean: Double,
    val accelStd: Double,
    /** World "up" in the phone frame (unit), or null before the first accelerometer sample. */
    val up: Vec3? = null,
    /** Vehicle forward axis in the phone frame (unit, horizontal), once learned from turns. */
    val forward: Vec3? = null,
    /** Increments whenever the phone is re-mounted (a large tilt). Mount-dependent calibrations reset on change. */
    val mountEpoch: Int = 0,
    /** RMS angular rate about horizontal axes over the last ~0.5 s (rad/s): phone shaking / holder wobble. */
    val tiltRateRms: Double = 0.0,
    /** Current horizontal specific force (m/s²): accelerating, braking, cornering or bumps. */
    val horizontalAccel: Double = 0.0,
    /** True when [up] comes from a gyro-stabilized rotation vector (robust to acceleration), false for low-passed accel. */
    val upFromOrientation: Boolean = false,
    /** Mean specific force along the vehicle forward axis over this update (m/s²), once [forward] is known. */
    val longitudinalAccel: Double? = null,
    /** Mean specific force towards the vehicle's left over this update (m/s²), once [forward] is known. */
    val lateralAccel: Double? = null,
    /**
     * Stopped by the real-car rule (R-025: ‖a‖ std < 0.3 m/s², mean ‖ω‖ < 0.02 rad/s over 1 s): catches 74–87 % of
     * real stops vs 10–47 % for [stationary], which is tuned to the simulator. Used for ZUPT without OBD.
     */
    val stillLoose: Boolean = false,
)

/**
 * Dead-reckoned path between two times from vehicle speed (OBD) and the gyro bearing change.
 * [chordM] is the straight-line displacement: it does not depend on the (unknown) absolute heading,
 * so it constrains coarse fixes even when nothing knows which way the car points.
 */
/**
 * Path driven between two times. [dE], [dN] is the displacement in the gyro's relative frame (bearing =
 * cumulative gyro bearing, zero at an arbitrary start), and [relBearingEnd] the relative bearing at the
 * end time: absolute bearing = relative bearing + (estimated heading − [relBearingEnd]).
 */
data class Odometry(
    val distanceM: Double,
    val chordM: Double,
    val dE: Double = 0.0,
    val dN: Double = 0.0,
    val relBearingEnd: Double? = null,
)

data class MotionConfig(
    val updatePeriodNs: Long = 50_000_000,
    val windowNs: Long = 1_000_000_000,
    /** Low-pass for gravity. Long enough that centripetal/braking acceleration barely tilts it (the phone is mounted). */
    val gravityTauS: Double = 5.0,
    val stationaryAccelStd: Double = 0.12,
    val stationaryGyroNorm: Double = 0.03,
    /** Real-car stop rule (see [MotionUpdate.stillLoose]). */
    val stillLooseAccelStd: Double = 0.3,
    val stillLooseGyroNorm: Double = 0.02,
    val historyNs: Long = 600_000_000_000,
    /** An orientation sample is used for gravity only if it is this fresh. */
    val orientationMaxAgeNs: Long = 500_000_000,
    /** Forward-axis learning: minimum |yaw rate| (rad/s) and horizontal acceleration (m/s²) in a turn. */
    val mountMinYawRate: Double = 0.08,
    val mountMinLateralAccel: Double = 0.4,
    /** Samples (gyro rate) and concentration needed before the forward axis is reported. */
    /** About two 90° turns at 100 Hz gyro, so a single turn with braking cannot fix the axis. */
    val mountMinSamples: Int = 1000,
    val mountMinConcentration: Double = 0.6,
    /** Net non-yaw rotation within [remountWindowNs] that counts as re-mounting the phone (rad). */
    val remountTiltRad: Double = 0.35,
    val remountWindowNs: Long = 3_000_000_000,
    /** Vehicle speed older than this does not count for odometry; the interval then has a gap. */
    val odoMaxSpeedAgeNs: Long = 1_500_000_000,
    /**
     * Gravity ("down") estimator: the gyro carries the up vector, the accelerometer pulls it back with
     * this time constant (s), only while the car is not accelerating or turning (D-037). Android's
     * GRAVITY / rotation vectors lean towards the apparent gravity in turns (dev-guide P14).
     * null = old behaviour (Android rotation vector if fresh, else low-passed accelerometer).
     */
    val upTauS: Double? = 30.0,
    /**
     * Accelerometer correction gate: | |a| − m | below this (m/s²), where m is the long-term mean of |a|
     * on this phone (its scale error makes |a| at rest differ from 9.81 by more than this), and
     * |yaw rate| below [upGateYawRate].
     */
    val upGateAccel: Double = 0.3,
    val upGateYawRate: Double = 0.05,
    /** Time constant of the yaw rate used by that gate (s). */
    val upGateYawTauS: Double = 0.5,
    /** … and only while the smoothed specific force is nearly vertical (m/s² horizontal, ≈ 3°). */
    val upGateHorizontal: Double = 0.5,
    /** Time constant of the long-term mean of |a| (s). */
    val upNormTauS: Double = 120.0,
    /**
     * At a stop (real-car stop rule continuously for [upStillMinS]) the accelerometer is exactly gravity, so up is
     * pulled to it with this shorter time constant instead of [upTauS] (D-072): a hand-held phone was 4–15° off
     * (drive 20261003-140822), which leaks 0.7–2.5 m/s² of gravity into the longitudinal acceleration and ran the
     * speed to 70 m/s. null = [upTauS] only.
     */
    val upStillTauS: Double? = 3.0,
    val upStillMinS: Double = 3.0,
    /** … and only while the estimator's own speed is below this (m/s): smooth steady acceleration also passes the stop rule. */
    val upStillMaxSpeedMps: Double = 1.5,
    /** … and only while the specific force is steady: |a(0.1 s) − a(1 s)| below this (m/s²), so a pull-away is not mistaken for tilt. */
    val upStillMaxOnset: Double = 0.15,
)

/**
 * Turns raw IMU samples into [MotionUpdate]s. It also:
 *  - keeps a cumulative-yaw history, so that trust checks can ask "how much did the car turn between
 *    t1 and t2" independently of GNSS;
 *  - learns the vehicle forward axis in the phone frame from turns. The centripetal acceleration
 *    points to the turn centre, so sign(yawRate)·a_horizontal is the vehicle's *left*, and
 *    forward = left × up. This needs no speed and has no sign ambiguity;
 *  - detects re-mounting (a large net rotation that is not about the up axis);
 *  - integrates vehicle speed along the gyro bearing into a relative path ([odometry]).
 */
class MotionTracker(private val cfg: MotionConfig = MotionConfig()) {

    data class State(
        val g: Vec3, val gravityInit: Boolean,
        val orientUp: Vec3?, val orientT: Long,
        val lastAccel: Vec3?, val lastAccelT: Long,
        val lastGyroT: Long, val yawAccum: Double, val yawAccumDt: Double,
        val lastEmitT: Long, val cumYawBearing: Double,
        val stationarySinceNs: Long?, val seenCalibratedGyro: Boolean,
        val accWindow: List<Pair<Long, Double>>, val gyroWindow: List<Pair<Long, Double>>,
        val leftSum: Vec3, val leftWeight: Double, val leftCount: Int,
        val tiltWindow: List<Pair<Long, Vec3>>, val mountEpoch: Int, val remountAt: Long,
        val tiltRateWindow: List<Pair<Long, Double>>,
        val lastSpeed: Double, val lastSpeedT: Long,
        val odoE: Double, val odoN: Double, val odoDist: Double, val odoGaps: Int,
        val gyroUp: Vec3?, val accelLp: Vec3?, val accelLpSlow: Vec3?, val lastGyroW: Vec3?, val accelNormMean: Double?, val yawRateLp: Double,
        val accSinceEmit: Vec3, val accCountSinceEmit: Int, val lastStillLoose: Boolean, val stillLooseSinceNs: Long, val stillLooseLastNs: Long, val speedHintMps: Double?,
    )

    // Gravity (specific force at rest points up) in the phone frame, low-passed accelerometer.
    private var g = Vec3.ZERO
    private var gravityInit = false
    private var orientUp: Vec3? = null
    private var orientT = Long.MIN_VALUE
    private var lastAccel: Vec3? = null
    /** Accelerometer samples since the last update, for its mean longitudinal / lateral specific force. */
    private var accSinceEmit = Vec3.ZERO
    private var accCountSinceEmit = 0
    private var lastAccelT = Long.MIN_VALUE

    private var lastGyroT = Long.MIN_VALUE
    private var yawAccum = 0.0
    private var yawAccumDt = 0.0
    private var lastEmitT = Long.MIN_VALUE
    private var cumYawBearing = 0.0
    private var stationarySinceNs: Long? = null
    private var seenCalibratedGyro = false

    private val accWindow = ArrayDeque<Pair<Long, Double>>()
    private val gyroWindow = ArrayDeque<Pair<Long, Double>>()

    // Mount learning.
    private var leftSum = Vec3.ZERO
    private var leftWeight = 0.0
    private var leftCount = 0
    private val tiltWindow = ArrayDeque<Pair<Long, Vec3>>()
    private var tiltSum = Vec3.ZERO
    private var mountEpoch = 0
    private var remountAt = Long.MIN_VALUE
    private val tiltRateWindow = ArrayDeque<Pair<Long, Double>>()

    // Gravity estimator (gyro-propagated up, slow gated accelerometer correction).
    private var gyroUp: Vec3? = null
    private var accelLp: Vec3? = null
    /** Accelerometer low-passed over ~1 s: its difference to [accelLp] flags the onset of a vehicle acceleration. */
    private var accelLpSlow: Vec3? = null
    private var lastGyroW: Vec3? = null
    /** Yaw rate low-passed for the up-correction gate (rad/s). */
    private var yawRateLp = 0.0
    /** Real-car stop rule at the last update (for the up-correction gate). */
    private var lastStillLoose = false
    private var stillLooseSinceNs = Long.MIN_VALUE
    private var stillLooseLastNs = Long.MIN_VALUE

    /** The estimator's speed (m/s), set by the pipeline once a second; null = unknown. Only gates the fast up correction at stops. */
    var speedHintMps: Double? = null
    private var accelNormMean: Double? = null

    // Odometry: vehicle speed integrated along the cumulative gyro bearing (relative frame).
    private var lastSpeed = 0.0
    private var lastSpeedT = Long.MIN_VALUE
    private var odoE = 0.0
    private var odoN = 0.0
    private var odoDist = 0.0
    private var odoGaps = 0

    // History (cumulative bearing change and odometry): parallel arrays, appended at each update.
    private var histT = LongArray(1024)
    private var histYaw = DoubleArray(1024)
    private var histE = DoubleArray(1024)
    private var histN = DoubleArray(1024)
    private var histD = DoubleArray(1024)
    private var histGap = IntArray(1024)
    private var histStart = 0
    private var histEnd = 0

    var latest: MotionUpdate? = null
        private set

    /** Feed a measurement. Returns a [MotionUpdate] when one is due. */
    fun onMeasurement(m: Measurement): MotionUpdate? = when (m) {
        is ImuSample -> when (m.kind) {
            ImuKind.ACCEL -> { onAccel(m); null }
            ImuKind.GYRO -> { seenCalibratedGyro = true; onGyro(m.tNs, Vec3(m.x, m.y, m.z)) }
            ImuKind.GYRO_UNCAL -> if (seenCalibratedGyro) null else onGyro(m.tNs, Vec3(m.x, m.y, m.z))
            else -> null
        }
        is OrientationSample -> {
            if (m.kind == OrientationKind.GAME_ROTATION_VECTOR || m.kind == OrientationKind.ROTATION_VECTOR) {
                // Third row of the phone→world rotation matrix = world "up" expressed in phone frame.
                val w = m.qw; val x = m.qx; val y = m.qy; val z = m.qz
                orientUp = Vec3(2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y))
                orientT = m.tNs
            }
            null
        }
        is VehicleSpeedMeasurement -> {
            lastSpeed = m.speedMps
            lastSpeedT = m.tNs
            null
        }
        else -> null
    }

    private fun onAccel(m: ImuSample) {
        val a = Vec3(m.x, m.y, m.z)
        if (!gravityInit) {
            g = a; gravityInit = true
        } else {
            val dt = if (lastAccelT == Long.MIN_VALUE) 0.01 else ((m.tNs - lastAccelT) / 1e9).coerceIn(0.0, 0.5)
            g += (a - g) * (1 - exp(-dt / cfg.gravityTauS))
        }
        val dtA = if (lastAccelT == Long.MIN_VALUE) 0.01 else ((m.tNs - lastAccelT) / 1e9).coerceIn(0.0, 0.5)
        lastAccel = a
        lastAccelT = m.tNs
        accSinceEmit += a; accCountSinceEmit++
        cfg.upTauS?.let { tau -> correctUp(a, dtA, tau) }
        accWindow.addLast(m.tNs to a.norm)
        while (accWindow.isNotEmpty() && accWindow.first().first < m.tNs - cfg.windowNs) accWindow.removeFirst()
    }

    private fun correctUp(a: Vec3, dt: Double, tau: Double) {
        val lp = accelLp?.let { it + (a - it) * 0.1 } ?: a
        accelLp = lp
        val slow = accelLpSlow?.let { it + (a - it) * (dt / 1.0).coerceAtMost(1.0) } ?: a
        accelLpSlow = slow
        val mean = accelNormMean?.let { it + (a.norm - it) * (dt / cfg.upNormTauS) } ?: a.norm
        accelNormMean = mean
        val cur = gyroUp
        if (cur == null) { gyroUp = lp.unit(); return }
        if (lastGyroW == null) return
        // Gate on the smoothed yaw rate: the raw sample carries vibration (real moving ‖ω‖ p50 0.06–0.08 rad/s),
        // which kept the raw-sample gate shut almost all the time while driving (2026-10-02).
        // Also no correction while the car accelerates or brakes: 2 m/s² of braking changes ‖a‖ by only 0.2 m/s²
        // (passes the norm gate) but tilts the apparent gravity by 12°, which left a false +0.38 m/s² "bias" at the
        // next stop (2026-10-02).
        // A hand-held phone at a stop flickers in and out of the stop rule (aStd 0.08–0.3 m/s²): bridge short gaps.
        val stillS = if (stillLooseSinceNs == Long.MIN_VALUE || (lastAccelT - stillLooseLastNs) / 1e9 > 1.5) 0.0 else (lastAccelT - stillLooseSinceNs) / 1e9
        val horizontal = lp.perp(cur).norm
        // At rest there is no vehicle acceleration, so a horizontal component is the up error itself: always
        // correct then (otherwise an up tilted by > 3°, e.g. re-seeded while accelerating, never recovers).
        val quiet = abs(lp.norm - mean) < cfg.upGateAccel && abs(yawRateLp) < cfg.upGateYawRate &&
            (horizontal < cfg.upGateHorizontal || lastStillLoose || (cfg.upStillTauS != null && stillS >= cfg.upStillMinS && stopHint()))
        if (!quiet) return
        val f = lp.unit() ?: return
        val tauEff = cfg.upStillTauS?.takeIf { stillS >= cfg.upStillMinS && stopHint() && (lp - slow).norm < cfg.upStillMaxOnset } ?: tau
        gyroUp = (cur + (f - cur) * (dt / tauEff).coerceAtMost(1.0)).unit() ?: cur
    }

    private fun stopHint() = speedHintMps?.let { it < cfg.upStillMaxSpeedMps } == true

    /** Propagate the up vector with the gyro: a world-fixed vector rotates by −ω in the phone frame. */
    private fun propagateUp(w: Vec3, dt: Double) {
        val cur = gyroUp ?: return
        gyroUp = (cur - (w cross cur) * dt).unit() ?: cur
    }

    /** Current world-up in the phone frame. */
    fun up(tNs: Long): Vec3? {
        if (cfg.upTauS != null) gyroUp?.let { return it }
        val o = orientUp
        if (o != null && tNs - orientT <= cfg.orientationMaxAgeNs) return o
        return if (gravityInit) g.unit() else null
    }

    private fun onGyro(tNs: Long, w: Vec3): MotionUpdate? {
        gyroWindow.addLast(tNs to w.norm)
        while (gyroWindow.isNotEmpty() && gyroWindow.first().first < tNs - cfg.windowNs) gyroWindow.removeFirst()

        if (lastGyroT != Long.MIN_VALUE && cfg.upTauS != null) propagateUp(w, ((tNs - lastGyroT) / 1e9).coerceIn(0.0, 0.2))
        lastGyroW = w
        val up = up(tNs)
        if (up != null && lastGyroT != Long.MIN_VALUE) {
            val dtL = ((tNs - lastGyroT) / 1e9).coerceIn(0.0, 0.2)
            yawRateLp += ((w dot up) - yawRateLp) * (dtL / cfg.upGateYawTauS).coerceAtMost(1.0)
        }
        if (lastGyroT != Long.MIN_VALUE && up != null) {
            val dt = ((tNs - lastGyroT) / 1e9).coerceIn(0.0, 0.2)
            val wUp = w dot up
            yawAccum += wUp * dt
            yawAccumDt += dt
            learnMount(tNs, w, wUp, up, dt)
        }
        lastGyroT = tNs
        if (lastEmitT == Long.MIN_VALUE) lastEmitT = tNs
        if (tNs - lastEmitT < cfg.updatePeriodNs) return null
        return emit(tNs)
    }

    private fun learnMount(tNs: Long, w: Vec3, wUp: Double, up: Vec3, dt: Double) {
        // Shake / wobble metric: RMS of the non-yaw angular rate over 0.5 s.
        val perpRate = w.perp(up).norm
        tiltRateWindow.addLast(tNs to perpRate * perpRate)
        while (tiltRateWindow.isNotEmpty() && tiltRateWindow.first().first < tNs - 500_000_000L) tiltRateWindow.removeFirst()
        // Re-mount detection: net rotation about horizontal axes over a few seconds.
        val tilt = w.perp(up) * dt
        tiltWindow.addLast(tNs to tilt)
        tiltSum += tilt
        while (tiltWindow.isNotEmpty() && tiltWindow.first().first < tNs - cfg.remountWindowNs) {
            tiltSum -= tiltWindow.removeFirst().second
        }
        // One re-mount event per handling: ignore further tilt for one window after a detection.
        if (tiltSum.norm > cfg.remountTiltRad && (remountAt == Long.MIN_VALUE || tNs - remountAt > cfg.remountWindowNs)) {
            mountEpoch++
            remountAt = tNs
            leftSum = Vec3.ZERO; leftWeight = 0.0; leftCount = 0
            tiltWindow.clear(); tiltSum = Vec3.ZERO
            gravityInit = false // re-seed gravity from the next accelerometer sample
            gyroUp = null; accelLp = null
            return
        }
        // Forward-axis learning from centripetal acceleration in turns. Smoothed accel and yaw rate: single raw
        // samples carry 0.5–0.9 m/s² of vibration per axis, which kept the concentration at 0.15–0.3 on two of four
        // real drives (forward never learned; 2026-10-02).
        val a = (if (cfg.upTauS != null) accelLp else null) ?: lastAccel ?: return
        val wTurn = if (cfg.upTauS != null) yawRateLp else wUp
        if (tNs - lastAccelT > 50_000_000 || abs(wTurn) < cfg.mountMinYawRate) return
        val ah = a.perp(up)
        if (ah.norm < cfg.mountMinLateralAccel) return
        leftSum += ah * sign(wTurn)
        leftWeight += ah.norm
        leftCount++
    }

    /** Vehicle forward axis in the phone frame, or null until enough turns have been seen. */
    fun forward(tNs: Long): Vec3? {
        if (leftCount < cfg.mountMinSamples || leftWeight <= 0) return null
        if (leftSum.norm / leftWeight < cfg.mountMinConcentration) return null
        val up = up(tNs) ?: return null
        val left = leftSum.perp(up).unit() ?: return null
        return (left cross up).unit()
    }

    private fun emit(tNs: Long): MotionUpdate {
        val dtS = (tNs - lastEmitT) / 1e9
        val rate = if (yawAccumDt > 0) yawAccum / yawAccumDt else 0.0
        cumYawBearing -= rate * dtS
        yawAccum = 0.0; yawAccumDt = 0.0
        if (lastSpeedT != Long.MIN_VALUE && tNs - lastSpeedT <= cfg.odoMaxSpeedAgeNs) {
            val ds = lastSpeed * dtS
            odoE += ds * sin(cumYawBearing); odoN += ds * cos(cumYawBearing); odoDist += ds
        } else if (dtS > 0) {
            odoGaps++
        }
        lastEmitT = tNs

        val accStd = std(accWindow)
        val gyroMean = if (gyroWindow.isEmpty()) 0.0 else gyroWindow.sumOf { it.second } / gyroWindow.size
        val windowFull = accWindow.size >= 10 && gyroWindow.size >= 10
        val still = windowFull && accStd < cfg.stationaryAccelStd && gyroMean < cfg.stationaryGyroNorm
        if (still) {
            if (stationarySinceNs == null) stationarySinceNs = tNs
        } else {
            stationarySinceNs = null
        }
        appendHistory(tNs)
        val up = up(tNs)
        val ah = if (up != null) lastAccel?.perp(up)?.norm ?: 0.0 else 0.0
        val tiltRms = if (tiltRateWindow.isEmpty()) 0.0 else sqrt(tiltRateWindow.sumOf { it.second } / tiltRateWindow.size)
        // "Up robust to acceleration": our gyro-carried up, or a fresh Android rotation vector.
        val fromOrientation = (cfg.upTauS != null && gyroUp != null) || (orientUp != null && tNs - orientT <= cfg.orientationMaxAgeNs)
        val fwd = forward(tNs)
        val meanA = if (accCountSinceEmit > 0) accSinceEmit * (1.0 / accCountSinceEmit) else null
        accSinceEmit = Vec3.ZERO; accCountSinceEmit = 0
        val left = if (up != null && fwd != null) (up cross fwd).unit() else null
        val stillLoose = windowFull && accStd < cfg.stillLooseAccelStd && gyroMean < cfg.stillLooseGyroNorm
        if (stillLoose) {
            if (stillLooseSinceNs == Long.MIN_VALUE || (tNs - stillLooseLastNs) / 1e9 > 1.5) stillLooseSinceNs = tNs
            stillLooseLastNs = tNs
        }
        lastStillLoose = stillLoose
        val u = MotionUpdate(
            tNs, dtS, rate, still,
            stationarySinceNs?.let { (tNs - it) / 1e9 } ?: 0.0,
            gyroMean, accStd, up, fwd, mountEpoch, tiltRms, ah, fromOrientation,
            longitudinalAccel = if (meanA != null && fwd != null) meanA dot fwd else null,
            lateralAccel = if (meanA != null && left != null) meanA dot left else null,
            stillLoose = stillLoose,
        )
        latest = u
        return u
    }

    private fun std(w: ArrayDeque<Pair<Long, Double>>): Double {
        if (w.size < 2) return Double.MAX_VALUE
        val mean = w.sumOf { it.second } / w.size
        return sqrt(w.sumOf { (it.second - mean) * (it.second - mean) } / (w.size - 1))
    }

    private fun appendHistory(t: Long) {
        if (histEnd == histT.size) {
            // Compact or grow.
            val live = histEnd - histStart
            val cap = if (histStart > histT.size / 2) histT.size else histT.size * 2
            fun move(a: LongArray) = (if (cap == a.size) a else a.copyOf(cap)).also { System.arraycopy(a, histStart, it, 0, live) }
            fun move(a: DoubleArray) = (if (cap == a.size) a else a.copyOf(cap)).also { System.arraycopy(a, histStart, it, 0, live) }
            fun move(a: IntArray) = (if (cap == a.size) a else a.copyOf(cap)).also { System.arraycopy(a, histStart, it, 0, live) }
            histT = move(histT); histYaw = move(histYaw); histE = move(histE); histN = move(histN)
            histD = move(histD); histGap = move(histGap)
            histStart = 0; histEnd = live
        }
        histT[histEnd] = t; histYaw[histEnd] = cumYawBearing
        histE[histEnd] = odoE; histN[histEnd] = odoN; histD[histEnd] = odoDist; histGap[histEnd] = odoGaps
        histEnd++
        while (histStart < histEnd && histT[histStart] < t - cfg.historyNs) histStart++
    }

    /** History bracket for [tNs]: (lo, hi, fraction), or null if outside history. */
    private fun bracket(tNs: Long): Triple<Int, Int, Double>? {
        if (histEnd == histStart) return null
        if (tNs < histT[histStart] || tNs > histT[histEnd - 1] + cfg.updatePeriodNs * 4) return null
        var lo = histStart
        var hi = histEnd - 1
        if (tNs >= histT[hi]) return Triple(hi, hi, 0.0)
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (histT[mid] <= tNs) lo = mid else hi = mid
        }
        return Triple(lo, hi, (tNs - histT[lo]).toDouble() / (histT[hi] - histT[lo]).coerceAtLeast(1))
    }

    private fun DoubleArray.at(b: Triple<Int, Int, Double>) = this[b.first] + b.third * (this[b.second] - this[b.first])

    /** Cumulative bearing change (rad, clockwise positive) interpolated at [tNs], or null if outside history. */
    fun cumulativeBearingAt(tNs: Long): Double? = bracket(tNs)?.let { histYaw.at(it) }

    /**
     * Path travelled between [t1] and [t2] from vehicle speed and the gyro, or null when outside
     * history or when vehicle speed was missing for any part of the interval.
     */
    fun odometry(t1: Long, t2: Long): Odometry? {
        val a = bracket(t1) ?: return null
        val b = bracket(t2) ?: return null
        if (histGap[a.first] != histGap[b.second]) return null
        val dE = histE.at(b) - histE.at(a); val dN = histN.at(b) - histN.at(a)
        return Odometry(histD.at(b) - histD.at(a), hypot(dE, dN), dE, dN, histYaw.at(b))
    }

    /** Bearing change measured by the gyro between two times (rad, clockwise positive). */
    fun bearingChange(t1: Long, t2: Long): Double? {
        val a = cumulativeBearingAt(t1) ?: return null
        val b = cumulativeBearingAt(t2) ?: return null
        return b - a
    }

    fun isStationary(): Boolean = latest?.stationary == true
    fun stationaryForS(): Double = latest?.stationaryForS ?: 0.0

    fun snapshot(): State = State(
        g, gravityInit, orientUp, orientT, lastAccel, lastAccelT, lastGyroT, yawAccum, yawAccumDt,
        lastEmitT, cumYawBearing, stationarySinceNs, seenCalibratedGyro, accWindow.toList(), gyroWindow.toList(),
        leftSum, leftWeight, leftCount, tiltWindow.toList(), mountEpoch, remountAt, tiltRateWindow.toList(),
        lastSpeed, lastSpeedT, odoE, odoN, odoDist, odoGaps, gyroUp, accelLp, accelLpSlow, lastGyroW, accelNormMean, yawRateLp, accSinceEmit, accCountSinceEmit, lastStillLoose, stillLooseSinceNs, stillLooseLastNs, speedHintMps,
    )

    /** Restore state; history entries newer than the snapshot are discarded (they will be regenerated). */
    fun restore(s: State) {
        g = s.g; gravityInit = s.gravityInit
        orientUp = s.orientUp; orientT = s.orientT
        lastAccel = s.lastAccel; lastAccelT = s.lastAccelT
        lastGyroT = s.lastGyroT; yawAccum = s.yawAccum; yawAccumDt = s.yawAccumDt
        lastEmitT = s.lastEmitT; cumYawBearing = s.cumYawBearing
        stationarySinceNs = s.stationarySinceNs; seenCalibratedGyro = s.seenCalibratedGyro
        accWindow.clear(); accWindow.addAll(s.accWindow)
        gyroWindow.clear(); gyroWindow.addAll(s.gyroWindow)
        leftSum = s.leftSum; leftWeight = s.leftWeight; leftCount = s.leftCount
        tiltWindow.clear(); tiltWindow.addAll(s.tiltWindow)
        tiltSum = s.tiltWindow.fold(Vec3.ZERO) { acc, p -> acc + p.second }
        mountEpoch = s.mountEpoch; remountAt = s.remountAt
        tiltRateWindow.clear(); tiltRateWindow.addAll(s.tiltRateWindow)
        lastSpeed = s.lastSpeed; lastSpeedT = s.lastSpeedT
        odoE = s.odoE; odoN = s.odoN; odoDist = s.odoDist; odoGaps = s.odoGaps
        gyroUp = s.gyroUp; accelLp = s.accelLp; accelLpSlow = s.accelLpSlow; lastGyroW = s.lastGyroW; accelNormMean = s.accelNormMean; yawRateLp = s.yawRateLp
        accSinceEmit = s.accSinceEmit; accCountSinceEmit = s.accCountSinceEmit; lastStillLoose = s.lastStillLoose; stillLooseSinceNs = s.stillLooseSinceNs; stillLooseLastNs = s.stillLooseLastNs; speedHintMps = s.speedHintMps
        while (histEnd > histStart && histT[histEnd - 1] > s.lastEmitT) histEnd--
        latest = null
    }
}
