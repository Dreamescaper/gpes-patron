package gpes.core.geo

import kotlin.math.cos
import kotlin.math.hypot

/** A point of a recorded route; [tNs] is in the recording's timebase (`elapsedRealtimeNanos`). */
data class RoutePoint(val tNs: Long, val lat: Double, val lon: Double)

/**
 * Helpers for showing a recorded drive as a small picture: a route of thousands of points is thinned to a few hundred without
 * changing its shape, and its length and duration are measured. Pure, no clock.
 */
object RoutePreview {
    private const val M_PER_DEG_LAT = 111_320.0

    /**
     * Douglas–Peucker: keeps the first and the last point and every point needed so that no dropped point is farther than
     * [toleranceM] from the simplified line (distance to the segment). Iterative, so a long drive cannot overflow the stack.
     */
    fun simplify(points: List<RoutePoint>, toleranceM: Double): List<RoutePoint> {
        val n = points.size
        if (n <= 2) return points
        val lat0 = points[0].lat
        val kx = M_PER_DEG_LAT * cos(Math.toRadians(lat0))
        val x = DoubleArray(n) { (points[it].lon - points[0].lon) * kx }
        val y = DoubleArray(n) { (points[it].lat - lat0) * M_PER_DEG_LAT }
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (lo, hi) = stack.removeLast()
            if (hi - lo < 2) continue
            var worst = -1.0
            var at = -1
            for (i in lo + 1 until hi) {
                val d = distanceToSegment(x[i], y[i], x[lo], y[lo], x[hi], y[hi])
                if (d > worst) { worst = d; at = i }
            }
            if (worst > toleranceM) {
                keep[at] = true
                stack.addLast(intArrayOf(lo, at))
                stack.addLast(intArrayOf(at, hi))
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** [simplify] with the tolerance doubled until at most [maxPoints] points remain. */
    fun simplifyTo(points: List<RoutePoint>, maxPoints: Int, startToleranceM: Double = 3.0): List<RoutePoint> {
        var tol = startToleranceM
        var out = simplify(points, tol)
        var guard = 0
        while (out.size > maxPoints && guard++ < 24) { tol *= 2; out = simplify(points, tol) }
        return out
    }

    /** Length of the polyline (great-circle steps). */
    fun lengthM(points: List<RoutePoint>): Double {
        var sum = 0.0
        for (i in 1 until points.size) sum += Geo.haversineM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        return sum
    }

    fun durationS(points: List<RoutePoint>): Double =
        if (points.size < 2) 0.0 else (points.last().tNs - points.first().tNs) / 1e9

    private fun distanceToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        if (len2 == 0.0) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }
}
