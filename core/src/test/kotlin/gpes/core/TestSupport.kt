package gpes.core

import gpes.core.model.LocSource
import gpes.core.model.LocationMeasurement
import gpes.core.model.Measurement
import gpes.core.replay.Metrics
import gpes.core.replay.ReplayResult
import gpes.core.replay.ReplayRunner
import gpes.core.replay.ReplaySummary
import gpes.core.replay.Scenario
import gpes.core.replay.TruthTrack
import gpes.core.replay.Variant
import gpes.core.sim.DriveSimulator
import gpes.core.sim.SimConfig
import gpes.core.sim.SimDrive

object TestSupport {
    val defaultDrive: SimDrive by lazy { DriveSimulator.generate(SimConfig()) }

    fun measurements(d: SimDrive = defaultDrive): List<Measurement> = d.records.filterIsInstance<Measurement>()

    fun truth(d: SimDrive = defaultDrive) = TruthTrack(d.truth)

    fun run(scenario: Scenario, variant: Variant = Variant("phone-only"), d: SimDrive = defaultDrive): Pair<ReplayResult, ReplaySummary> {
        val r = ReplayRunner().run(measurements(d), scenario, variant, truth(d))
        return r to Metrics.summarize(r)
    }

    fun fix(tS: Double, lat: Double, lon: Double, acc: Double = 5.0, speed: Double? = 10.0, bearing: Double? = 90.0, source: LocSource = LocSource.GNSS) =
        LocationMeasurement(
            tNs = (tS * 1e9).toLong(), receivedNs = (tS * 1e9).toLong() + 100_000_000, source = source,
            provider = if (source == LocSource.GNSS) "gps" else source.name.lowercase(),
            lat = lat, lon = lon, hAccM = acc, speedMps = speed, bearingDeg = bearing,
        )
}
