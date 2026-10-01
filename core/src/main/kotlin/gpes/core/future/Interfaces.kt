package gpes.core.future

import gpes.core.estimator.PositionEstimator
import gpes.core.geo.LatLon
import gpes.core.model.RoadState
import gpes.core.pipeline.MeasurementSource

/*
 * Phase 2+ extension points. These are declared now so that Phase 1 code does not paint us into a
 * corner. Nothing in Phase 1 implements them.
 */

/** Any vehicle-speed provider (Bluetooth OBD, CAN, ...). Emits `VehicleSpeedMeasurement`s. */
interface VehicleSpeedSource : MeasurementSource

data class RoadSegment(
    val id: Long,
    val geometry: List<LatLon>,
    val lengthM: Double,
    /** Drivable only from the first to the last point of [geometry]. */
    val oneway: Boolean,
    val roadClass: String,
    /**
     * Segments that can be entered at this segment's end (and at its start, if two-way): those leaving
     * the shared node, or two-way segments arriving at it.
     */
    val successorsFromEnd: List<Long>,
    val successorsFromStart: List<Long>,
    /** Total lanes from OSM, 0 when unknown. */
    val lanes: Int = 0,
    val layer: Int = 0,
    val name: String = "",
    val osmWayId: Long = 0,
    val startNode: Long = 0,
    val endNode: Long = 0,
)

/** Road network (for example from OSM). Used by a road-constrained estimator for candidate generation and propagation. */
interface RoadGraph {
    fun segment(id: Long): RoadSegment?
    fun segmentsWithin(center: LatLon, radiusM: Double): List<RoadSegment>
    fun toLatLon(state: RoadState): LatLon
}

/** A planned manoeuvre, as observed evidence rather than an instruction. */
data class PlannedManeuver(val segmentId: Long, val turnAngleDeg: Double, val distanceFromStartM: Double)

/** A planned route is a prior, not ground truth. The estimator must allow deviation from it. */
interface RoutePrior {
    val segments: List<Long>
    val maneuvers: List<PlannedManeuver>
    /** Prior weight multiplier for a hypothesis on [segmentId] (1.0 = neutral). */
    fun priorWeight(segmentId: Long): Double
}

/**
 * Phase 2 estimator: multiple weighted road-state hypotheses (particle filter or HMM). It produces
 * `PositionEstimate.hypotheses` with `RoadState` set, and uses [RoadGraph] plus an optional [RoutePrior].
 */
interface RoadStateEstimator : PositionEstimator {
    val graph: RoadGraph
    var route: RoutePrior?
}
