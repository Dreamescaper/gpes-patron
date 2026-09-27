package gpes.core.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object Wgs84 {
    const val A = 6378137.0
    const val F = 1 / 298.257223563
    const val E2 = F * (2 - F)
    const val MEAN_RADIUS = 6371008.8
}

data class Enu(val e: Double, val n: Double) {
    operator fun minus(o: Enu) = Enu(e - o.e, n - o.n)
    operator fun plus(o: Enu) = Enu(e + o.e, n + o.n)
    val norm: Double get() = sqrt(e * e + n * n)
}

data class LatLon(val lat: Double, val lon: Double)

/**
 * Local tangent plane anchored at ([lat0], [lon0]), using the ellipsoid's meridian and
 * prime-vertical radii at the origin. Error is below ~1 m within ±20 km of the origin, which is why
 * estimators re-anchor ([Geo.REANCHOR_DISTANCE_M]). Do not use this for continental distances; use
 * [Geo.haversineM] for those.
 */
class LocalFrame(val lat0: Double, val lon0: Double) {
    private val phi0 = Math.toRadians(lat0)
    private val sinPhi = sin(phi0)
    private val rN = Wgs84.A / sqrt(1 - Wgs84.E2 * sinPhi * sinPhi)
    private val rM = rN * (1 - Wgs84.E2) / (1 - Wgs84.E2 * sinPhi * sinPhi)
    private val cosPhi = cos(phi0)

    fun toEnu(lat: Double, lon: Double): Enu {
        val dLon = Geo.wrapDeg(lon - lon0)
        return Enu(Math.toRadians(dLon) * rN * cosPhi, Math.toRadians(lat - lat0) * rM)
    }

    fun toLatLon(e: Double, n: Double): LatLon =
        LatLon(lat0 + Math.toDegrees(n / rM), Geo.wrapDeg(lon0 + Math.toDegrees(e / (rN * cosPhi))))
}

object Geo {
    const val REANCHOR_DISTANCE_M = 20_000.0

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * Wgs84.MEAN_RADIUS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    /** Initial bearing in degrees, [0, 360), clockwise from north. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Destination point given a distance and a bearing (degrees). */
    fun destination(lat: Double, lon: Double, distanceM: Double, bearingDeg: Double): LatLon {
        val d = distanceM / Wgs84.MEAN_RADIUS
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return LatLon(Math.toDegrees(p2), wrapDeg(Math.toDegrees(l2)))
    }

    /** Wrap an angle to (−180, 180]. */
    fun wrapDeg(a: Double): Double {
        var x = a % 360.0
        if (x > 180) x -= 360
        if (x <= -180) x += 360
        return x
    }

    /** Wrap an angle to (−π, π]. */
    fun wrapRad(a: Double): Double {
        var x = a % (2 * PI)
        if (x > PI) x -= 2 * PI
        if (x <= -PI) x += 2 * PI
        return x
    }
}
