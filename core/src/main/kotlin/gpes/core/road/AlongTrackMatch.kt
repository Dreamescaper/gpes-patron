package gpes.core.road

import gpes.core.geo.Geo
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Along-track position from the shape of the recent path (M4, docs/road-constraint.md).
 *
 * Both profiles are bearings as a function of distance *back* from now: the gyro's (odometry distance)
 * and the matched road's (road distance from the matched point). If the car is really Δ metres further
 * along the road than the matched point, then ψ_gyro(s) ≈ ψ_road(s − Δ) + c, with c an unknown heading
 * offset. We search Δ and accept it only when the road has a persistent bearing change in the window (a
 * turn or a bend), the best Δ is clearly better than the others, and the residual is small. A lane change
 * (out-and-back, net ≈ 0) gives no persistent change, so it never produces a shift.
 */
object AlongTrackMatch {

    /** A profile sample: distance back from now (m, ≥ 0) and unwrapped bearing (rad). */
    data class Sample(val back: Double, val bearing: Double)

    data class Result(val shiftM: Double, val sigmaM: Double, val rmsDeg: Double, val turnDeg: Double)

    data class Config(
        val windowM: Double = 250.0,
        val maxShiftM: Double = 50.0,
        val stepM: Double = 2.0,
        /** The road must turn by at least this much inside the window … */
        val minTurnDeg: Double = 20.0,
        /** … at least this far from both window ends, so the turn is fully seen. */
        val turnInsetM: Double = 30.0,
        /** Matched residual RMS must be below this (degrees). */
        val maxRmsDeg: Double = 6.0,
        /** The best shift must beat any shift ≥ [ambiguityM] away by this cost ratio. */
        val ambiguityRatio: Double = 2.0,
        val ambiguityM: Double = 15.0,
        val minOverlapM: Double = 120.0,
        val sigmaFloorM: Double = 4.0,
    )

    /** Linear interpolation of an unwrapped profile sorted by [Sample.back]; null outside its range. */
    private fun at(p: List<Sample>, back: Double): Double? {
        if (p.isEmpty() || back < p.first().back || back > p.last().back) return null
        var i = 1
        while (i < p.size && p[i].back < back) i++
        if (i >= p.size) return p.last().bearing
        val a = p[i - 1]; val b = p[i]
        val f = if (b.back > a.back) (back - a.back) / (b.back - a.back) else 0.0
        return a.bearing + f * (b.bearing - a.bearing)
    }

    fun match(gyro: List<Sample>, road: List<Sample>, cfg: Config = Config()): Result? {
        if (gyro.size < 3 || road.size < 3) return null
        val w = min(cfg.windowM, min(gyro.last().back, road.last().back))
        if (w < cfg.minOverlapM) return null
        // Persistent road turn inside the window, away from its ends.
        val inner = road.filter { it.back in cfg.turnInsetM..(w - cfg.turnInsetM) }
        val r0 = at(road, 0.0) ?: return null
        val rw = at(road, w) ?: return null
        val turn = Math.toDegrees(abs(r0 - rw))
        if (turn < cfg.minTurnDeg || inner.isEmpty()) return null
        val turnInside = Math.toDegrees(abs((at(road, cfg.turnInsetM) ?: return null) - (at(road, w - cfg.turnInsetM) ?: return null)))
        if (turnInside < 0.7 * cfg.minTurnDeg) return null

        val shifts = ArrayList<Pair<Double, Double>>() // shift → cost (mean squared residual)
        var sh = -cfg.maxShiftM
        while (sh <= cfg.maxShiftM + 1e-9) {
            val res = ArrayList<Double>()
            var s = max(0.0, sh)
            while (s <= w) {
                val g = at(gyro, s); val r = at(road, s - sh)
                if (g != null && r != null) res += g - r
                s += cfg.stepM
            }
            if (res.size * cfg.stepM >= cfg.minOverlapM) {
                // Remove the constant heading offset (circular mean of the differences).
                val c = atan2(res.sumOf { sin(it) }, res.sumOf { cos(it) })
                val cost = res.sumOf { val d = Geo.wrapRad(it - c); d * d } / res.size
                shifts += sh to cost
            }
            sh += cfg.stepM
        }
        if (shifts.isEmpty()) return null
        val best = shifts.minBy { it.second }
        val rms = Math.toDegrees(sqrt(best.second))
        if (rms > cfg.maxRmsDeg) return null
        val rival = shifts.filter { abs(it.first - best.first) >= cfg.ambiguityM }.minOfOrNull { it.second }
        if (rival != null && rival < cfg.ambiguityRatio * best.second) return null
        // Width of the valley where the cost stays below twice the minimum (+ a small absolute floor).
        val lim = 2 * best.second + Math.toRadians(1.0).let { it * it }
        val valley = shifts.filter { it.second <= lim }.map { it.first }
        val half = (valley.max() - valley.min()) / 2
        if (best.first <= -cfg.maxShiftM + cfg.stepM || best.first >= cfg.maxShiftM - cfg.stepM) return null // at the edge
        return Result(best.first, max(cfg.sigmaFloorM, half), rms, turn)
    }

    /** Unwraps a sequence of bearings (rad) in order. */
    fun unwrap(bearings: List<Double>): List<Double> {
        val out = ArrayList<Double>(bearings.size)
        for (b in bearings) out += if (out.isEmpty()) b else out.last() + Geo.wrapRad(b - out.last())
        return out
    }
}
