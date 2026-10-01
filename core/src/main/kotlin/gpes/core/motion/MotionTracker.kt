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
)

/**
 * Dead-reckoned path between two times from vehicle speed (OBD) and the gyro bearing change.
 * [chordM] is the straight-line displacement: it does not depend on the (unknown) absolute heading,
 * so it constrains coarse fixes even when nothing knows which way the car points.
 */
data class Odometry(val distanceM: Double, val chordM: Double)

data class MotionConfig(
    val updatePeriodNs: Long = 50_000_000,
    val windowNs: Long = 1_000_000_000,
    /** Low-pass for gravity. Long enough that centripetal/braking acceleration barely tilts it (the phone is mounted). */
    val gravityTauS: Double = 5.0,
    val stationaryAccelStd: Double = 0.12,
    val stationaryGyroNorm: Double = 0.03,
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
    )

    // Gravity (specific force at rest points up) in the phone frame, low-passed accelerometer.
    private var g = Vec3.ZERO
    private var gravityInit = false
    private var orientUp: Vec3? = null
    private var orientT = Long.MIN_VALUE
    private var lastAccel: Vec3? = null
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
        lastAccel = a
        lastAccelT = m.tNs
        accWindow.addLast(m.tNs to a.norm)
        while (accWindow.isNotEmpty() && accWindow.first().first < m.tNs - cfg.windowNs) accWindow.removeFirst()
    }

    /** Current world-up in the phone frame. */
    fun up(tNs: Long): Vec3? {
        val o = orientUp
        if (o != null && tNs - orientT <= cfg.orientationMaxAgeNs) return o
        return if (gravityInit) g.unit() else null
    }

    private fun onGyro(tNs: Long, w: Vec3): MotionUpdate? {
        gyroWindow.addLast(tNs to w.norm)
        while (gyroWindow.isNotEmpty() && gyroWindow.first().first < tNs - cfg.windowNs) gyroWindow.removeFirst()

        val up = up(tNs)
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
            return
        }
        // Forward-axis learning from centripetal acceleration in turns.
        val a = lastAccel ?: return
        if (tNs - lastAccelT > 50_000_000 || abs(wUp) < cfg.mountMinYawRate) return
        val ah = a.perp(up)
        if (ah.norm < cfg.mountMinLateralAccel) return
        leftSum += ah * sign(wUp)
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
        val fromOrientation = orientUp != null && tNs - orientT <= cfg.orientationMaxAgeNs
        val u = MotionUpdate(
            tNs, dtS, rate, still,
            stationarySinceNs?.let { (tNs - it) / 1e9 } ?: 0.0,
            gyroMean, accStd, up, forward(tNs), mountEpoch, tiltRms, ah, fromOrientation,
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
        return Odometry(histD.at(b) - histD.at(a), hypot(histE.at(b) - histE.at(a), histN.at(b) - histN.at(a)))
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
        lastSpeed, lastSpeedT, odoE, odoN, odoDist, odoGaps,
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
        while (histEnd > histStart && histT[histEnd - 1] > s.lastEmitT) histEnd--
        latest = null
    }
}
