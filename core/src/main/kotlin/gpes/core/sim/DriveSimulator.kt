package gpes.core.sim

import gpes.core.geo.LocalFrame
import gpes.core.model.Cov2
import gpes.core.model.DriveRecord
import gpes.core.model.GeomagneticReference
import gpes.core.model.GnssStatusSnapshot
import gpes.core.model.ImuKind
import gpes.core.model.ImuSample
import gpes.core.model.LocSource
import gpes.core.model.LocationMeasurement
import gpes.core.model.OrientationKind
import gpes.core.model.OrientationSample
import gpes.core.model.RecordOrder
import gpes.core.model.SatInfo
import gpes.core.model.SessionInfo
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.random.Random

/** One leg of a synthetic drive. */
@Serializable
sealed interface Leg {
    /** Drive straight at [speedMps] for [durationS]. Speed changes are rate-limited. */
    @Serializable
    data class Straight(val durationS: Double, val speedMps: Double) : Leg

    /** Turn by [angleDeg] (positive = right / clockwise) at [speedMps]. */
    @Serializable
    data class Turn(val angleDeg: Double, val speedMps: Double, val rateDegPerS: Double = 15.0) : Leg

    @Serializable
    data class Stop(val durationS: Double) : Leg
}

@Serializable
data class SimConfig(
    val startLat: Double = 50.4501,
    val startLon: Double = 30.5234,
    val startBearingDeg: Double = 90.0,
    val legs: List<Leg> = defaultCityLoop(),
    val imuHz: Double = 100.0,
    val gnssHz: Double = 1.0,
    val gnssSigmaM: Double = 3.0,
    val gnssSpeedSigma: Double = 0.2,
    val gnssBearingSigmaDeg: Double = 2.0,
    /** Network fixes every N seconds (0 = none), with this one-sigma error. */
    val networkPeriodS: Double = 0.0,
    val networkSigmaM: Double = 500.0,
    val gyroNoise: Double = 0.003,
    val gyroBiasZ: Double = 0.004,
    /** Gyro scale-factor error (fraction) and bias random walk (rad/s per √s): real MEMS gyros are not ideal. */
    val gyroScaleError: Double = 0.0,
    val gyroBiasWalk: Double = 0.0,
    val accelNoise: Double = 0.05,
    /** Engine / road vibration added to the accelerometer while moving (m/s² one-sigma). */
    val vibration: Double = 0.3,
    val maxAccel: Double = 2.0,
    /** Phone mounting (degrees): roll, pitch, yaw relative to the vehicle frame (x right, y forward, z up). */
    val mountRollDeg: Double = 10.0,
    val mountPitchDeg: Double = -60.0,
    val mountYawDeg: Double = 15.0,
    /** Earth field (Kyiv-like) and in-car distortions. */
    val magFieldUt: Double = 50.0,
    val magInclinationDeg: Double = 66.0,
    val magDeclinationDeg: Double = 8.5,
    /** Car hard iron (vehicle frame, µT) and soft iron (diagonal scale + xy coupling). */
    val carHardIronUt: List<Double> = listOf(12.0, -8.0, 5.0),
    val carSoftIronDiag: List<Double> = listOf(1.08, 0.94, 1.0),
    val carSoftIronXy: Double = 0.05,
    /** Phone hard iron (phone frame, µT). Android's calibrated MAG removes it; MAG_UNCAL includes it. */
    val phoneHardIronUt: List<Double> = listOf(6.0, -4.0, 10.0),
    val magNoiseUt: Double = 0.4,
    /** Transient disturbances (trams, trucks, bridges): mean interval (s), duration (s), strength (µT). 0 = none. */
    val magAnomalyEveryS: Double = 90.0,
    val magAnomalyDurationS: Double = 5.0,
    val magAnomalyUt: Double = 20.0,
    /** Emit GAME_ROTATION_VECTOR (gyro-stabilized attitude, as on real phones) with slowly wandering tilt error (deg). */
    val emitGameRotationVector: Boolean = true,
    val grvTiltErrorDeg: Double = 0.5,
    /** Emit a GeomagneticReference (declination, field) at start, like the app does from WMM. */
    val emitGeomagReference: Boolean = true,
    val seed: Long = 1,
    val startElapsedNs: Long = 1_000_000_000_000,
) {
    companion object {
        fun defaultCityLoop(): List<Leg> = listOf(
            Leg.Stop(10.0),
            Leg.Straight(60.0, 14.0),
            Leg.Turn(90.0, 8.0),
            Leg.Straight(45.0, 12.0),
            Leg.Stop(20.0),
            Leg.Straight(40.0, 13.0),
            Leg.Turn(-90.0, 8.0),
            Leg.Straight(90.0, 15.0),
            Leg.Turn(90.0, 8.0),
            Leg.Straight(30.0, 10.0),
            Leg.Turn(90.0, 8.0),
            Leg.Straight(120.0, 16.0),
            Leg.Stop(15.0),
            Leg.Straight(60.0, 12.0),
            Leg.Turn(-45.0, 10.0),
            Leg.Straight(60.0, 14.0),
            Leg.Stop(10.0),
        )
    }
}

@Serializable
data class TruthSample(val tNs: Long, val lat: Double, val lon: Double, val bearingDeg: Double, val speedMps: Double)

data class SimDrive(val records: List<DriveRecord>, val truth: List<TruthSample>)

/**
 * Generates a physically consistent synthetic drive. A truth trajectory is turned into phone-frame
 * IMU samples (with gravity, vehicle acceleration, centripetal force, gyro bias, noise and a tilted
 * phone mount), plus noisy GNSS and optional network fixes. It is used by tests and by
 * `replay-cli simulate`, so the full replay flow runs without a device.
 */
object DriveSimulator {
    private const val G = 9.80665

    fun generate(cfg: SimConfig = SimConfig()): SimDrive {
        val rnd = Random(cfg.seed)
        val frame = LocalFrame(cfg.startLat, cfg.startLon)
        val dt = 1.0 / cfg.imuHz
        val dtNs = (1e9 / cfg.imuHz).toLong()
        val rot = mountRotation(cfg) // vehicle → phone: v_phone = rot * v_vehicle

        val records = ArrayList<DriveRecord>()
        val truth = ArrayList<TruthSample>()
        records += SessionInfo(cfg.startElapsedNs, "sim-${cfg.seed}", cfg.startElapsedNs, 1_700_000_000_000L, device = "simulator", mode = "SIM")

        var t = cfg.startElapsedNs
        var e = 0.0
        var n = 0.0
        var psi = Math.toRadians(cfg.startBearingDeg)
        var v = 0.0
        var nextGnss = t
        var nextNet = if (cfg.networkPeriodS > 0) t else Long.MAX_VALUE
        if (cfg.emitGeomagReference) {
            records += GeomagneticReference(t, cfg.startLat, cfg.startLon, cfg.magDeclinationDeg, cfg.magInclinationDeg, cfg.magFieldUt, "sim")
        }
        val inc = Math.toRadians(cfg.magInclinationDeg)
        val dec = Math.toRadians(cfg.magDeclinationDeg)
        val bEnu = doubleArrayOf(cfg.magFieldUt * cos(inc) * sin(dec), cfg.magFieldUt * cos(inc) * cos(dec), -cfg.magFieldUt * sin(inc))
        var stepIdx = 0L
        var anomalyUntil = Long.MIN_VALUE
        var anomaly = doubleArrayOf(0.0, 0.0, 0.0)
        var biasZ = cfg.gyroBiasZ
        var tiltErrX = 0.0
        var tiltErrY = 0.0
        val rotT = Array(3) { i -> DoubleArray(3) { j -> rot[j][i] } } // phone → vehicle
        val gnssPeriod = (1e9 / cfg.gnssHz).toLong()

        fun step(targetSpeed: Double, bearingRate: Double) {
            val dv = (targetSpeed - v).let { sign(it) * min(abs(it), cfg.maxAccel * dt) }
            val aLong = dv / dt
            v += dv
            psi += bearingRate * dt
            e += v * sin(psi) * dt
            n += v * cos(psi) * dt
            t += dtNs

            // Vehicle frame: x right, y forward, z up. Specific force = accel + gravity reaction.
            // A right turn (bearing increasing) has centripetal acceleration toward +x (right).
            val moving = v > 0.1
            val vib = if (moving) cfg.vibration else 0.0
            val fVeh = doubleArrayOf(
                v * bearingRate + rnd.gauss() * vib,
                aLong + rnd.gauss() * vib,
                G + rnd.gauss() * vib,
            )
            // Yaw rate about up (CCW positive) = −bearing rate.
            val wVeh = doubleArrayOf(0.0, 0.0, -bearingRate)
            val fPh = mul(rot, fVeh)
            biasZ += rnd.gauss() * cfg.gyroBiasWalk * kotlin.math.sqrt(dt)
            val wPh = mul(rot, wVeh).let { w -> DoubleArray(3) { w[it] * (1 + cfg.gyroScaleError) } }
            val biasPh = mul(rot, doubleArrayOf(0.0, 0.0, biasZ))
            records += ImuSample(t, ImuKind.ACCEL, fPh[0] + rnd.gauss() * cfg.accelNoise, fPh[1] + rnd.gauss() * cfg.accelNoise, fPh[2] + rnd.gauss() * cfg.accelNoise)
            records += ImuSample(t, ImuKind.GYRO, wPh[0] + biasPh[0] + rnd.gauss() * cfg.gyroNoise, wPh[1] + biasPh[1] + rnd.gauss() * cfg.gyroNoise, wPh[2] + biasPh[2] + rnd.gauss() * cfg.gyroNoise)

            if (stepIdx++ % 2 == 0L) {
                // World field (+ anomaly, world frame) → vehicle frame → car soft/hard iron → phone frame + phone hard iron.
                if (cfg.magAnomalyEveryS > 0 && t > anomalyUntil && rnd.nextDouble() < dt * 2 / cfg.magAnomalyEveryS) {
                    anomalyUntil = t + (cfg.magAnomalyDurationS * 1e9).toLong()
                    anomaly = doubleArrayOf(rnd.gauss(), rnd.gauss(), rnd.gauss()).let { a ->
                        val k = cfg.magAnomalyUt / kotlin.math.sqrt(a.sumOf { it * it }); DoubleArray(3) { a[it] * k }
                    }
                }
                val w = if (t <= anomalyUntil) DoubleArray(3) { bEnu[it] + anomaly[it] } else bEnu
                val vx = w[0] * cos(psi) - w[1] * sin(psi)
                val vy = w[0] * sin(psi) + w[1] * cos(psi)
                val d = cfg.carSoftIronDiag
                val bv = doubleArrayOf(
                    d[0] * vx + cfg.carSoftIronXy * vy + cfg.carHardIronUt[0],
                    cfg.carSoftIronXy * vx + d[1] * vy + cfg.carHardIronUt[1],
                    d[2] * w[2] + cfg.carHardIronUt[2],
                )
                val bp = mul(rot, bv)
                val h = cfg.phoneHardIronUt
                val nz = DoubleArray(3) { rnd.gauss() * cfg.magNoiseUt }
                records += ImuSample(t, ImuKind.MAG_UNCAL, bp[0] + h[0] + nz[0], bp[1] + h[1] + nz[1], bp[2] + h[2] + nz[2], h[0], h[1], h[2], 3)
                records += ImuSample(t, ImuKind.MAG, bp[0] + nz[0], bp[1] + nz[1], bp[2] + nz[2], accuracy = 3)

                if (cfg.emitGameRotationVector) {
                    // Phone → world = (vehicle → world) · (phone → vehicle), with a slowly wandering tilt error.
                    val k = Math.toRadians(cfg.grvTiltErrorDeg)
                    tiltErrX += (-tiltErrX / 30.0) * 0.02 + rnd.gauss() * k * kotlin.math.sqrt(2 * 0.02 / 30.0)
                    tiltErrY += (-tiltErrY / 30.0) * 0.02 + rnd.gauss() * k * kotlin.math.sqrt(2 * 0.02 / 30.0)
                    val rvw = arrayOf(doubleArrayOf(cos(psi), sin(psi), 0.0), doubleArrayOf(-sin(psi), cos(psi), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
                    val tilt = mm(
                        arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(tiltErrX), -sin(tiltErrX)), doubleArrayOf(0.0, sin(tiltErrX), cos(tiltErrX))),
                        arrayOf(doubleArrayOf(cos(tiltErrY), 0.0, sin(tiltErrY)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(tiltErrY), 0.0, cos(tiltErrY))),
                    )
                    val q = quat(mm(tilt, mm(rvw, rotT)))
                    records += OrientationSample(t, OrientationKind.GAME_ROTATION_VECTOR, q[0], q[1], q[2], q[3])
                }
            }

            val ll = frame.toLatLon(e, n)
            val bearingDeg = (Math.toDegrees(psi) % 360 + 360) % 360
            if (truth.isEmpty() || t - truth.last().tNs >= 100_000_000) truth += TruthSample(t, ll.lat, ll.lon, bearingDeg, v)

            if (t >= nextGnss) {
                nextGnss += gnssPeriod
                val g = frame.toLatLon(e + rnd.gauss() * cfg.gnssSigmaM, n + rnd.gauss() * cfg.gnssSigmaM)
                val acc = cfg.gnssSigmaM * Cov2.R68_PER_SIGMA
                records += GnssStatusSnapshot(t, List(9) { i ->
                    SatInfo(svid = i + 1, constellation = 1, cn0DbHz = 30 + 12 * rnd.nextDouble(), elevDeg = 10 + 70 * rnd.nextDouble(), azDeg = 40.0 * i, usedInFix = true)
                })
                records += LocationMeasurement(
                    tNs = t, receivedNs = t + 150_000_000, source = LocSource.GNSS, provider = "gps",
                    lat = g.lat, lon = g.lon, hAccM = acc,
                    speedMps = (v + rnd.gauss() * cfg.gnssSpeedSigma).coerceAtLeast(0.0), speedAccMps = cfg.gnssSpeedSigma * 2,
                    bearingDeg = if (v > 1) (bearingDeg + rnd.gauss() * cfg.gnssBearingSigmaDeg + 360) % 360 else null,
                    bearingAccDeg = if (v > 1) cfg.gnssBearingSigmaDeg * 2 else null,
                    satsUsed = 9,
                )
            }
            if (t >= nextNet) {
                nextNet += (cfg.networkPeriodS * 1e9).toLong()
                val g = frame.toLatLon(e + rnd.gauss() * cfg.networkSigmaM, n + rnd.gauss() * cfg.networkSigmaM)
                records += LocationMeasurement(
                    tNs = t, receivedNs = t + 500_000_000, source = LocSource.NETWORK, provider = "network",
                    lat = g.lat, lon = g.lon, hAccM = cfg.networkSigmaM * Cov2.R68_PER_SIGMA,
                )
            }
        }

        for (leg in cfg.legs) when (leg) {
            is Leg.Straight -> repeat((leg.durationS * cfg.imuHz).toInt()) { step(leg.speedMps, 0.0) }
            is Leg.Stop -> repeat((leg.durationS * cfg.imuHz).toInt()) { step(0.0, 0.0) }
            is Leg.Turn -> {
                val rate = Math.toRadians(leg.rateDegPerS) * sign(leg.angleDeg)
                val steps = (abs(leg.angleDeg) / leg.rateDegPerS * cfg.imuHz).toInt()
                repeat(steps) { step(leg.speedMps, rate) }
            }
        }
        records.sortWith(RecordOrder.comparator)
        return SimDrive(records, truth)
    }

    private fun Random.gauss(): Double {
        // Box–Muller
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2 * kotlin.math.ln(u1)) * cos(2 * PI * u2)
    }

    /** Vehicle→phone rotation used by the simulator (v_phone = R · v_vehicle). */
    fun mountRotation(cfg: SimConfig): Array<DoubleArray> {
        val r = Math.toRadians(cfg.mountRollDeg)
        val p = Math.toRadians(cfg.mountPitchDeg)
        val y = Math.toRadians(cfg.mountYawDeg)
        val rx = arrayOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, cos(p), -sin(p)), doubleArrayOf(0.0, sin(p), cos(p)))
        val ry = arrayOf(doubleArrayOf(cos(r), 0.0, sin(r)), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-sin(r), 0.0, cos(r)))
        val rz = arrayOf(doubleArrayOf(cos(y), -sin(y), 0.0), doubleArrayOf(sin(y), cos(y), 0.0), doubleArrayOf(0.0, 0.0, 1.0))
        return mm(rx, mm(ry, rz))
    }

    /** Rotation matrix → unit quaternion (w, x, y, z). */
    private fun quat(m: Array<DoubleArray>): DoubleArray {
        val tr = m[0][0] + m[1][1] + m[2][2]
        return if (tr > 0) {
            val s = kotlin.math.sqrt(tr + 1.0) * 2
            doubleArrayOf(0.25 * s, (m[2][1] - m[1][2]) / s, (m[0][2] - m[2][0]) / s, (m[1][0] - m[0][1]) / s)
        } else if (m[0][0] > m[1][1] && m[0][0] > m[2][2]) {
            val s = kotlin.math.sqrt(1.0 + m[0][0] - m[1][1] - m[2][2]) * 2
            doubleArrayOf((m[2][1] - m[1][2]) / s, 0.25 * s, (m[0][1] + m[1][0]) / s, (m[0][2] + m[2][0]) / s)
        } else if (m[1][1] > m[2][2]) {
            val s = kotlin.math.sqrt(1.0 + m[1][1] - m[0][0] - m[2][2]) * 2
            doubleArrayOf((m[0][2] - m[2][0]) / s, (m[0][1] + m[1][0]) / s, 0.25 * s, (m[1][2] + m[2][1]) / s)
        } else {
            val s = kotlin.math.sqrt(1.0 + m[2][2] - m[0][0] - m[1][1]) * 2
            doubleArrayOf((m[1][0] - m[0][1]) / s, (m[0][2] + m[2][0]) / s, (m[1][2] + m[2][1]) / s, 0.25 * s)
        }
    }

    private fun mm(a: Array<DoubleArray>, b: Array<DoubleArray>) =
        Array(3) { i -> DoubleArray(3) { j -> (0 until 3).sumOf { k -> a[i][k] * b[k][j] } } }

    private fun mul(m: Array<DoubleArray>, v: DoubleArray) = DoubleArray(3) { i -> (0 until 3).sumOf { m[i][it] * v[it] } }
}
