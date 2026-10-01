package gpes.core.road

import gpes.core.geo.Geo
import gpes.core.model.Cov2
import gpes.core.model.RoadState
import kotlinx.serialization.Serializable
import java.util.PriorityQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

@Serializable
data class RoadMatcherConfig(
    val enabled: Boolean = true,
    /** One HMM step per this much driven distance (m). */
    val stepM: Double = 10.0,
    val maxCandidates: Int = 12,
    val maxHypotheses: Int = 24,
    /** Candidate search radius: this many pose σ, clamped to [minRadiusM, maxRadiusM]. */
    val radiusSigmas: Double = 3.0,
    val minRadiusM: Double = 30.0,
    val maxRadiusM: Double = 200.0,
    /** OSM centreline position error (m, 1σ). */
    val osmGeometryStdM: Double = 4.0,
    /** Allowance between road bearing and our heading beyond the EKF's own heading std (degrees). */
    val headingStdDeg: Double = 10.0,
    /** Gyro turn vs road bearing change over a step: σ = turnStdDeg + turnStdFrac·|turn|. */
    val turnStdDeg: Double = 15.0,
    val turnStdFrac: Double = 0.15,
    /** |route distance − driven distance| is Laplace with scale β = routeBetaM + routeBetaFrac·step. */
    val routeBetaM: Double = 8.0,
    val routeBetaFrac: Double = 0.15,
    /** Off-road spatial likelihood equals a road candidate this many σ away from the pose. */
    val offRoadSigmas: Double = 2.0,
    /** Off-road path likelihood relative to a perfectly consistent road step (nats). */
    val offRoadPathPenalty: Double = 1.0,
    /** Per-step probability of leaving the road (higher when slow: car parks, yards). */
    val pLeave: Double = 0.02,
    val pLeaveSlow: Double = 0.1,
    val slowMps: Double = 4.2,
    /** Per-step probability of joining a road from off-road. */
    val pEnter: Double = 0.05,
    /**
     * Per-step log-prior against minor roads (nats): most driving is on the main carriageway, and a
     * parallel residential or service carriageway ~20 m away is otherwise indistinguishable (R-020b).
     */
    val minorRoadPenalty: Double = 0.3,
    val serviceRoadPenalty: Double = 0.0,
    /** A road state with at least this probability counts as confident. */
    val confidentProb: Double = 0.9,
    /**
     * Short dips do not reset the confident distance (P1, 2026-09-28 +5:30): for up to this many steps with
     * probability ≥ [dipMinProb] it is kept (not grown), inherited through the HMM transition, e.g. at a
     * corner where the mass briefly splits between streets. 0 = reset on any dip (before 2026-10-02).
     */
    val dipMaxSteps: Int = 0,
    val dipMinProb: Double = 0.5,
    /** Dips are tolerated only around a turn: the gyro turned at least this much over the last 3 steps. */
    val dipNeedsTurnDeg: Double = 0.0,
    /** Steps of road history kept per hypothesis (M4 shape matching needs ~250 m). */
    val trailSteps: Int = 40,
)

/** When and how strongly the EKF uses the matched road (M2 heading, M3 cross-track). */
@Serializable
data class RoadConstraintConfig(
    val heading: Boolean = true,
    val crossTrack: Boolean = true,
    /** The road (this street, this direction) must have at least this probability … */
    val minProbability: Double = 0.9,
    /** … for at least this distance (hysteresis on entering a road) … */
    val minConfidentM: Double = 150.0,
    /** … with the off-road probability below this … */
    val maxPOffRoad: Double = 0.1,
    /** … and the EKF pose σ (largest axis) at most this; a pose far off picks wrong roads (M1, R-020). */
    val maxPoseSigmaM: Double = 50.0,
    val minSpeedMps: Double = 4.2,
    /** Road updates need a known speed (OBD or GNSS): without it the driven distance, hence the match, is unreliable (R-020). */
    val maxSpeedStdMps: Double = 1.5,
    /** At most one road update per this much driving: consecutive road updates are the same evidence (P5). */
    val everyM: Double = 40.0,
    /** Road heading σ: OSM bearing error ⊕ lane changes (degrees). */
    val headingStdDeg: Double = 3.0,
    /** Heading only where the road bearing changes by less than this over ±[straightWindowM]. */
    val straightMaxDeg: Double = 5.0,
    val straightWindowM: Double = 40.0,
    /** … and the gyro turned less than this over the last [gyroWindowSteps] matcher steps. */
    val gyroMaxDeg: Double = 3.0,
    val gyroWindowSteps: Int = 4,
    /** χ² gate (1 dof) on each road update; a rejection is evidence against the match. */
    val gateNis: Double = 9.0,
    /** After this many consecutive cross-track rejections, take the road-free twin's state. */
    val resyncAfterRejects: Int = 2,
    /**
     * Hold a coarse fix that lies more than this far (m) to the side of a confidently matched straight road,
     * until the next fix confirms the offset (same side, beyond [holdConfirmM]); otherwise drop it. Limits a
     * single bad fix that is plausible by its own hAcc (R-020b, 2026-09-28 6:22). null disables.
     */
    val holdOffRoadFixM: Double? = null,
    val holdConfirmM: Double = 60.0,
    /**
     * Corner fix (R-021): right after a completed turn ≥ [cornerMinTurnDeg], align the gyro turn with the
     * road corner on the hypothesis' trail and apply a 2-D position update to the matched road point
     * shifted along the road. It needs a confident road *before* the corner (trail ≥ [cornerTrailM]), not
     * 150 m on the new street, and fixes the along-track error that becomes cross-track after the turn.
     */
    val cornerFix: Boolean = true,
    val cornerMinTurnDeg: Double = 45.0,
    val cornerTrailM: Double = 150.0,
    val cornerMinProbability: Double = 0.9,
    /** χ² gate (2 dof) for the corner fix. */
    val cornerGateNis: Double = 9.21,
    /**
     * Significance: apply the corner fix only if the estimate is more than this many of its own σ from the
     * target road point. When the estimate is already good the fix only adds its own noise (drive B 2:41,
     * 5.5 → 7.7 m). 0 = always.
     */
    val cornerMinSigmas: Double = 2.0,
    /** M4: along-track updates from the path shape (turns, bends). */
    val alongTrack: Boolean = true,
    /** At most one along-track update per this much driving (the window overlaps; P5). */
    val alongEveryM: Double = 150.0,
    /** Smallest persistent road bearing change that M4 uses (degrees). */
    val alongMinTurnDeg: Double = 12.0,
    /** Cross-track updates only this far (+ 2σ of the pose) from the ends of the segment (m). */
    val endMarginM: Double = 15.0,
    val endMarginSigmas: Double = 1.0,
)

/** One HMM step's evidence, from the EKF (pose, heading) and the gyro (turn since the previous step). */
data class RoadStepInput(
    val lat: Double,
    val lon: Double,
    val cov: Cov2,
    val headingRad: Double?,
    val headingStdRad: Double?,
    val distanceM: Double,
    /** Heading change measured by the gyro since the previous step (rad, clockwise positive). */
    val gyroTurnRad: Double,
    val speedMps: Double,
)

/**
 * Online road matcher: a hidden Markov model over road positions (Newson & Krumm 2009, causal forward
 * filtering) with an explicit OFF-ROAD state. Candidates are the projections of the current EKF pose onto
 * nearby segments, per driving direction. Emissions use the EKF covariance across the road, the road
 * width and the heading; transitions compare the route distance with the driven distance and the road's
 * bearing change with the gyro turn. Off-road has no road expectations, so it wins when roads do not
 * explain the motion (car parks, unmapped roads). See docs/road-constraint.md (M1).
 *
 * Deterministic, no wall clock. State is immutable between steps, so [copy] is cheap.
 */
class RoadMatcher(private val cfg: RoadMatcherConfig, private val net: RoadNetwork) {

    val network: RoadNetwork get() = net

    /** A road hypothesis: on [seg] at [d] from its first point, driving forward along it or not. */
    data class Hyp(
        val seg: Long, val d: Double, val fwd: Boolean, val bearingDeg: Double, val logP: Double, val confidentM: Double,
        /** This hypothesis' recent road positions, oldest first (its most likely predecessors), for M4. */
        val trail: List<TrailPt> = emptyList(),
        /** Consecutive steps below [RoadMatcherConfig.confidentProb] with the confident distance kept. */
        val dipSteps: Int = 0,
    )

    data class TrailPt(val seg: Long, val fwd: Boolean, val d: Double)

    private var hyps: List<Hyp> = emptyList()
    private var logOff = ln(0.5)
    private var offConfidentM = 0.0
    private var started = false
    /** Gyro turn of the last 3 steps (rad), for [RoadMatcherConfig.dipNeedsTurnDeg]. */
    private var recentTurn: List<Double> = emptyList()

    val hypotheses: List<Hyp> get() = hyps

    fun copy(): RoadMatcher = RoadMatcher(cfg, net).also {
        it.hyps = hyps; it.logOff = logOff; it.offConfidentM = offConfidentM; it.started = started; it.recentTurn = recentTurn
    }

    fun reset() { hyps = emptyList(); logOff = ln(0.5); offConfidentM = 0.0; started = false; recentTurn = emptyList() }

    fun pOffRoad(): Double = exp(logOff)

    /**
     * The most probable road state, or null before the first step or with no road nearby. Its probability
     * is that of the whole road in this direction (all hypotheses on the same street, travelling within
     * 45° of it), since consecutive segments of one road split the mass at every node.
     */
    fun best(): RoadState? {
        val b = hyps.maxByOrNull { it.logP } ?: return null
        val s = net.segment(b.seg)!!
        return RoadState(b.seg, b.d, b.fwd, roadProbability(b, hyps), exp(logOff), b.confidentM, s.name)
    }

    /** The most probable hypothesis itself (for the EKF road updates). */
    fun bestHyp(): Hyp? = hyps.maxByOrNull { it.logP }

    /** Road probability of [h] (its street, its direction). */
    fun probabilityOf(h: Hyp): Double = roadProbability(h, hyps)

    /**
     * Bearing of the road travelled by [h] (travel direction, unwrapped, rad) against road distance back
     * from its current position, sampled every [stepM], reconstructed from its trail.
     */
    fun roadProfile(h: Hyp, stepM: Double = 2.0): List<AlongTrackMatch.Sample> {
        val t = h.trail
        if (t.size < 2) return emptyList()
        val dist = ArrayList<Double>(); val brg = ArrayList<Double>()
        var acc = 0.0
        fun sample(seg: Long, fwd: Boolean, from: Double, to: Double) {
            val len = abs(to - from)
            val n = maxOf(1, (len / stepM).toInt())
            for (i in 0..n) {
                val d = from + (to - from) * i / n
                if (i > 0) acc += len / n
                val b = net.bearingAt(seg, d.coerceIn(0.0, net.lengthOf(seg)))
                dist += acc; brg += rad(if (fwd) b else b + 180)
            }
        }
        for (k in 1 until t.size) {
            val a = t[k - 1]; val c = t[k]
            if (a.seg == c.seg && a.fwd == c.fwd) sample(c.seg, c.fwd, a.d, c.d)
            else {
                sample(a.seg, a.fwd, a.d, if (a.fwd) net.lengthOf(a.seg) else 0.0)
                sample(c.seg, c.fwd, if (c.fwd) 0.0 else net.lengthOf(c.seg), c.d)
            }
        }
        val un = AlongTrackMatch.unwrap(brg)
        val total = acc
        return dist.indices.reversed().map { AlongTrackMatch.Sample(total - dist[it], un[it]) }.distinctBy { it.back }
    }

    private fun roadKey(h: Hyp): String = net.segment(h.seg)!!.let { if (it.name.isNotEmpty()) "n:" + it.name else "w:" + it.osmWayId }

    private fun roadProbability(h: Hyp, all: List<Hyp>): Double {
        val k = roadKey(h)
        return all.filter { roadKey(it) == k && abs(Geo.wrapDeg(it.bearingDeg - h.bearingDeg)) < 45.0 }.sumOf { exp(it.logP) }
    }

    fun step(u: RoadStepInput) {
        val sMax = sqrt(maxEig(u.cov))
        val radius = (cfg.radiusSigmas * sMax).coerceIn(cfg.minRadiusM, cfg.maxRadiusM)
        val projs = net.project(u.lat, u.lon, radius).take(cfg.maxCandidates)

        // Candidates with their emission log-likelihoods.
        class Cand(val p: RoadProjection, val fwd: Boolean, val bearing: Double, val le: Double)
        val cands = ArrayList<Cand>()
        val headingVar = u.headingStdRad?.let { it * it + rad(cfg.headingStdDeg).let { h -> h * h } }
        for (p in projs) {
            for (fwd in if (p.segment.oneway) listOf(true) else listOf(true, false)) {
                val bearing = if (fwd) p.bearingDeg else (p.bearingDeg + 180) % 360
                val b = rad(p.bearingDeg)
                // Pose variance along the road normal (right of the segment's forward direction).
                val nx = cos(b); val ny = -sin(b)
                val crossVar = nx * nx * u.cov.ee + 2 * nx * ny * u.cov.en + ny * ny * u.cov.nn
                val v = crossVar + roadVar(p.segment)
                var le = -0.5 * p.distanceM * p.distanceM / v - 0.5 * ln(2 * PI * v)
                if (u.headingRad != null && headingVar != null) {
                    val dh = Geo.wrapRad(u.headingRad - rad(bearing))
                    le += -0.5 * dh * dh / headingVar - 0.5 * ln(2 * PI * headingVar)
                }
                le -= when (p.segment.roadClass) {
                    "service" -> cfg.serviceRoadPenalty
                    "residential", "living_street", "unclassified", "road" -> cfg.minorRoadPenalty
                    else -> 0.0
                }
                cands += Cand(p, fwd, bearing, le)
            }
        }
        // Off-road emission: as a road candidate offRoadSigmas σ away; heading uniform.
        val refVar = sMax * sMax + cfg.osmGeometryStdM * cfg.osmGeometryStdM
        var lOff = -0.5 * cfg.offRoadSigmas * cfg.offRoadSigmas - 0.5 * ln(2 * PI * refVar)
        if (u.headingRad != null && headingVar != null) lOff += -ln(2 * PI)

        val pLeave = if (u.speedMps < cfg.slowMps) cfg.pLeaveSlow else cfg.pLeave
        val beta = cfg.routeBetaM + cfg.routeBetaFrac * u.distanceM
        val turnSd = rad(cfg.turnStdDeg) + cfg.turnStdFrac * abs(u.gyroTurnRad)
        // A perfectly consistent road step scores −ln(2β) + turn peak; off-road gets a flat, slightly lower score.
        val roadPeak = -ln(2 * beta) - 0.5 * ln(2 * PI * turnSd * turnSd)
        val offPath = roadPeak - cfg.offRoadPathPenalty

        val prevOff = logOff
        val newHyps = ArrayList<Hyp>()
        if (!started) {
            val prior = ln(0.5) - ln(max(cands.size, 1).toDouble())
            for (c in cands) newHyps += Hyp(c.p.segment.id, c.p.distanceAlongM, c.fwd, c.bearing, prior + c.le, 0.0,
                listOf(TrailPt(c.p.segment.id, c.fwd, c.p.distanceAlongM)))
            logOff = ln(0.5) + lOff
            started = true
        } else {
            val reach = hyps.map { h -> h to reachable(h, 2 * u.distanceM + 60.0) }
            val enterPrior = ln(cfg.pEnter) - ln(max(cands.size, 1).toDouble())
            for (c in cands) {
                var acc = Double.NEGATIVE_INFINITY
                var bestPred: Hyp? = null; var bestPredScore = Double.NEGATIVE_INFINITY
                for ((h, map) in reach) {
                    val r = routeDistance(h, map, c.p.segment.id, c.fwd, c.p.distanceAlongM) ?: continue
                    val roadTurn = Geo.wrapRad(rad(c.bearing - h.bearingDeg))
                    val dt = Geo.wrapRad(u.gyroTurnRad - roadTurn)
                    val t = ln(1 - pLeave) - abs(r - u.distanceM) / beta - ln(2 * beta) -
                        0.5 * dt * dt / (turnSd * turnSd) - 0.5 * ln(2 * PI * turnSd * turnSd)
                    val sc = h.logP + t
                    acc = logAdd(acc, sc)
                    if (sc > bestPredScore) { bestPredScore = sc; bestPred = h }
                }
                val fromOff = prevOff + enterPrior
                acc = logAdd(acc, fromOff)
                if (acc == Double.NEGATIVE_INFINITY) continue
                val lp = acc + c.le
                val pt = TrailPt(c.p.segment.id, c.fwd, c.p.distanceAlongM)
                // A road predecessor only if it beat entering from off-road.
                val pred = bestPred?.takeIf { bestPredScore >= fromOff }
                newHyps += Hyp(c.p.segment.id, c.p.distanceAlongM, c.fwd, c.bearing, lp, pred?.confidentM ?: 0.0,
                    ((pred?.trail ?: emptyList()) + pt).takeLast(cfg.trailSteps), pred?.dipSteps ?: 0)
            }
            val roadMass = hyps.fold(Double.NEGATIVE_INFINITY) { a, h -> logAdd(a, h.logP) }
            logOff = logAdd(prevOff + ln(1 - cfg.pEnter), roadMass + ln(pLeave)) + offPath + lOff
        }

        // Normalise, prune, and update the confident distance.
        var z = logOff
        for (h in newHyps) z = logAdd(z, h.logP)
        logOff -= z
        val kept = newHyps.map { it.copy(logP = it.logP - z) }
            .sortedWith(compareByDescending<Hyp> { it.logP }.thenBy { it.seg }.thenBy { it.fwd })
            .take(cfg.maxHypotheses)
        recentTurn = (recentTurn + u.gyroTurnRad).takeLast(3)
        val turning = Math.toDegrees(abs(recentTurn.sum())) >= cfg.dipNeedsTurnDeg
        hyps = kept.map { h ->
            val p = roadProbability(h, kept)
            when {
                p >= cfg.confidentProb -> h.copy(confidentM = h.confidentM + u.distanceM, dipSteps = 0)
                p >= cfg.dipMinProb && h.confidentM > 0 && h.dipSteps < cfg.dipMaxSteps && turning -> h.copy(dipSteps = h.dipSteps + 1)
                else -> h.copy(confidentM = 0.0, dipSteps = 0)
            }
        }
    }

    /** Road variance across the carriageway: uniform over half the width, plus OSM geometry error. */
    private fun roadVar(s: gpes.core.future.RoadSegment): Double {
        val half = RoadWidth.halfWidthM(s)
        return half * half / 3 + cfg.osmGeometryStdM * cfg.osmGeometryStdM
    }

    // ---- route distances -------------------------------------------------------------------------

    /** Distance from [h] to the entry of each (segment, direction) reachable within [limit] metres. */
    private fun reachable(h: Hyp, limit: Double): Map<Pair<Long, Boolean>, Double> {
        val out = HashMap<Pair<Long, Boolean>, Double>()
        val s = net.segment(h.seg)!!
        val len = net.lengthOf(h.seg)
        val q = PriorityQueue<Triple<Double, Long, Boolean>>(compareBy<Triple<Double, Long, Boolean>> { it.first }.thenBy { it.second })
        fun expandFrom(dist: Double, seg: gpes.core.future.RoadSegment, fwd: Boolean) {
            val node = if (fwd) seg.endNode else seg.startNode
            val succ = if (fwd) seg.successorsFromEnd else seg.successorsFromStart
            for (t in succ) {
                val ts = net.segment(t)!!
                if (ts.startNode == node) q += Triple(dist, t, true)
                if (ts.endNode == node && !ts.oneway) q += Triple(dist, t, false)
            }
        }
        expandFrom(if (h.fwd) len - h.d else h.d, s, h.fwd)
        while (q.isNotEmpty()) {
            val (d, seg, fwd) = q.poll()
            val key = seg to fwd
            if (key in out || d > limit) continue
            out[key] = d
            expandFrom(d + net.lengthOf(seg), net.segment(seg)!!, fwd)
        }
        return out
    }

    private fun routeDistance(h: Hyp, reach: Map<Pair<Long, Boolean>, Double>, seg: Long, fwd: Boolean, d: Double): Double? {
        if (h.seg == seg && h.fwd == fwd) {
            val r = if (fwd) d - h.d else h.d - d
            if (r >= -20.0) return r
        }
        val entry = reach[seg to fwd] ?: return null
        return entry + if (fwd) d else net.lengthOf(seg) - d
    }

    private fun rad(deg: Double) = Math.toRadians(deg)

    private fun maxEig(c: Cov2): Double {
        val m = (c.ee + c.nn) / 2
        val d = sqrt(((c.ee - c.nn) / 2) * ((c.ee - c.nn) / 2) + c.en * c.en)
        return m + d
    }

    private fun logAdd(a: Double, b: Double): Double {
        if (a == Double.NEGATIVE_INFINITY) return b
        if (b == Double.NEGATIVE_INFINITY) return a
        val m = max(a, b)
        return m + ln(exp(a - m) + exp(b - m))
    }
}

/** Carriageway width from OSM `lanes`, or a default per road class (lanes are missing on most service roads). */
object RoadWidth {
    const val LANE_M = 3.5

    fun defaultLanes(roadClass: String): Int = when (roadClass) {
        "motorway", "trunk", "primary" -> 4
        "secondary", "tertiary", "unclassified", "residential", "road" -> 2
        else -> 1 // service, living_street, links
    }

    fun halfWidthM(s: gpes.core.future.RoadSegment): Double {
        val lanes = if (s.lanes > 0) s.lanes else defaultLanes(s.roadClass)
        // A one-way carriageway of a divided road carries its own lanes; a two-way road splits them.
        return lanes * LANE_M / 2
    }
}
