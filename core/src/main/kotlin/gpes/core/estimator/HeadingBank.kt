package gpes.core.estimator

import gpes.core.geo.Geo
import gpes.core.geo.LocalFrame
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class HeadingBankConfig(
    val enabled: Boolean = true,
    /** Number of heading hypotheses, spread evenly over 360°. */
    val hypotheses: Int = 12,
    /** Hand the heading to the EKF once the mixture heading std is below this (degrees). */
    val convergedStdDeg: Double = 15.0,
    /** ... and the car has driven at least this far since the bank started (m). */
    val minTravelM: Double = 300.0,
    /** ... and at least this many coarse fixes were used. */
    val minFixes: Int = 4,
    /** Weight floor relative to the best hypothesis, so a hypothesis can recover. */
    val minRelWeight: Double = 1e-4,
    val headingRandomWalk: Double = 0.01,
    val gyroScaleError: Double = 0.02,
    /** Position process noise (m/√s), for lateral slip, lane changes and reversing. */
    val posRandomWalk: Double = 0.5,
    /** Along-track speed error (fraction of distance): speedometer scale and latency. */
    val alongTrackError: Double = 0.03,
    /**
     * Position std multiplier at hand-over. Coarse fixes have correlated errors, but the bank treats
     * them as independent, so its position covariance is too small (dev-guide P5).
     */
    val handoverPosStdScale: Double = 2.0,
)

/**
 * A Gaussian-sum filter over the unknown absolute heading (after PX4's EKF-GSF yaw estimator, with
 * coarse position fixes instead of GNSS velocity). Each hypothesis is a small EKF over [e, n, ψ] that
 * starts from a different heading and dead-reckons with the gyro (relative bearing, very stable) and the
 * estimated speed (OBD). A coarse fix updates every hypothesis and re-weights it by its likelihood: after
 * the car has driven a few hundred metres, only headings whose track passes near the fixes survive.
 *
 * It keeps its own local frame, so it is independent of the main EKF frame and re-anchoring.
 */
class HeadingBank(private val cfg: HeadingBankConfig) {

    private class Hyp(var e: Double, var n: Double, var psi: Double, val p: DoubleArray, var logW: Double) {
        fun copy() = Hyp(e, n, psi, p.copyOf(), logW)
    }

    private var frame: LocalFrame? = null
    private var hyps: List<Hyp> = emptyList()
    private var travelledM = 0.0
    private var fixes = 0
    /**
     * Along-track distance error accumulated since the last fix: ∫(σ_v + scale·|v|) dt. Speed errors are
     * correlated (a wrong speed stays wrong), so the along-track variance grows as this squared, not
     * linearly in time. Without a speed source σ_v is large and the bank cannot discriminate headings.
     */
    private var alongErrM = 0.0

    val active: Boolean get() = hyps.isNotEmpty()

    fun copy(): HeadingBank = HeadingBank(cfg).also { c ->
        c.frame = frame; c.hyps = hyps.map { it.copy() }; c.travelledM = travelledM; c.fixes = fixes
        c.alongErrM = alongErrM
    }

    fun reset() {
        frame = null; hyps = emptyList(); travelledM = 0.0; fixes = 0; alongErrM = 0.0
    }

    /** [yawRate]: bias-corrected yaw rate about up (rad/s, counter-clockwise positive). */
    fun propagate(dt: Double, yawRate: Double, speed: Double, speedVar: Double) {
        if (!active || dt <= 0) return
        val ds = speed * dt
        travelledM += kotlin.math.abs(ds)
        val turn = yawRate * dt * cfg.gyroScaleError
        val qPsi = cfg.headingRandomWalk * cfg.headingRandomWalk * dt + turn * turn
        val qPos = cfg.posRandomWalk * cfg.posRandomWalk * dt
        val u0 = alongErrM
        alongErrM += (sqrt(max(speedVar, 0.0)) + cfg.alongTrackError * kotlin.math.abs(speed)) * dt
        val along = alongErrM * alongErrM - u0 * u0
        for (h in hyps) {
            val s = sin(h.psi); val c = cos(h.psi)
            h.e += ds * s; h.n += ds * c
            h.psi = Geo.wrapRad(h.psi - yawRate * dt)
            // F = [[1,0,ds·c],[0,1,−ds·s],[0,0,1]]; P ← F P Fᵀ + Q
            val f02 = ds * c; val f12 = -ds * s
            val p = h.p
            val a = DoubleArray(9)
            // A = F·P
            for (j in 0..2) {
                a[j] = p[j] + f02 * p[6 + j]
                a[3 + j] = p[3 + j] + f12 * p[6 + j]
                a[6 + j] = p[6 + j]
            }
            // P = A·Fᵀ
            for (i in 0..2) {
                p[i * 3] = a[i * 3] + a[i * 3 + 2] * f02
                p[i * 3 + 1] = a[i * 3 + 1] + a[i * 3 + 2] * f12
                p[i * 3 + 2] = a[i * 3 + 2]
            }
            p[0] += qPos + along * s * s; p[4] += qPos + along * c * c
            p[1] += along * s * c; p[3] += along * s * c
            p[8] += qPsi
        }
    }

    /** A coarse fix with per-axis variance [r] (m²). Starts the bank on the first call. */
    fun onFix(lat: Double, lon: Double, r: Double) {
        if (!active) {
            start(lat, lon, r); return
        }
        val z = frame!!.toEnu(lat, lon)
        for (h in hyps) {
            val p = h.p
            val ye = z.e - h.e; val yn = z.n - h.n
            val s00 = p[0] + r; val s01 = p[1]; val s11 = p[4] + r
            val det = s00 * s11 - s01 * s01
            val i00 = s11 / det; val i01 = -s01 / det; val i11 = s00 / det
            val nis = ye * (i00 * ye + i01 * yn) + yn * (i01 * ye + i11 * yn)
            h.logW += -0.5 * nis - 0.5 * ln(det)
            // K = P Hᵀ S⁻¹ (3×2), H selects e, n
            val k = DoubleArray(6)
            for (i in 0..2) {
                val pe = p[i * 3]; val pn = p[i * 3 + 1]
                k[i * 2] = pe * i00 + pn * i01
                k[i * 2 + 1] = pe * i01 + pn * i11
            }
            h.e += k[0] * ye + k[1] * yn
            h.n += k[2] * ye + k[3] * yn
            h.psi = Geo.wrapRad(h.psi + k[4] * ye + k[5] * yn)
            // Joseph form: P = (I−KH) P (I−KH)ᵀ + K R Kᵀ
            val ikh = doubleArrayOf(1 - k[0], -k[1], 0.0, -k[2], 1 - k[3], 0.0, -k[4], -k[5], 1.0)
            val tmp = mul3(ikh, p)
            val np = mul3t(tmp, ikh)
            for (i in 0..2) for (j in 0..2) np[i * 3 + j] += r * (k[i * 2] * k[j * 2] + k[i * 2 + 1] * k[j * 2 + 1])
            np.copyInto(p)
        }
        val best = hyps.maxOf { it.logW }
        val floor = best + ln(cfg.minRelWeight)
        for (h in hyps) h.logW = max(h.logW, floor) - best
        fixes++
        alongErrM = 0.0
    }

    private fun start(lat: Double, lon: Double, r: Double) {
        frame = LocalFrame(lat, lon)
        val nH = cfg.hypotheses
        val spread = PI / nH // σ = half the spacing between hypotheses
        hyps = List(nH) { i ->
            Hyp(0.0, 0.0, Geo.wrapRad(2 * PI * i / nH), doubleArrayOf(r, 0.0, 0.0, 0.0, r, 0.0, 0.0, 0.0, spread * spread), 0.0)
        }
        travelledM = 0.0
        fixes = 1
        alongErrM = 0.0
    }

    private fun weights(): DoubleArray {
        val w = DoubleArray(hyps.size) { exp(hyps[it].logW) }
        val sum = w.sum()
        for (i in w.indices) w[i] /= sum
        return w
    }

    /** Mixture heading (rad, clockwise from north) and its std (rad), or null while inactive. */
    fun heading(): Pair<Double, Double>? {
        if (!active) return null
        val w = weights()
        var sx = 0.0; var sy = 0.0
        for (i in hyps.indices) { sx += w[i] * sin(hyps[i].psi); sy += w[i] * cos(hyps[i].psi) }
        val mean = atan2(sx, sy)
        var v = 0.0
        for (i in hyps.indices) { val d = Geo.wrapRad(hyps[i].psi - mean); v += w[i] * (hyps[i].p[8] + d * d) }
        return mean to sqrt(v)
    }

    fun converged(): Boolean {
        val h = heading() ?: return false
        return fixes >= cfg.minFixes && travelledM >= cfg.minTravelM && Math.toDegrees(h.second) <= cfg.convergedStdDeg
    }

    /**
     * Hand-over state: position (lat, lon) and the 3×3 covariance of [e, n, ψ] (row-major, including the
     * spread between hypotheses), from the mixture.
     */
    fun mixture(): Triple<Double, Double, DoubleArray>? {
        val (psiMean, _) = heading() ?: return null
        val w = weights()
        var e = 0.0; var n = 0.0
        for (i in hyps.indices) { e += w[i] * hyps[i].e; n += w[i] * hyps[i].n }
        val cov = DoubleArray(9)
        for (i in hyps.indices) {
            val h = hyps[i]
            val d = doubleArrayOf(h.e - e, h.n - n, Geo.wrapRad(h.psi - psiMean))
            for (a in 0..2) for (b in 0..2) cov[a * 3 + b] += w[i] * (h.p[a * 3 + b] + d[a] * d[b])
        }
        val ll = frame!!.toLatLon(e, n)
        return Triple(ll.lat, ll.lon, cov)
    }

    private fun mul3(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { idx ->
        val i = idx / 3; val j = idx % 3
        a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j]
    }

    /** a · bᵀ */
    private fun mul3t(a: DoubleArray, b: DoubleArray) = DoubleArray(9) { idx ->
        val i = idx / 3; val j = idx % 3
        a[i * 3] * b[j * 3] + a[i * 3 + 1] * b[j * 3 + 1] + a[i * 3 + 2] * b[j * 3 + 2]
    }
}
