package gpes.core.replay

import gpes.core.geo.Geo
import gpes.core.model.LocSource
import gpes.core.model.TrustState
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

@Serializable
data class TickRow(
    val tS: Double,
    val estLat: Double,
    val estLon: Double,
    val r68: Double,
    val mode: String,
    val truthLat: Double?,
    val truthLon: Double?,
    val errM: Double?,
    val headingErrDeg: Double?,
    val degradedKind: String?,
    val sinceDegradedStartS: Double?,
)

@Serializable
data class WindowMetrics(
    val kind: String,
    val startS: Double,
    val durationS: Double,
    val maxErrM: Double?,
    val errAtEndM: Double?,
    /** Seconds from window start until error first exceeds each threshold (null = never). */
    val timeToExceedS: Map<String, Double?>,
    /** Seconds after window end until error is back below [RECOVERY_ERR_M] (null = not within 5 min). */
    val recoveryS: Double?,
)

@Serializable
data class TrustMetrics(
    val cleanFixes: Int,
    val falseRejections: Int,
    val falseRejectionRate: Double?,
    val perturbedFixes: Int,
    val missedDetections: Int,
    val missedDetectionRate: Double?,
    /** Per manipulated window: seconds until the first non-TRUSTED verdict for a perturbed fix. */
    val detectionLatencyS: List<Double?>,
)

@Serializable
data class ReplaySummary(
    val scenario: String,
    val variant: String,
    val estimator: String,
    val durationS: Double,
    val ticks: Int,
    val ticksWithTruth: Int,
    val ticksWithEstimate: Int,
    val rmseM: Double?,
    val p50M: Double?,
    val p95M: Double?,
    val maxM: Double?,
    val degradedRmseM: Double?,
    val degradedP95M: Double?,
    val headingP50Deg: Double?,
    val headingP95Deg: Double?,
    /** Fraction of ticks where the true error ≤ reported 68% / 95% radius (ideal ≈ 0.68 / 0.95). */
    val within68: Double?,
    val within95: Double?,
    val windows: List<WindowMetrics>,
    val trust: TrustMetrics,
    val modeFractions: Map<String, Double>,
)

const val RECOVERY_ERR_M = 20.0
val EXCEED_THRESHOLDS_M = listOf(50, 100, 250, 500, 1000)

object Metrics {
    /** Minimum injected offset for a GNSS fix to count as "should be rejected". */
    const val PERTURBED_MIN_OFFSET_M = 50.0

    fun rows(r: ReplayResult): List<TickRow> = r.estimates.map { e ->
        val tr = r.truth.at(e.tNs)
        val err = tr?.let { Geo.haversineM(it.lat, it.lon, e.lat, e.lon) }
        val hErr = if (tr != null && e.headingRad != null && !tr.bearingDeg.isNaN() && tr.speedMps > 3) {
            abs(Geo.wrapDeg(Math.toDegrees(e.headingRad!!) - tr.bearingDeg))
        } else null
        val w = r.windows.firstOrNull { e.tNs >= it.startNs && e.tNs < it.endNs }
        TickRow(
            (e.tNs - r.t0Ns) / 1e9, e.lat, e.lon, e.accuracyM, e.mode.name, tr?.lat, tr?.lon, err, hErr,
            w?.kind, w?.let { (e.tNs - it.startNs) / 1e9 },
        )
    }

    fun summarize(r: ReplayResult, rows: List<TickRow> = rows(r)): ReplaySummary {
        val errs = rows.mapNotNull { it.errM }
        val degradedErrs = rows.filter { it.degradedKind != null }.mapNotNull { it.errM }
        val heading = rows.mapNotNull { it.headingErrDeg }
        val withTruth = rows.filter { it.errM != null }
        val totalTicks = ((r.endNs - r.t0Ns) / 1e9).toInt()
        return ReplaySummary(
            scenario = r.scenario.name,
            variant = r.variant.name,
            estimator = r.variant.estimator,
            durationS = (r.endNs - r.t0Ns) / 1e9,
            ticks = totalTicks,
            ticksWithTruth = withTruth.size,
            ticksWithEstimate = rows.size,
            rmseM = rmse(errs), p50M = pct(errs, 0.5), p95M = pct(errs, 0.95), maxM = errs.maxOrNull(),
            degradedRmseM = rmse(degradedErrs), degradedP95M = pct(degradedErrs, 0.95),
            headingP50Deg = pct(heading, 0.5), headingP95Deg = pct(heading, 0.95),
            within68 = frac(withTruth) { it.errM!! <= it.r68 },
            within95 = frac(withTruth) { it.errM!! <= it.r68 * 2.4477 / 1.5096 },
            windows = r.windows.map { windowMetrics(r, it, rows) },
            trust = trustMetrics(r),
            modeFractions = rows.groupingBy { it.mode }.eachCount().mapValues { it.value.toDouble() / rows.size.coerceAtLeast(1) },
        )
    }

    private fun windowMetrics(r: ReplayResult, w: DegradedWindow, rows: List<TickRow>): WindowMetrics {
        val startS = (w.startNs - r.t0Ns) / 1e9
        val endS = (w.endNs - r.t0Ns) / 1e9
        val inside = rows.filter { it.tS >= startS && it.tS < endS && it.errM != null }
        val after = rows.filter { it.tS >= endS && it.tS < endS + 300 && it.errM != null }
        return WindowMetrics(
            kind = w.kind, startS = startS, durationS = endS - startS,
            maxErrM = inside.maxOfOrNull { it.errM!! },
            errAtEndM = inside.lastOrNull()?.errM,
            timeToExceedS = EXCEED_THRESHOLDS_M.associate { thr ->
                "${thr}m" to inside.firstOrNull { it.errM!! > thr }?.let { it.tS - startS }
            },
            recoveryS = if (endS >= (r.endNs - r.t0Ns) / 1e9 - 1) null else after.firstOrNull { it.errM!! < RECOVERY_ERR_M }?.let { it.tS - endS },
        )
    }

    private fun trustMetrics(r: ReplayResult): TrustMetrics {
        val labelByT = r.labels.associateBy { it.tNs to it.provider }
        val gnss = r.trust.filter { it.source == LocSource.GNSS }
        var clean = 0; var falseRej = 0; var pert = 0; var missed = 0
        for (a in gnss) {
            val l = labelByT[a.tNs to a.provider] ?: continue
            if (l.offsetM < 1.0) {
                // Only fixes that are good enough to be truth count as "should be trusted".
                if (r.truth.at(a.tNs) == null) continue
                clean++
                if (a.state != TrustState.TRUSTED) falseRej++
            } else if (l.offsetM >= PERTURBED_MIN_OFFSET_M) {
                pert++
                if (a.state == TrustState.TRUSTED) missed++
            }
        }
        val latencies = r.windows.filter { it.kind != "gnss_outage" }.map { w ->
            gnss.firstOrNull { a ->
                a.tNs >= w.startNs && a.tNs < w.endNs && a.state != TrustState.TRUSTED &&
                    (labelByT[a.tNs to a.provider]?.offsetM ?: 0.0) >= PERTURBED_MIN_OFFSET_M
            }?.let { (it.tNs - w.startNs) / 1e9 }
        }
        return TrustMetrics(
            clean, falseRej, if (clean > 0) falseRej.toDouble() / clean else null,
            pert, missed, if (pert > 0) missed.toDouble() / pert else null, latencies,
        )
    }

    /** Error as a function of time since the start of degradation, in [binS] bins: (binStartS, p50, p95, n). */
    fun errorVsTimeSinceDegraded(rows: List<TickRow>, binS: Double = 10.0): List<List<Double>> =
        rows.filter { it.sinceDegradedStartS != null && it.errM != null }
            .groupBy { (it.sinceDegradedStartS!! / binS).toInt() }
            .toSortedMap()
            .map { (bin, rs) -> val e = rs.map { it.errM!! }; listOf(bin * binS, pct(e, 0.5)!!, pct(e, 0.95)!!, e.size.toDouble()) }

    private fun rmse(x: List<Double>) = if (x.isEmpty()) null else sqrt(x.sumOf { it * it } / x.size)

    fun pct(x: List<Double>, p: Double): Double? {
        if (x.isEmpty()) return null
        val s = x.sorted()
        val idx = (p * (s.size - 1))
        val lo = idx.toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (idx - lo) * (s[hi] - s[lo])
    }

    private inline fun <T> frac(xs: List<T>, pred: (T) -> Boolean): Double? =
        if (xs.isEmpty()) null else xs.count(pred).toDouble() / xs.size
}
