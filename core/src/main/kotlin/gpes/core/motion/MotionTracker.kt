package gpes.core.motion

import gpes.core.model.ImuKind
import gpes.core.model.ImuSample
import gpes.core.model.Measurement
import gpes.core.model.OrientationKind
import gpes.core.model.OrientationSample
import kotlin.math.exp
import kotlin.math.sqrt

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
)

data class MotionConfig(
    val updatePeriodNs: Long = 50_000_000,
    val windowNs: Long = 1_000_000_000,
    val gravityTauS: Double = 1.0,
    val stationaryAccelStd: Double = 0.12,
    val stationaryGyroNorm: Double = 0.03,
    val historyNs: Long = 600_000_000_000,
    /** An orientation sample is used for gravity only if it is this fresh. */
    val orientationMaxAgeNs: Long = 500_000_000,
)

/**
 * Turns raw IMU samples into [MotionUpdate]s. It also keeps a cumulative-yaw history, so that trust
 * checks can ask "how much did the car turn between t1 and t2" independently of GNSS.
 */
class MotionTracker(private val cfg: MotionConfig = MotionConfig()) {

    data class State(
        val gx: Double, val gy: Double, val gz: Double, val gravityInit: Boolean,
        val orientUp: DoubleArray?, val orientT: Long,
        val lastGyroT: Long, val yawAccum: Double, val yawAccumDt: Double,
        val lastEmitT: Long, val cumYawBearing: Double,
        val stationarySinceNs: Long?, val seenCalibratedGyro: Boolean,
        val accWindow: List<Pair<Long, Double>>, val gyroWindow: List<Pair<Long, Double>>,
    )

    // Gravity (specific force at rest points up) in the phone frame, low-passed accelerometer.
    private var gx = 0.0
    private var gy = 0.0
    private var gz = 0.0
    private var gravityInit = false
    private var orientUp: DoubleArray? = null
    private var orientT = Long.MIN_VALUE

    private var lastGyroT = Long.MIN_VALUE
    private var yawAccum = 0.0
    private var yawAccumDt = 0.0
    private var lastEmitT = Long.MIN_VALUE
    private var cumYawBearing = 0.0
    private var stationarySinceNs: Long? = null
    private var seenCalibratedGyro = false

    private val accWindow = ArrayDeque<Pair<Long, Double>>()
    private val gyroWindow = ArrayDeque<Pair<Long, Double>>()

    // Cumulative bearing change history: parallel arrays, appended at each update.
    private var histT = LongArray(1024)
    private var histYaw = DoubleArray(1024)
    private var histStart = 0
    private var histEnd = 0

    var latest: MotionUpdate? = null
        private set

    /** Feed a measurement. Returns a [MotionUpdate] when one is due. */
    fun onMeasurement(m: Measurement): MotionUpdate? = when (m) {
        is ImuSample -> when (m.kind) {
            ImuKind.ACCEL -> { onAccel(m); null }
            ImuKind.GYRO -> { seenCalibratedGyro = true; onGyro(m.tNs, m.x, m.y, m.z) }
            ImuKind.GYRO_UNCAL -> if (seenCalibratedGyro) null else onGyro(m.tNs, m.x, m.y, m.z)
            else -> null
        }
        is OrientationSample -> {
            if (m.kind == OrientationKind.GAME_ROTATION_VECTOR || m.kind == OrientationKind.ROTATION_VECTOR) {
                // Third row of the phone→world rotation matrix = world "up" expressed in phone frame.
                val (w, x, y, z) = listOf(m.qw, m.qx, m.qy, m.qz)
                orientUp = doubleArrayOf(2 * (x * z - w * y), 2 * (y * z + w * x), 1 - 2 * (x * x + y * y))
                orientT = m.tNs
            }
            null
        }
        else -> null
    }

    private fun onAccel(m: ImuSample) {
        if (!gravityInit) {
            gx = m.x; gy = m.y; gz = m.z; gravityInit = true
        } else {
            val dt = if (accWindow.isEmpty()) 0.01 else ((m.tNs - accWindow.last().first) / 1e9).coerceIn(0.0, 0.5)
            val a = 1 - exp(-dt / cfg.gravityTauS)
            gx += a * (m.x - gx); gy += a * (m.y - gy); gz += a * (m.z - gz)
        }
        accWindow.addLast(m.tNs to sqrt(m.x * m.x + m.y * m.y + m.z * m.z))
        while (accWindow.isNotEmpty() && accWindow.first().first < m.tNs - cfg.windowNs) accWindow.removeFirst()
    }

    private fun upUnit(tNs: Long): DoubleArray? {
        val o = orientUp
        if (o != null && tNs - orientT <= cfg.orientationMaxAgeNs) return o
        if (!gravityInit) return null
        val n = sqrt(gx * gx + gy * gy + gz * gz)
        if (n < 1e-3) return null
        return doubleArrayOf(gx / n, gy / n, gz / n)
    }

    private fun onGyro(tNs: Long, x: Double, y: Double, z: Double): MotionUpdate? {
        gyroWindow.addLast(tNs to sqrt(x * x + y * y + z * z))
        while (gyroWindow.isNotEmpty() && gyroWindow.first().first < tNs - cfg.windowNs) gyroWindow.removeFirst()

        val up = upUnit(tNs)
        if (lastGyroT != Long.MIN_VALUE && up != null) {
            val dt = ((tNs - lastGyroT) / 1e9).coerceIn(0.0, 0.2)
            val wUp = x * up[0] + y * up[1] + z * up[2]
            yawAccum += wUp * dt
            yawAccumDt += dt
        }
        lastGyroT = tNs
        if (lastEmitT == Long.MIN_VALUE) lastEmitT = tNs
        if (tNs - lastEmitT < cfg.updatePeriodNs) return null
        return emit(tNs)
    }

    private fun emit(tNs: Long): MotionUpdate {
        val dtS = (tNs - lastEmitT) / 1e9
        val rate = if (yawAccumDt > 0) yawAccum / yawAccumDt else 0.0
        cumYawBearing -= rate * dtS
        yawAccum = 0.0; yawAccumDt = 0.0
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
        appendHistory(tNs, cumYawBearing)
        val u = MotionUpdate(
            tNs, dtS, rate, still,
            stationarySinceNs?.let { (tNs - it) / 1e9 } ?: 0.0,
            gyroMean, accStd,
        )
        latest = u
        return u
    }

    private fun std(w: ArrayDeque<Pair<Long, Double>>): Double {
        if (w.size < 2) return Double.MAX_VALUE
        val mean = w.sumOf { it.second } / w.size
        return sqrt(w.sumOf { (it.second - mean) * (it.second - mean) } / (w.size - 1))
    }

    private fun appendHistory(t: Long, yaw: Double) {
        if (histEnd == histT.size) {
            // Compact or grow.
            val live = histEnd - histStart
            if (histStart > histT.size / 2) {
                System.arraycopy(histT, histStart, histT, 0, live)
                System.arraycopy(histYaw, histStart, histYaw, 0, live)
            } else {
                histT = histT.copyOf(histT.size * 2).also { System.arraycopy(histT, histStart, it, 0, live) }
                histYaw = histYaw.copyOf(histYaw.size * 2).also { System.arraycopy(histYaw, histStart, it, 0, live) }
            }
            histStart = 0; histEnd = live
        }
        histT[histEnd] = t; histYaw[histEnd] = yaw; histEnd++
        while (histStart < histEnd && histT[histStart] < t - cfg.historyNs) histStart++
    }

    /** Cumulative bearing change (rad, clockwise positive) interpolated at [tNs], or null if outside history. */
    fun cumulativeBearingAt(tNs: Long): Double? {
        if (histEnd == histStart) return null
        if (tNs < histT[histStart] || tNs > histT[histEnd - 1] + cfg.updatePeriodNs * 4) return null
        var lo = histStart
        var hi = histEnd - 1
        if (tNs >= histT[hi]) return histYaw[hi]
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (histT[mid] <= tNs) lo = mid else hi = mid
        }
        val f = (tNs - histT[lo]).toDouble() / (histT[hi] - histT[lo]).coerceAtLeast(1)
        return histYaw[lo] + f * (histYaw[hi] - histYaw[lo])
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
        gx, gy, gz, gravityInit, orientUp?.copyOf(), orientT, lastGyroT, yawAccum, yawAccumDt,
        lastEmitT, cumYawBearing, stationarySinceNs, seenCalibratedGyro, accWindow.toList(), gyroWindow.toList(),
    )

    /** Restore state; history entries newer than the snapshot are discarded (they will be regenerated). */
    fun restore(s: State) {
        gx = s.gx; gy = s.gy; gz = s.gz; gravityInit = s.gravityInit
        orientUp = s.orientUp?.copyOf(); orientT = s.orientT
        lastGyroT = s.lastGyroT; yawAccum = s.yawAccum; yawAccumDt = s.yawAccumDt
        lastEmitT = s.lastEmitT; cumYawBearing = s.cumYawBearing
        stationarySinceNs = s.stationarySinceNs; seenCalibratedGyro = s.seenCalibratedGyro
        accWindow.clear(); accWindow.addAll(s.accWindow)
        gyroWindow.clear(); gyroWindow.addAll(s.gyroWindow)
        while (histEnd > histStart && histT[histEnd - 1] > s.lastEmitT) histEnd--
        latest = null
    }
}
