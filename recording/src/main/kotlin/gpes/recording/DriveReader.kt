package gpes.recording

import gpes.core.io.DriveJson
import gpes.core.model.AgcInfo
import gpes.core.model.Annotation
import gpes.core.model.Cov2
import gpes.core.model.DriveRecord
import gpes.core.model.EstimatorMode
import gpes.core.model.GnssClockInfo
import gpes.core.model.GnssMeasurementBatch
import gpes.core.model.GnssRawMeas
import gpes.core.model.GnssStatusSnapshot
import gpes.core.model.Hypothesis
import gpes.core.model.ImuKind
import gpes.core.model.ImuSample
import gpes.core.model.LocSource
import gpes.core.model.LocationMeasurement
import gpes.core.model.Measurement
import gpes.core.model.NmeaSentence
import gpes.core.model.OrientationKind
import gpes.core.model.OrientationSample
import gpes.core.model.PositionEstimate
import gpes.core.model.ProviderEvent
import gpes.core.model.RecordOrder
import gpes.core.model.SatInfo
import gpes.core.model.SensorInfo
import gpes.core.model.SessionInfo
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustReason
import gpes.core.model.TrustState
import gpes.core.model.VehicleSpeedMeasurement
import gpes.recording.db.DriveDatabase
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Reads a drive bundle back into project-owned models. */
class DriveReader(db: DriveDatabase) {
    private val q = db.driveQueries

    fun schemaVersion(): Long? = q.selectVersion().executeAsOneOrNull()

    fun session(): SessionInfo? = q.selectSession().executeAsList().firstOrNull()?.let {
        SessionInfo(
            it.anchor_elapsed_ns, it.session_id, it.anchor_elapsed_ns, it.anchor_wall_ms, it.device, it.manufacturer, it.model,
            it.android_sdk.toInt(), it.app_version, it.mode, it.notes, it.config_json,
        )
    }

    fun sensorInfo(): List<SensorInfo> = q.selectSensorInfo { t, type, st, name, vendor, ver, res, max, minD, maxD, fifo, pwr ->
        SensorInfo(t, type.toInt(), st, name, vendor, ver.toInt(), res, max, minD.toInt(), maxD.toInt(), fifo.toInt(), pwr)
    }.executeAsList()

    fun locations(): List<LocationMeasurement> = q.selectLocation { t, recv, src, prov, lat, lon, alt, hAcc, vAcc, spd, spdAcc, brg, brgAcc, wall, mock, sats, extras ->
        LocationMeasurement(
            t, recv, LocSource.valueOf(src), prov, lat, lon, alt, hAcc, vAcc, spd, spdAcc, brg, brgAcc, wall, mock != 0L, sats?.toInt(),
            extras?.let { DriveJson.json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it) } ?: emptyMap(),
        )
    }.executeAsList()

    fun imu(): List<ImuSample> = q.selectImu { t, kind, x, y, z, bx, by, bz, acc ->
        ImuSample(t, ImuKind.valueOf(kind), x, y, z, bx, by, bz, acc.toInt())
    }.executeAsList()

    fun orientation(): List<OrientationSample> = q.selectOrientation { t, kind, w, x, y, z, hAcc ->
        OrientationSample(t, OrientationKind.valueOf(kind), w, x, y, z, hAcc)
    }.executeAsList()

    fun gnssStatus(): List<GnssStatusSnapshot> {
        val sats = q.selectGnssSat { t, svid, c, cn0, el, az, used, eph, alm, carr, bb ->
            t to SatInfo(svid.toInt(), c.toInt(), cn0, el, az, used != 0L, eph != 0L, alm != 0L, carr, bb)
        }.executeAsList().groupBy({ it.first }, { it.second })
        return q.selectGnssStatus().executeAsList().map { GnssStatusSnapshot(it.t_ns, sats[it.t_ns] ?: emptyList()) }
    }

    fun gnssMeasurements(): List<GnssMeasurementBatch> {
        val meas = q.selectGnssMeas { t, svid, c, off, st, rx, rxUnc, cn0, prr, prrUnc, adrSt, adr, adrUnc, carr, mp, snr, agc, bb, code ->
            t to GnssRawMeas(svid.toInt(), c.toInt(), off, st.toInt(), rx, rxUnc, cn0, prr, prrUnc, adrSt.toInt(), adr, adrUnc, carr, mp.toInt(), snr, agc, bb, code)
        }.executeAsList().groupBy({ it.first }, { it.second })
        val agc = q.selectGnssAgc { t, c, carr, lvl -> t to AgcInfo(c.toInt(), carr, lvl) }
            .executeAsList().groupBy({ it.first }, { it.second })
        return q.selectGnssClock().executeAsList().map {
            GnssMeasurementBatch(
                it.t_ns,
                GnssClockInfo(it.time_nanos, it.leap_second?.toInt(), it.time_unc_nanos, it.full_bias_nanos, it.bias_nanos, it.bias_unc_nanos, it.drift_nsps, it.drift_unc_nsps, it.hw_clock_discontinuity_count.toInt()),
                meas[it.t_ns] ?: emptyList(), agc[it.t_ns] ?: emptyList(),
            )
        }
    }

    fun trust(): List<TrustAssessment> = q.selectTrust { t, src, prov, st, conf, reasons, nis, implied ->
        TrustAssessment(
            t, LocSource.valueOf(src), prov, TrustState.valueOf(st), conf,
            reasons.split(',').filter { it.isNotBlank() }.map { TrustReason.valueOf(it) }.toSet(), nis, implied,
        )
    }.executeAsList()

    fun estimates(): List<PositionEstimate> = q.selectEstimate { t, est, lat, lon, ee, en, nn, _, h, hStd, s, sStd, mode, conf, hyp ->
        val cov = Cov2(ee, en, nn)
        PositionEstimate(
            t, est, lat, lon, cov, h, hStd, s, sStd, EstimatorMode.valueOf(mode), conf,
            hyp?.let { DriveJson.json.decodeFromString(ListSerializer(Hypothesis.serializer()), it) } ?: listOf(Hypothesis(1.0, lat, lon, cov)),
        )
    }.executeAsList()

    /** All pipeline inputs, merged and sorted by time (stable within equal timestamps). */
    fun measurements(): List<Measurement> {
        val all = ArrayList<Measurement>()
        all += imu(); all += orientation(); all += locations(); all += gnssStatus(); all += gnssMeasurements()
        all += q.selectProviderEvent { t, p, e -> ProviderEvent(t, p, ProviderEvent.Kind.valueOf(e)) }.executeAsList()
        all += q.selectNmea { t, text -> NmeaSentence(t, text) }.executeAsList()
        all += q.selectVehicleSpeed { t, s, std, src -> VehicleSpeedMeasurement(t, s, std, src) }.executeAsList()
        all += q.selectAnnotation { t, l, n -> Annotation(t, l, n) }.executeAsList()
        all.sortWith(RecordOrder.comparator)
        return all
    }

    /** Everything, sorted: session, sensor info, measurements, trust and estimates. */
    fun allRecords(): List<DriveRecord> {
        val head = ArrayList<DriveRecord>()
        session()?.let { head += it }
        head += sensorInfo()
        val body = ArrayList<DriveRecord>()
        body += measurements(); body += trust(); body += estimates()
        body.sortWith(RecordOrder.comparator)
        return head + body
    }
}
