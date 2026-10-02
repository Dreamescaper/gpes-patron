package gpes.app.service

import gpes.core.model.LocSource
import gpes.core.model.LocationMeasurement
import gpes.core.model.PositionEstimate
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustState
import gpes.core.road.RoadNetwork
import gpes.core.geo.Geo

data class MapPoint(val lat: Double, val lon: Double)

/** A real (not our own mock) GNSS or network fix, with the trust verdict once known. */
data class MapFix(val tNs: Long, val source: LocSource, val lat: Double, val lon: Double, val state: TrustState?)

/** Immutable copy for the UI, published with the 1 Hz status. */
data class MapSnapshot(val trail: List<MapPoint>, val fixes: List<MapFix>, val network: RoadNetwork?)

/**
 * What the map shows: our estimate's recent track, and the real GNSS and network fixes coloured by trust, so a lying
 * GPS is visible next to where we think the car is. Written on the pipeline thread, read through [snapshot].
 */
class MapTrack(private val maxTrail: Int = 1200, private val maxFixes: Int = 300) {
    private val trail = ArrayDeque<MapPoint>()
    private val fixes = ArrayDeque<MapFix>()

    @Synchronized
    fun addEstimate(e: PositionEstimate) {
        val last = trail.lastOrNull()
        // A parked car would otherwise fill the trail with one point.
        if (last != null && Geo.haversineM(last.lat, last.lon, e.lat, e.lon) < MIN_STEP_M) return
        trail.addLast(MapPoint(e.lat, e.lon))
        while (trail.size > maxTrail) trail.removeFirst()
    }

    @Synchronized
    fun addFix(m: LocationMeasurement) {
        if (m.isSynthetic || (m.source != LocSource.GNSS && m.source != LocSource.NETWORK)) return
        fixes.addLast(MapFix(m.tNs, m.source, m.lat, m.lon, null))
        while (fixes.size > maxFixes) fixes.removeFirst()
    }

    /** The verdict arrives after the fix; match it to the fix of the same time and source. */
    @Synchronized
    fun setTrust(a: TrustAssessment) {
        for (i in fixes.indices.reversed().take(RECENT)) {
            val f = fixes[i]
            if (f.tNs == a.tNs && f.source == a.source) { fixes[i] = f.copy(state = a.state); return }
        }
    }

    @Synchronized
    fun snapshot(network: RoadNetwork?) = MapSnapshot(trail.toList(), fixes.toList(), network)

    private companion object {
        const val MIN_STEP_M = 3.0
        const val RECENT = 40
    }
}
