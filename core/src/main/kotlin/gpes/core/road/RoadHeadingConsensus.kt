package gpes.core.road

import gpes.core.geo.Geo
import gpes.core.geo.LocalFrame
import gpes.core.model.Cov2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Nearby straight roads can share a travel axis without identifying distance or a particular carriageway.
 * Use every candidate consistent with the road-free position ellipse and heading uncertainty, without HMM
 * probabilities, odometry, road names or class priors. Competing axes and an explicit off-road score reduce support;
 * parallel carriageways form one axis but do not accumulate extra support merely by their number.
 * Direction along an axis comes from the existing heading; this cannot resolve a 180° error (D-077).
 */
object RoadHeadingConsensus {
    fun bearing(
        net: RoadNetwork, lat: Double, lon: Double, cov: Cov2, heading: Double, headingStd: Double,
        road: RoadMatcherConfig, constraint: RoadConstraintConfig,
    ): Double? {
        val eigen = (cov.ee + cov.nn) / 2 + sqrt((cov.ee - cov.nn) * (cov.ee - cov.nn) / 4 + cov.en * cov.en)
        val sigma = sqrt(eigen.coerceAtLeast(0.0))
        if (sigma > constraint.uncertainHeadingMaxPoseSigmaM) return null
        val radius = maxOf(road.minRadiusM,
            3 * sqrt(eigen + net.maxHalfWidthM * net.maxHalfWidthM / 3 + road.osmGeometryStdM * road.osmGeometryStdM))
        val frame = LocalFrame(lat, lon)
        val headingVar = headingStd * headingStd + Math.toRadians(road.headingStdDeg).let { it * it }
        data class Candidate(val bearing: Double, val delta: Double, val score: Double, val straight: Boolean)
        val candidates = ArrayList<Candidate>()
        for (p in net.project(lat, lon, radius)) {
            val b = Math.toRadians(p.bearingDeg)
            val directed = Geo.wrapRad(if (abs(Geo.wrapRad(b - heading)) <= PI / 2) b else b + PI)
            val delta = Geo.wrapRad(directed - heading)
            if (delta * delta > 9 * headingVar) continue
            val half = RoadWidth.halfWidthM(p.segment)
            val r = half * half / 3 + road.osmGeometryStdM * road.osmGeometryStdM
            val ee = cov.ee + r; val nn = cov.nn + r
            val q = frame.toEnu(p.point.lat, p.point.lon)
            val nis = (nn * q.e * q.e - 2 * cov.en * q.e * q.n + ee * q.n * q.n) / (ee * nn - cov.en * cov.en)
            if (nis > 9) continue
            // An S bend may have equal end bearings: check the whole local window.
            val from = maxOf(0.0, p.distanceAlongM - constraint.straightWindowM)
            val to = minOf(net.lengthOf(p.segment.id), p.distanceAlongM + constraint.straightWindowM)
            val straight = net.maxBearingDeviationDeg(p.segment.id, from, to, p.bearingDeg) <= constraint.straightMaxDeg
            candidates += Candidate(directed, delta, exp(-0.5 * (nis + delta * delta / headingVar)), straight)
        }
        if (candidates.isEmpty()) return null
        val spread = Math.toRadians(constraint.uncertainHeadingSpreadDeg)
        val axes = ArrayList<MutableList<Candidate>>()
        for (c in candidates.sortedBy { it.delta }) {
            val last = axes.lastOrNull()
            if (last != null && c.delta - last.first().delta <= spread) last += c else axes += mutableListOf(c)
        }
        // Use the strongest candidate per axis: duplicated OSM pieces and parallel roads are not new evidence.
        val scores = axes.map { axis -> axis.maxOf { it.score } }
        val best = scores.indices.maxBy { scores[it] }
        if (scores[best] / (scores.sum() + constraint.uncertainHeadingOffRoadScore) < constraint.uncertainHeadingMinSupport) return null
        val axis = axes[best]
        if (axis.any { !it.straight && it.score >= 0.1 * scores[best] }) return null
        return atan2(axis.sumOf { it.score * sin(it.bearing) }, axis.sumOf { it.score * cos(it.bearing) })
    }
}
