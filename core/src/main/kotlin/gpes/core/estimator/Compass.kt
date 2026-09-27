package gpes.core.estimator

import gpes.core.geo.Geo
import gpes.core.model.GeomagneticReference
import gpes.core.model.ImuKind
import gpes.core.model.ImuSample
import gpes.core.model.PowerState
import gpes.core.motion.MotionUpdate
import gpes.core.motion.Vec3
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class CompassConfig(
    val enabled: Boolean = true,
    /** Magnetometer smoothing (s). */
    val tauS: Double = 0.2,
    /** Reject when the corrected horizontal field radius deviates from the fitted radius by more than this fraction. */
    val radiusTolerance: Double = 0.1,
    /**
     * With a fixed mount the field component along "up" is constant whatever the heading (hard iron
     * is constant too). Local anomalies (trams, bridges, trucks) almost always change it. Reject when
     * it deviates from its running median by more than this (µT), and for [anomalyHoldoffS] afterwards.
     */
    val verticalToleranceUt: Double = 3.0,
    val anomalyHoldoffS: Double = 3.0,
    /** Uncorrected (Android MAG) path: reject when |B| deviates from the expected field by more than this fraction. */
    val magnitudeTolerance: Double = 0.3,
    /** Reject when the compass turned differently from the gyro by more than this over [gyroCheckS]. */
    val gyroCheckDeg: Double = 10.0,
    val gyroCheckS: Double = 2.0,
    /** Heading coverage (gyro-measured, 45° octants) needed for the iron fits. */
    val circleMinOctants: Int = 4,
    val ellipseMinOctants: Int = 6,
    /** GNSS alignment samples: speed ≥ this, and |yaw rate| ≤ [alignMaxYawRate] (course lags in turns). */
    val alignMinSpeedMps: Double = 7.0,
    val alignMaxYawRate: Double = 0.05,
    val alignMinSamples: Int = 10,
    /** Floor of the GNSS-aligned compass sigma (degrees). Compass errors are time-correlated; stay humble. */
    val minSigmaDeg: Double = 5.0,
    /** Extra sigma while only a circle (hard iron) is fitted: soft iron is unmodelled (degrees). */
    val circleOnlySigmaDeg: Double = 5.0,
    /** Extra sigma when the current heading is > 45° from every GNSS alignment sample (degrees). */
    val extrapolationSigmaDeg: Double = 5.0,
    /** Iron-corrected but aligned only via forward axis + declination (degrees). */
    val forwardAlignedSigmaDeg: Double = 15.0,
    /** No iron correction: Android-calibrated MAG + forward axis + declination (degrees). */
    val uncorrectedSigmaDeg: Double = 35.0,
    /** Extra sigma when no declination is known (degrees). */
    val noDeclinationSigmaDeg: Double = 10.0,
    /**
     * Update the EKF heading only if its sigma exceeds this fraction of the compass sigma. Compass
     * errors are strongly time-correlated (the same residual deviation for minutes), so repeated
     * readings must not be averaged down: with 1.0 the compass alone never pushes heading sigma much
     * below its own sigma.
     */
    val usefulFraction: Double = 1.0,
    /** Extra sigma for poorly conditioned iron fits: 4 octants covered / 5 octants (degrees). */
    val coverage4SigmaDeg: Double = 25.0,
    val coverage5SigmaDeg: Double = 10.0,
    val gateNis: Double = 9.0,
    val periodS: Double = 1.0,
    /**
     * Shake gate: RMS angular rate about horizontal axes (rad/s) above which the phone is "shaking".
     * With a gyro-stabilized rotation vector the up-vector follows the shaking, so only strong wobble
     * hurts (sim at 3 Hz: 3° ≈ 0.7 rad/s RMS harmless, 8° ≈ 1.9 rad/s doubles p95). With accelerometer-only up, even moderate shaking
     * leaks the vertical field into the heading.
     */
    val maxTiltRateRms: Double = 0.15,
    val maxTiltRateRmsWithOrientation: Double = 1.2,
    /** Shake gate when "up" comes from the accelerometer (no rotation vector): horizontal accel (m/s²) that tilts it. */
    val maxHorizontalAccel: Double = 1.0,
    /** Additional sigma when the quality verdict is MARGINAL (degrees). */
    val marginalSigmaDeg: Double = 10.0,
    /** Minimum moving time before time-fraction quality checks apply (s). */
    val qualityMinMovingS: Double = 60.0,
)

enum class CompassVerdict { UNKNOWN, USABLE, MARGINAL, UNUSABLE }

/**
 * Self-assessment of the magnetometer for this mount, for the UI, logs and the offline report.
 * [reasons] are stable codes (not localized); see docs/estimation-algorithm.md §3b.
 */
@Serializable
data class CompassQuality(
    val verdict: CompassVerdict,
    val reasons: Set<String>,
    val fit: String,
    val octants: Int,
    /** Fitted horizontal-field radius / expected (WMM F·cos I). ≈ 1 for a clean mount. */
    val radiusRatio: Double?,
    /** Radial scatter of the binned field around the fit, as an angle (degrees). */
    val scatterDeg: Double?,
    /** Largest movement of the fitted centre in the last 5 min, relative to the radius. */
    val centerDriftRatio: Double?,
    /** Horizontal hard-iron offset (µT): the fitted centre's distance from zero. A holder magnet shows up here. */
    val hardIronUt: Double?,
    val dirtyFraction: Double?,
    val shakyFraction: Double?,
    val tiltRateRmsMean: Double?,
    val alignRmsDeg: Double?,
    val saturated: Boolean,
    val wirelessCharging: Boolean,
    val mountEpoch: Int,
)

enum class CompassMode { GNSS_ALIGNED, FORWARD_ALIGNED, UNCORRECTED }

data class CompassReading(val bearingRad: Double, val sigmaRad: Double, val mode: CompassMode)

/**
 * Compass heading for a phone **fixed in a car**, where the magnetometer is badly distorted by the
 * body, engine and wiring (and sometimes by a magnetic phone holder).
 *
 * With a fixed mount, the distortions are constant in the phone frame. As the car turns, the
 * horizontal field measured in a phone-fixed horizontal frame traces an **ellipse**: soft iron
 * gives the shape, hard iron the offset. This is exact for a constant tilt.
 *
 * 1. **Iron fit, no GNSS needed.** Horizontal field points are binned by the *gyro* heading (so
 *    coverage is known independently of the distorted compass). A circle (offset only) is fitted
 *    with ≥ 3 octants covered, and a full ellipse with ≥ 6. The corrected angle θc then rotates 1:1
 *    with the vehicle.
 * 2. **Alignment c in ψ = θc + c:**
 *    - GNSS_ALIGNED: circular mean of (trusted GNSS course − θc) while driving straight. This
 *      absorbs mount yaw, declination and the residual soft-iron rotation. σ comes from the
 *      residuals (≥ 5°).
 *    - FORWARD_ALIGNED: no GNSS; the azimuth of the learned vehicle forward axis w.r.t. the
 *      corrected field, plus WMM declination. σ = 15°.
 * 3. UNCORRECTED: no iron fit yet; Android-calibrated MAG + forward axis + declination. σ = 35°.
 *
 * Gates: corrected radius (or |B|) near its expected value, and compass heading changes must agree
 * with the gyro (this rejects trams, bridges and trucks). A re-mount (new mount epoch) resets
 * everything.
 */
class Compass(private val cfg: CompassConfig = CompassConfig()) {

    private data class Stream(var ema: Vec3? = null, var t: Long = Long.MIN_VALUE, var vMed: Double = 0.0, var vN: Int = 0)

    /** Iron model in the (r1, r2) horizontal frame: q = W · (p − center), W symmetric (identity for a circle). */
    private data class IronFit(val cx: Double, val cy: Double, val w11: Double, val w12: Double, val w22: Double, val radius: Double, val ellipse: Boolean)

    private var raw = Stream()      // MAG_UNCAL: stable, preferred for iron fitting
    private var cal = Stream()      // MAG: Android hard-iron removed
    private var reference: GeomagneticReference? = null
    private var up: Vec3? = null
    private var forward: Vec3? = null
    private var epoch = -1
    private var r1: Vec3? = null
    private var cumGyroBearing = 0.0
    private val hist = ArrayDeque<Triple<Long, Double, Double>>() // (t, candidate heading, gyro bearing)

    // Horizontal field points binned by gyro heading.
    private val binX = DoubleArray(BINS)
    private val binY = DoubleArray(BINS)
    private val binN = DoubleArray(BINS)
    private var iron: IronFit? = null
    private var ironDirty = false

    // GNSS alignment pairs: (raw horizontal point x, y, true course), ring buffer.
    private val align = ArrayDeque<Triple<Double, Double, Double>>()
    private var lastDirtyT = Long.MIN_VALUE

    // Quality bookkeeping.
    private var shaky = false
    private var movingN = 0
    private var dirtyN = 0
    private var shakyN = 0
    private var tiltSum = 0.0
    private var saturated = false
    private val satRun = IntArray(3)
    private val satLast = DoubleArray(3)
    private var wirelessCharging = false
    private val centers = ArrayDeque<Triple<Long, Double, Double>>()
    private var lastAlignRms: Double? = null
    private var lastT = 0L
    private var qualityCache: CompassQuality? = null

    fun copy(): Compass = Compass(cfg).also { c ->
        c.raw = raw.copy(); c.cal = cal.copy(); c.reference = reference; c.up = up; c.forward = forward
        c.epoch = epoch; c.r1 = r1; c.cumGyroBearing = cumGyroBearing; c.hist.addAll(hist)
        binX.copyInto(c.binX); binY.copyInto(c.binY); binN.copyInto(c.binN)
        c.iron = iron; c.ironDirty = ironDirty; c.align.addAll(align); c.lastDirtyT = lastDirtyT; c.fitStreamRaw = fitStreamRaw
        c.shaky = shaky; c.movingN = movingN; c.dirtyN = dirtyN; c.shakyN = shakyN; c.tiltSum = tiltSum
        c.saturated = saturated; satRun.copyInto(c.satRun); satLast.copyInto(c.satLast); c.wirelessCharging = wirelessCharging
        c.centers.addAll(centers); c.lastAlignRms = lastAlignRms; c.lastT = lastT; c.qualityCache = qualityCache
    }

    val alignmentSamples: Int get() = align.size

    fun onReference(r: GeomagneticReference) { reference = r }

    fun onPower(p: PowerState) {
        if (p.wireless && p.charging) wirelessCharging = true
    }

    fun onMag(m: ImuSample) {
        val s = when (m.kind) {
            ImuKind.MAG_UNCAL -> raw
            ImuKind.MAG -> cal
            else -> return
        }
        val v = Vec3(m.x, m.y, m.z)
        if (s === raw) detectSaturation(v)
        val prev = s.ema
        s.ema = if (prev == null) v else prev + (v - prev) * (1 - exp(-((m.tNs - s.t) / 1e9).coerceIn(0.0, 1.0) / cfg.tauS))
        s.t = m.tNs
    }

    /** A clipped axis repeats exactly the same large value; real noise never does. */
    private fun detectSaturation(v: Vec3) {
        val a = doubleArrayOf(v.x, v.y, v.z)
        for (i in 0..2) {
            satRun[i] = if (abs(a[i]) > 200.0 && a[i] == satLast[i]) satRun[i] + 1 else 0
            satLast[i] = a[i]
            if (satRun[i] >= 20) saturated = true
        }
    }

    private fun fresh(s: Stream, tNs: Long) = s.ema != null && tNs - s.t <= 300_000_000L

    /** The stream used for iron fitting: raw if available, else Android-calibrated. Fixed once chosen per epoch. */
    private var fitStreamRaw: Boolean? = null

    private fun fitStream(tNs: Long): Stream? {
        val useRaw = fitStreamRaw ?: when {
            fresh(raw, tNs) -> true
            fresh(cal, tNs) -> false
            else -> return null
        }.also { fitStreamRaw = it }
        val s = if (useRaw) raw else cal
        return if (fresh(s, tNs)) s else null
    }

    /** Horizontal field of [s] in the phone-fixed (r1, r2) frame. */
    private fun horizontal(s: Stream): Pair<Double, Double>? {
        val u = up ?: return null
        val ref = r1 ?: run {
            val x = Vec3(1.0, 0.0, 0.0)
            val a = (if (abs(x dot u) < 0.9) x else Vec3(0.0, 1.0, 0.0)).perp(u).unit() ?: return null
            a.also { r1 = it }
        }
        val h = s.ema!!.perp(u)
        return (h dot ref) to (h dot (u cross ref))
    }

    /** [gyroBias]: current estimate of the yaw-rate bias (rad/s), so that coverage is not faked by gyro drift. */
    fun onMotion(u: MotionUpdate, gyroBias: Double = 0.0) {
        if (u.mountEpoch != epoch) reset(u.mountEpoch)
        up = u.up ?: up
        forward = u.forward
        cumGyroBearing -= (u.yawRateUp - gyroBias) * u.dtS
        lastT = u.tNs
        qualityCache = null // time-fraction metrics change every update
        // Shake gate: a shaking or wobbling phone mixes the large vertical field into the horizontal
        // projection (≈ 2.3° heading error per 1° tilt error in Kyiv); without a rotation vector,
        // acceleration tilts the accelerometer-based "up" the same way.
        val tiltLimit = if (u.upFromOrientation) cfg.maxTiltRateRmsWithOrientation else cfg.maxTiltRateRms
        shaky = u.tiltRateRms > tiltLimit || (!u.upFromOrientation && u.horizontalAccel > cfg.maxHorizontalAccel)
        val s = fitStream(u.tNs)
        if (s != null) checkVertical(s, u.tNs)
        if (!u.stationary) {
            movingN++
            tiltSum += u.tiltRateRms
            if (shaky) shakyN++
            if (s != null && !clean(u.tNs)) dirtyN++
        }
        if (s != null && clean(u.tNs) && !shaky) horizontal(s)?.let { (px, py) ->
            // Bin by gyro heading: coverage is known without trusting the distorted compass.
            val b = bin(cumGyroBearing)
            if (binN[b] >= BIN_CAP) { val k = (BIN_CAP - 1) / BIN_CAP; binX[b] *= k; binY[b] *= k; binN[b] *= k }
            binX[b] += px; binY[b] += py; binN[b] += 1.0
            ironDirty = true
        }
        candidate(u.tNs)?.let { hist.addLast(Triple(u.tNs, it.bearingRad, cumGyroBearing)) }
        while (hist.isNotEmpty() && hist.first().first < u.tNs - ((cfg.gyroCheckS + 1) * 1e9).toLong()) hist.removeFirst()
    }

    /** Track the vertical field component; mark the time as dirty when it jumps. */
    private fun checkVertical(s: Stream, tNs: Long) {
        val u = up ?: return
        val v = s.ema!! dot u
        if (s.vN < 50) { s.vMed = (s.vMed * s.vN + v) / (s.vN + 1); s.vN++; return }
        if (abs(v - s.vMed) > cfg.verticalToleranceUt) lastDirtyT = tNs
        s.vMed += 0.02 * kotlin.math.sign(v - s.vMed)
        s.vN++
    }

    private fun clean(tNs: Long): Boolean {
        val s = fitStream(tNs) ?: return false
        if (s.vN < 50) return false
        return lastDirtyT == Long.MIN_VALUE || tNs - lastDirtyT > (cfg.anomalyHoldoffS * 1e9).toLong()
    }

    private fun reset(newEpoch: Int) {
        epoch = newEpoch
        lastDirtyT = Long.MIN_VALUE
        movingN = 0; dirtyN = 0; shakyN = 0; tiltSum = 0.0
        centers.clear(); lastAlignRms = null; qualityCache = null
        raw.vN = 0; cal.vN = 0
        r1 = null; fitStreamRaw = null; hist.clear(); align.clear()
        binX.fill(0.0); binY.fill(0.0); binN.fill(0.0)
        iron = null; ironDirty = false
    }

    private fun bin(bearing: Double) = floor(((bearing % (2 * PI) + 2 * PI) % (2 * PI)) / (2 * PI) * BINS).toInt().coerceIn(0, BINS - 1)

    private fun octants(): Int = (0 until BINS).filter { binN[it] >= 10 }.map { it * 8 / BINS }.toSet().size

    private fun refitIron() {
        ironDirty = false
        val oct = octants()
        val pts = (0 until BINS).filter { binN[it] >= 10 }.map { binX[it] / binN[it] to binY[it] / binN[it] }
        val circle = if (oct >= cfg.circleMinOctants) fitCircle(pts) else null
        // The ellipse needs real angular spread of the *field* around the circle centre too (not just gyro bins).
        val fieldOct = circle?.let { c -> pts.map { (x, y) -> (((atan2(y - c.cy, x - c.cx) + PI) / (2 * PI) * 8).toInt()).coerceIn(0, 7) }.toSet().size } ?: 0
        iron = if (oct >= cfg.ellipseMinOctants && fieldOct >= cfg.ellipseMinOctants) fitEllipse(pts) ?: circle else circle
        qualityCache = null
        iron?.let { f ->
            if (oct >= 5 && (centers.isEmpty() || lastT - centers.last().first >= 10_000_000_000L)) {
                centers.addLast(Triple(lastT, f.cx, f.cy))
                while (centers.isNotEmpty() && centers.first().first < lastT - 300_000_000_000L) centers.removeFirst()
            }
        }
    }

    /** Current self-assessment. Cheap; cached within one motion update. */
    fun quality(): CompassQuality {
        qualityCache?.let { return it }
        if (ironDirty) refitIron()
        val f = iron
        val reasons = LinkedHashSet<String>()
        var hard = false
        var marginal = false
        fun bad(code: String) { reasons += code; hard = true }
        fun meh(code: String) { reasons += code; marginal = true }

        if (saturated) bad("SATURATED")
        val expectedH = reference?.let { it.fieldUt * cos(Math.toRadians(it.inclinationDeg)) }
        val radiusRatio = if (f != null && expectedH != null && expectedH > 0) f.radius / expectedH else null
        radiusRatio?.let {
            if (it < 0.3) bad("WEAK_FIELD") else if (it < 0.6) meh("WEAK_FIELD")
            if (it > 3.0) bad("STRONG_FIELD") else if (it > 1.7) meh("STRONG_FIELD")
        }
        val scatterDeg = f?.let { fit ->
            val rs = (0 until BINS).filter { binN[it] >= 10 }.map { b ->
                val (qx, qy) = corrected(fit, binX[b] / binN[b], binY[b] / binN[b]); sqrt(qx * qx + qy * qy)
            }
            if (rs.size < 3) null else Math.toDegrees(sqrt(rs.sumOf { (it - fit.radius) * (it - fit.radius) } / rs.size) / fit.radius)
        }
        scatterDeg?.let { if (it > 20) bad("NOISY_FIT") else if (it > 8) meh("NOISY_FIT") }
        val drift = if (f != null && centers.size >= 3) {
            var mx = 0.0
            for (a in centers) for (b in centers) mx = max(mx, sqrt((a.second - b.second).let { it * it } + (a.third - b.third).let { it * it }))
            mx / f.radius
        } else null
        drift?.let { if (it > 0.5) bad("UNSTABLE_DISTORTION") else if (it > 0.2) meh("UNSTABLE_DISTORTION") }
        val hardIron = f?.let { sqrt(it.cx * it.cx + it.cy * it.cy) }
        if (hardIron != null && hardIron > 200) meh("LARGE_HARD_IRON")
        val enough = movingN * 0.05 >= cfg.qualityMinMovingS
        val dirtyF = if (enough) dirtyN.toDouble() / movingN else null
        val shakyF = if (enough) shakyN.toDouble() / movingN else null
        val tiltMean = if (movingN > 0) tiltSum / movingN else null
        dirtyF?.let { if (it > 0.6) bad("OFTEN_DISTURBED") else if (it > 0.3) meh("OFTEN_DISTURBED") }
        shakyF?.let { if (it > 0.7) bad("SHAKY_MOUNT") else if (it > 0.3) meh("SHAKY_MOUNT") }
        if (wirelessCharging) meh("WIRELESS_CHARGING")
        lastAlignRms?.let { val d = Math.toDegrees(it); if (d > 25) bad("GNSS_DISAGREES") else if (d > 10) meh("GNSS_DISAGREES") }

        val verdict = when {
            hard -> CompassVerdict.UNUSABLE
            f == null -> CompassVerdict.UNKNOWN
            marginal -> CompassVerdict.MARGINAL
            else -> CompassVerdict.USABLE
        }
        return CompassQuality(
            verdict, reasons, if (f == null) "none" else if (f.ellipse) "ellipse" else "circle", octants(),
            radiusRatio, scatterDeg, drift, hardIron, dirtyF, shakyF, tiltMean, lastAlignRms?.let { Math.toDegrees(it) },
            saturated, wirelessCharging, epoch,
        ).also { qualityCache = it }
    }

    /** Kåsa circle fit: x² + y² = 2a·x + 2b·y + c. */
    private fun fitCircle(pts: List<Pair<Double, Double>>): IronFit? {
        if (pts.size < 3) return null
        val ata = Mat(3, 3)
        val atb = DoubleArray(3)
        for ((x, y) in pts) {
            val row = doubleArrayOf(2 * x, 2 * y, 1.0)
            val rhs = x * x + y * y
            for (i in 0..2) { atb[i] += row[i] * rhs; for (j in 0..2) ata[i, j] += row[i] * row[j] }
        }
        val sol = solve(ata, atb) ?: return null
        val r2 = sol[2] + sol[0] * sol[0] + sol[1] * sol[1]
        if (r2 <= 0) return null
        return IronFit(sol[0], sol[1], 1.0, 0.0, 1.0, sqrt(r2), false)
    }

    /** Algebraic ellipse fit A·x² + B·xy + C·y² + D·x + E·y = 1, then W = M^½ normalized so the corrected radius keeps the mean radius. */
    private fun fitEllipse(pts: List<Pair<Double, Double>>): IronFit? {
        if (pts.size < 6) return null
        // Center the data first for conditioning.
        val mx = pts.sumOf { it.first } / pts.size
        val my = pts.sumOf { it.second } / pts.size
        val ata = Mat(5, 5)
        val atb = DoubleArray(5)
        for ((x0, y0) in pts) {
            val x = x0 - mx; val y = y0 - my
            val row = doubleArrayOf(x * x, x * y, y * y, x, y)
            for (i in 0..4) { atb[i] += row[i]; for (j in 0..4) ata[i, j] += row[i] * row[j] }
        }
        val (a, b, c, d, e) = solve(ata, atb)?.toList() ?: return null
        val det = 4 * a * c - b * b
        if (a <= 0 || c <= 0 || det <= 0) return null // not an ellipse
        val cx = (b * e - 2 * c * d) / det
        val cy = (b * d - 2 * a * e) / det
        // (p−c)ᵀ M (p−c) = k with M = [[a, b/2], [b/2, c]], k = 1 + a·cx² + b·cx·cy + c·cy².
        val k = 1 + a * cx * cx + b * cx * cy + c * cy * cy
        if (k <= 0) return null
        // Symmetric square root of M/k via eigen-decomposition.
        val m11 = a / k; val m12 = b / 2 / k; val m22 = c / k
        val tr = m11 + m22
        val disc = sqrt(((m11 - m22) / 2).let { it * it } + m12 * m12)
        val l1 = tr / 2 + disc
        val l2 = tr / 2 - disc
        if (l2 <= 0) return null
        val ang = 0.5 * atan2(2 * m12, m11 - m22)
        val cs = cos(ang); val sn = sin(ang)
        val s1 = sqrt(l1); val s2 = sqrt(l2)
        // W = R diag(s1, s2) Rᵀ maps the ellipse to the unit circle; scale back by the geometric-mean radius.
        val scale = 1 / sqrt(s1 * s2)
        val w11 = (cs * cs * s1 + sn * sn * s2) * scale
        val w12 = (cs * sn * (s1 - s2)) * scale
        val w22 = (sn * sn * s1 + cs * cs * s2) * scale
        // Reject implausible shapes (axis ratio > 2:1): those are more likely bad data than soft iron.
        if (s1 / s2 > 2.0) return null
        return IronFit(cx + mx, cy + my, w11, w12, w22, scale, true)
    }

    private fun solve(m: Mat, b: DoubleArray): DoubleArray? = try {
        val inv = m.inv()
        DoubleArray(b.size) { i -> b.indices.sumOf { j -> inv[i, j] * b[j] } }
    } catch (_: IllegalArgumentException) { null }

    private fun corrected(fit: IronFit, px: Double, py: Double): Pair<Double, Double> {
        val dx = px - fit.cx; val dy = py - fit.cy
        return (fit.w11 * dx + fit.w12 * dy) to (fit.w12 * dx + fit.w22 * dy)
    }

    /** Add a GNSS alignment pair: trusted course while driving straight. */
    fun addCalibration(tNs: Long, courseRad: Double, speedMps: Double, yawRate: Double) {
        if (speedMps < cfg.alignMinSpeedMps || abs(yawRate) > cfg.alignMaxYawRate) return
        if (!clean(tNs) || shaky) return
        val s = fitStream(tNs) ?: return
        iron?.let { f ->
            val (px, py) = horizontal(s) ?: return
            val (qx, qy) = corrected(f, px, py)
            if (abs(sqrt(qx * qx + qy * qy) - f.radius) > cfg.radiusTolerance * f.radius) return
        }
        val (px, py) = horizontal(s) ?: return
        align.addLast(Triple(px, py, courseRad))
        while (align.size > ALIGN_CAP) align.removeFirst()
    }

    /** Heading candidate right now, before the gyro-consistency gate. */
    private fun candidate(tNs: Long): CompassReading? {
        if (ironDirty) refitIron()
        val fit = iron
        val s = fitStream(tNs)
        val u = up
        if (fit != null && s != null && u != null) {
            val (px, py) = horizontal(s) ?: return null
            val (qx, qy) = corrected(fit, px, py)
            if (abs(sqrt(qx * qx + qy * qy) - fit.radius) > cfg.radiusTolerance * fit.radius) return null
            val thetaC = atan2(qy, qx)
            // GNSS alignment: circular mean of (course − θc) over stored pairs.
            if (align.size >= cfg.alignMinSamples) {
                var cs = 0.0; var sn = 0.0
                val res = ArrayList<Double>(align.size)
                for ((ax, ay, course) in align) {
                    val (cx, cy) = corrected(fit, ax, ay)
                    val d = course - atan2(cy, cx)
                    cs += cos(d); sn += sin(d); res += d
                }
                val c = atan2(sn, cs)
                val rms = sqrt(res.sumOf { Geo.wrapRad(it - c).let { e -> e * e } } / res.size)
                if (lastAlignRms == null || abs(lastAlignRms!! - rms) > 0.01) { lastAlignRms = rms; qualityCache = null }
                var sigma = max(Math.toRadians(cfg.minSigmaDeg), 1.5 * rms)
                if (!fit.ellipse) sigma += Math.toRadians(cfg.circleOnlySigmaDeg)
                if (octants() <= 4) sigma += Math.toRadians(cfg.coverage5SigmaDeg)
                val psi = thetaC + c
                if (align.none { abs(Geo.wrapRad(it.third - psi)) < PI / 4 }) sigma += Math.toRadians(cfg.extrapolationSigmaDeg)
                return CompassReading(norm(thetaC + c), sigma, CompassMode.GNSS_ALIGNED)
            }
            // No GNSS: align via the learned forward axis and declination.
            val fw = forward ?: return null
            val ref = r1 ?: return null
            val n = (ref * qx + (u cross ref) * qy).unit() ?: return null
            var base = cfg.forwardAlignedSigmaDeg + if (!fit.ellipse) cfg.circleOnlySigmaDeg else 0.0
            base += when (octants()) {
                in 0..4 -> cfg.coverage4SigmaDeg
                5 -> cfg.coverage5SigmaDeg
                else -> 0.0
            }
            return forwardAligned(fw, n, u, base, CompassMode.FORWARD_ALIGNED)
        }
        // No iron fit yet: Android-calibrated field, gated by the expected magnitude.
        val fw = forward ?: return null
        if (u == null || !fresh(cal, tNs)) return null
        val b = cal.ema!!
        val expected = reference?.fieldUt ?: return null
        if (abs(b.norm - expected) > cfg.magnitudeTolerance * expected) return null
        val n = b.perp(u).unit() ?: return null
        return forwardAligned(fw, n, u, cfg.uncorrectedSigmaDeg, CompassMode.UNCORRECTED)
    }

    private fun forwardAligned(fw: Vec3, magNorth: Vec3, u: Vec3, baseSigmaDeg: Double, mode: CompassMode): CompassReading {
        val psiM = atan2(fw dot (magNorth cross u), fw dot magNorth)
        val decl = reference?.declinationDeg
        val sigmaDeg = baseSigmaDeg + if (decl == null) cfg.noDeclinationSigmaDeg else 0.0
        return CompassReading(norm(psiM + Math.toRadians(decl ?: 0.0)), Math.toRadians(sigmaDeg), mode)
    }

    private fun norm(a: Double) = (Geo.wrapRad(a) + 2 * PI) % (2 * PI)

    private fun gyroConsistent(): Boolean {
        if (hist.size < 2) return false
        val end = hist.last()
        val start = hist.firstOrNull { it.first >= end.first - (cfg.gyroCheckS * 1e9).toLong() } ?: return false
        if (end.first - start.first < (cfg.gyroCheckS * 0.7 * 1e9).toLong()) return false
        val dMag = Geo.wrapRad(end.second - start.second)
        val dGyro = end.third - start.third
        return abs(Geo.wrapRad(dMag - dGyro)) <= Math.toRadians(cfg.gyroCheckDeg)
    }

    /** Current compass heading (true bearing) with honest sigma, or null if unusable right now. */
    fun heading(tNs: Long): CompassReading? {
        if (!cfg.enabled || shaky || !gyroConsistent()) return null
        if (fitStream(tNs) != null && !clean(tNs)) return null
        val q = quality()
        if (q.verdict == CompassVerdict.UNUSABLE) return null
        val r = candidate(tNs) ?: return null
        return if (q.verdict == CompassVerdict.MARGINAL) r.copy(sigmaRad = r.sigmaRad + Math.toRadians(cfg.marginalSigmaDeg)) else r
    }

    private companion object {
        const val BINS = 36
        const val BIN_CAP = 400.0
        const val ALIGN_CAP = 300
    }
}
