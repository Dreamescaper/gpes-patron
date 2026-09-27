package gpes.recording

import gpes.core.io.DriveJson
import gpes.core.model.Annotation
import gpes.core.model.CellScan
import gpes.core.model.GeomagneticReference
import gpes.recording.db.Geomag
import gpes.recording.db.Power
import gpes.core.model.PowerState
import gpes.core.model.WifiScan
import gpes.recording.db.Cell
import gpes.recording.db.Cell_scan
import gpes.recording.db.Wifi_ap
import gpes.recording.db.Wifi_scan
import gpes.core.model.DriveRecord
import gpes.core.model.GnssMeasurementBatch
import gpes.core.model.GnssStatusSnapshot
import gpes.core.model.ImuSample
import gpes.core.model.LocationMeasurement
import gpes.core.model.NmeaSentence
import gpes.core.model.OrientationSample
import gpes.core.model.PositionEstimate
import gpes.core.model.ProviderEvent
import gpes.core.model.SensorInfo
import gpes.core.model.SessionInfo
import gpes.core.model.TrustAssessment
import gpes.core.model.VehicleSpeedMeasurement
import gpes.recording.db.DriveDatabase
import gpes.recording.db.Estimate
import gpes.recording.db.Gnss_agc
import gpes.recording.db.Gnss_clock
import gpes.recording.db.Gnss_meas
import gpes.recording.db.Gnss_sat
import gpes.recording.db.Gnss_status
import gpes.recording.db.Imu
import gpes.recording.db.Location
import gpes.recording.db.Nmea
import gpes.recording.db.Orientation
import gpes.recording.db.Provider_event
import gpes.recording.db.Sensor_info
import gpes.recording.db.Session
import gpes.recording.db.Trust
import gpes.recording.db.Vehicle_speed
import kotlinx.serialization.builtins.ListSerializer
import gpes.core.model.Hypothesis
import gpes.recording.db.Annotation_

const val DRIVE_SCHEMA_VERSION = 1L

/**
 * Buffered, thread-safe writer for a drive bundle. Producers call [write] from any thread. One
 * consumer calls [flush] periodically (about every 500 ms), which writes the whole buffer in a
 * single transaction.
 */
class DriveWriter(private val db: DriveDatabase) {
    private val lock = Any()
    private var buffer = ArrayList<DriveRecord>(8192)
    private val counts = HashMap<String, Long>()

    init {
        val q = db.driveQueries
        if (q.selectVersion().executeAsOneOrNull() == null) q.insertVersion(DRIVE_SCHEMA_VERSION)
    }

    fun write(r: DriveRecord) {
        synchronized(lock) { buffer.add(r) }
    }

    /** Record counts by type written so far (for UI/diagnostics). */
    fun counts(): Map<String, Long> = synchronized(counts) { HashMap(counts) }

    fun flush(): Int {
        val batch: List<DriveRecord>
        synchronized(lock) {
            if (buffer.isEmpty()) return 0
            batch = buffer
            buffer = ArrayList(8192)
        }
        db.transaction { batch.forEach(::insert) }
        synchronized(counts) { batch.forEach { counts.merge(it::class.simpleName ?: "?", 1L, Long::plus) } }
        return batch.size
    }

    private fun Boolean.l() = if (this) 1L else 0L

    private fun insert(r: DriveRecord) {
        val q = db.driveQueries
        when (r) {
            is SessionInfo -> q.insertSession(
                Session(r.sessionId, r.anchorElapsedNs, r.anchorWallMs, r.device, r.manufacturer, r.model, r.androidSdk.toLong(), r.appVersion, r.mode, r.notes, r.configJson),
            )
            is SensorInfo -> q.insertSensorInfo(
                Sensor_info(r.tNs, r.type.toLong(), r.stringType, r.name, r.vendor, r.version.toLong(), r.resolution, r.maxRange, r.minDelayUs.toLong(), r.maxDelayUs.toLong(), r.fifoMaxEvents.toLong(), r.powerMa),
            )
            is LocationMeasurement -> q.insertLocation(
                Location(
                    r.tNs, r.receivedNs, r.source.name, r.provider, r.lat, r.lon, r.altM, r.hAccM, r.vAccM, r.speedMps, r.speedAccMps,
                    r.bearingDeg, r.bearingAccDeg, r.wallTimeMs, r.isMock.l(), r.satsUsed?.toLong(),
                    if (r.extras.isEmpty()) null else DriveJson.json.encodeToString(r.extras),
                ),
            )
            is ProviderEvent -> q.insertProviderEvent(Provider_event(r.tNs, r.provider, r.event.name))
            is ImuSample -> q.insertImu(Imu(r.tNs, r.kind.name, r.x, r.y, r.z, r.bx, r.by, r.bz, r.accuracy.toLong()))
            is OrientationSample -> q.insertOrientation(Orientation(r.tNs, r.kind.name, r.qw, r.qx, r.qy, r.qz, r.headingAccRad))
            is GnssStatusSnapshot -> {
                q.insertGnssStatus(Gnss_status(r.tNs, r.sats.size.toLong()))
                for (s in r.sats) q.insertGnssSat(
                    Gnss_sat(r.tNs, s.svid.toLong(), s.constellation.toLong(), s.cn0DbHz, s.elevDeg, s.azDeg, s.usedInFix.l(), s.hasEphemeris.l(), s.hasAlmanac.l(), s.carrierHz, s.basebandCn0DbHz),
                )
            }
            is GnssMeasurementBatch -> {
                val c = r.clock
                q.insertGnssClock(
                    Gnss_clock(r.tNs, c.timeNanos, c.leapSecond?.toLong(), c.timeUncNanos, c.fullBiasNanos, c.biasNanos, c.biasUncNanos, c.driftNsps, c.driftUncNsps, c.hwClockDiscontinuityCount.toLong()),
                )
                for (m in r.meas) q.insertGnssMeas(
                    Gnss_meas(
                        r.tNs, m.svid.toLong(), m.constellation.toLong(), m.timeOffsetNanos, m.state.toLong(), m.receivedSvTimeNanos, m.receivedSvTimeUncNanos,
                        m.cn0DbHz, m.pseudorangeRateMps, m.pseudorangeRateUncMps, m.adrState.toLong(), m.adrM, m.adrUncM, m.carrierHz,
                        m.multipath.toLong(), m.snrDb, m.agcDb, m.basebandCn0DbHz, m.codeType,
                    ),
                )
                for (a in r.agc) q.insertGnssAgc(Gnss_agc(r.tNs, a.constellation.toLong(), a.carrierHz, a.levelDb))
            }
            is NmeaSentence -> q.insertNmea(Nmea(r.tNs, r.text))
            is GeomagneticReference -> q.insertGeomag(Geomag(r.tNs, r.lat, r.lon, r.declinationDeg, r.inclinationDeg, r.fieldUt, r.source))
            is PowerState -> q.insertPower(Power(r.tNs, r.plug, r.charging.l(), r.currentUa, r.voltageMv?.toLong(), r.levelPct?.toLong(), r.temperatureC))
            is CellScan -> {
                q.insertCellScan(Cell_scan(r.tNs, r.cells.size.toLong()))
                for (c in r.cells) q.insertCell(
                    Cell(
                        r.tNs, c.rat, c.registered.l(), c.mcc?.toLong(), c.mnc?.toLong(), c.area, c.cid, c.pci?.toLong(), c.arfcn?.toLong(),
                        c.bandwidthKhz?.toLong(), c.rssiDbm?.toLong(), c.rsrpDbm?.toLong(), c.rsrqDb?.toLong(), c.sinrDb?.toLong(),
                        c.timingAdvance?.toLong(), c.asuLevel?.toLong(), c.measuredNs, c.connectionStatus?.toLong(),
                    ),
                )
            }
            is WifiScan -> {
                q.insertWifiScan(Wifi_scan(r.tNs, r.aps.size.toLong()))
                for (a in r.aps) q.insertWifiAp(Wifi_ap(r.tNs, a.bssid, a.rssiDbm.toLong(), a.freqMhz.toLong(), a.channelWidth?.toLong(), a.seenNs, a.standard?.toLong()))
            }
            is VehicleSpeedMeasurement -> q.insertVehicleSpeed(Vehicle_speed(r.tNs, r.speedMps, r.stdMps, r.source))
            is Annotation -> q.insertAnnotation(Annotation_(r.tNs, r.label, r.note))
            is TrustAssessment -> q.insertTrust(
                Trust(r.tNs, r.source.name, r.provider, r.state.name, r.confidence, r.reasons.joinToString(",") { it.name }, r.innovationNis, r.impliedSpeedMps),
            )
            is PositionEstimate -> q.insertEstimate(
                Estimate(
                    r.tNs, r.estimator, r.lat, r.lon, r.cov.ee, r.cov.en, r.cov.nn, r.accuracyM, r.headingRad, r.headingStdRad,
                    r.speedMps, r.speedStdMps, r.mode.name, r.confidence,
                    if (r.hypotheses.size <= 1) null else DriveJson.json.encodeToString(ListSerializer(Hypothesis.serializer()), r.hypotheses),
                ),
            )
        }
    }
}
