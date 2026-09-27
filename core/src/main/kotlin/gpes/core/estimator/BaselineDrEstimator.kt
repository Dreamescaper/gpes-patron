package gpes.core.estimator

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
 * travelled in an unknown direction, which is honest but not useful. That is the point: the
 * baseline shows what the phone alone can do.
 */
class BaselineDrEstimator(private val cfg: BaselineConfig = BaselineConfig()) : PositionEstimator {
    override val name = "baseline"

    private data class Snap(
        val initialized: Boolean, val frame: LocalFrame?, val x: DoubleArray, val p: DoubleArray,
        val headingKnown: Boolean, val lastT: Long, val lastYawRate: Double, val stationary: Boolean,
        val lastGnssT: Long, val dirlessDist: Double, val compass: Compass, val nextCompassT: Long,
        val odoM: Double, val lastNetT: Long, val lastNetOdo: Double,
    )

    private var initialized = false
    private var frame: LocalFrame? = null
    private val x = DoubleArray(N)
    private var p = Mat.diag(
        1.0, 1.0, PI * PI, cfg.unknownSpeedStd * cfg.unknownSpeedStd, cfg.initialBiasStd * cfg.initialBiasStd,
        cfg.speedScaleInitialStd * cfg.speedScaleInitialStd,
    )
    private var headingKnown = false
    private var lastT = Long.MIN_VALUE
    private var lastYawRate = 0.0
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

    // ------------------------------------------------------------------------------------------

    override fun onMotion(u: MotionUpdate) {
        propagateTo(u.tNs, lastYawRate)
        lastYawRate = u.yawRateUp
        if (stationary && !u.stationary) {
            // Pulling away: ZUPT pinned v to 0 with tiny variance, which is now meaningless.
            // Without a speed source the new speed is genuinely unknown.
            for (i in 0 until N) { p[IDX_V, i] = 0.0; p[i, IDX_V] = 0.0 }
            p[IDX_V, IDX_V] = cfg.unknownSpeedStd * cfg.unknownSpeedStd
            x[IDX_V] = cfg.pullAwaySpeedMps
        }
        stationary = u.stationary
        if (u.stationary) {
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
            return
        }
        if (sqrt(p[IDX_PSI, IDX_PSI]) < cfg.compass.usefulFraction * r.sigmaRad) return
        val innov = Geo.wrapRad(r.bearingRad - x[IDX_PSI])
        if (innov * innov / (p[IDX_PSI, IDX_PSI] + r2) > cfg.compass.gateNis) return
        update1(IDX_PSI, r.bearingRad, r2, angular = true)
    }

    override fun onMeasurement(m: Measurement, trust: TrustAssessment?) {
        when (m) {
            is LocationMeasurement -> onLocation(m, trust)
            is ImuSample -> compass.onMag(m)
            is GeomagneticReference -> compass.onReference(m)
            is PowerState -> compass.onPower(m)
            is VehicleSpeedMeasurement -> {
                propagateTo(m.tNs, lastYawRate)
                // Speedometer model: z = v·(1 + s). The scale error s (tyre wear, tyre size, OEM
                // over-reading) is learned while trusted GNSS speed is available, then kept during outages.
                val h = DoubleArray(N).also { it[IDX_V] = 1 + x[IDX_S]; it[IDX_S] = x[IDX_V] }
                updateH(h, m.speedMps - x[IDX_V] * (1 + x[IDX_S]), max(m.stdMps, 0.05).let { it * it })
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
        var sigma = acc / Cov2.R68_PER_SIGMA
        if (isCoarse) sigma *= cfg.networkInflation
        val r = sigma * sigma * rScale

        if (!initialized) {
            initAt(m.lat, m.lon, r)
            if (!isCoarse) applyGnssVelocity(m, rScale)
            if (!isCoarse) lastGnssT = m.tNs
            return
        }

        val z = frame!!.toEnu(m.lat, m.lon)
        if (isCoarse) {
            val posSigma = sqrt((p[0, 0] + p[1, 1]) / 2)
            val spaced = lastNetT == Long.MIN_VALUE ||
                ((m.tNs - lastNetT) / 1e9 >= cfg.networkMinIntervalS && odoM - lastNetOdo >= cfg.networkMinDistanceM)
            if (posSigma < cfg.networkUsefulFraction * sigma && !spaced) return
            lastNetT = m.tNs; lastNetOdo = odoM
            val nis = nis2(z.e, z.n, r)
            if (nis > cfg.networkResetNis) resetPosition(m.lat, m.lon, r) else updatePos(z.e, z.n, r)
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
        } else {
            update1(IDX_PSI, zPsi, bAcc * bAcc * rScale, angular = true)
        }
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
        val f = Mat.identity(N)
        val q = Mat(N, N)

        x[IDX_PSI] = Geo.wrapRad(psi - (yawRate - x[IDX_B]) * dt)
        f[IDX_PSI, IDX_B] = dt
        val turn = (yawRate - x[IDX_B]) * dt * cfg.gyroScaleError
        q[IDX_PSI, IDX_PSI] = cfg.headingRandomWalk * cfg.headingRandomWalk * dt + turn * turn
        q[IDX_V, IDX_V] = cfg.speedRandomWalk * cfg.speedRandomWalk * dt
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

        if (initialized && !headingKnown) {
            // Unknown direction: grow isotropically with a conservative distance bound.
            val ds = (abs(v) + 2 * sqrt(p[IDX_V, IDX_V])) * dt
            val d0 = dirlessDist
            dirlessDist += ds
            val dVar = (dirlessDist * dirlessDist - d0 * d0) / 2
            p[0, 0] += dVar; p[1, 1] += dVar
        }
        if (headingKnown && p[IDX_PSI, IDX_PSI] > PI * PI) headingKnown = false
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

    private fun updatePos(ze: Double, zn: Double, r: Double) {
        val h = Mat(2, N).also { it[0, 0] = 1.0; it[1, 1] = 1.0 }
        val s = h * p * h.t() + Mat.diag(r, r)
        val k = p * h.t() * s.inv()
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
            val cov = Cov2(p[0, 0], p[0, 1], p[1, 1])
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
            )
        } finally {
            restore(saved)
        }
    }

    override fun snapshot(): Any = Snap(
        initialized, frame, x.copyOf(), p.a.copyOf(), headingKnown, lastT, lastYawRate, stationary, lastGnssT, dirlessDist,
        compass.copy(), nextCompassT, odoM, lastNetT, lastNetOdo,
    )

    override fun restore(snapshot: Any) {
        val s = snapshot as Snap
        initialized = s.initialized; frame = s.frame
        s.x.copyInto(x); p = Mat(N, N, s.p.copyOf())
        headingKnown = s.headingKnown; lastT = s.lastT; lastYawRate = s.lastYawRate
        stationary = s.stationary; lastGnssT = s.lastGnssT; dirlessDist = s.dirlessDist
        compass = s.compass.copy(); nextCompassT = s.nextCompassT
        odoM = s.odoM; lastNetT = s.lastNetT; lastNetOdo = s.lastNetOdo
    }

    private companion object {
        const val N = 6
        const val IDX_PSI = 2
        const val IDX_V = 3
        const val IDX_B = 4
        const val IDX_S = 5
    }
}
