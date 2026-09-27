package gpes.recording

import gpes.core.io.DriveJson
import gpes.core.model.Annotation
import gpes.core.model.DriveRecord
import gpes.core.model.GnssMeasurementBatch
import gpes.core.model.GnssStatusSnapshot
import gpes.core.model.ImuKind
import gpes.core.model.ImuSample
import gpes.core.model.LocationMeasurement
import gpes.core.model.OrientationSample
import gpes.core.model.PositionEstimate
import gpes.core.model.SessionInfo
import gpes.core.model.TrustAssessment
import gpes.core.model.VehicleSpeedMeasurement
import java.io.File
import java.io.Writer
import kotlin.math.asin
import kotlin.math.atan2

object Exporters {
    fun jsonl(records: List<DriveRecord>, out: Writer) = DriveJson.write(records.asSequence(), out)

    /** One CSV per record kind in [dir]. Returns the files written. */
    fun csv(records: List<DriveRecord>, dir: File): List<File> {
        dir.mkdirs()
        val files = ArrayList<File>()
        fun <T : DriveRecord> table(name: String, header: String, items: List<T>, row: (T) -> List<Any?>) {
            if (items.isEmpty()) return
            val f = File(dir, "$name.csv")
            f.bufferedWriter().use { w ->
                w.write(header); w.write("\n")
                for (it in items) { w.write(row(it).joinToString(",") { v -> fmt(v) }); w.write("\n") }
            }
            files += f
        }
        table("location", "t_ns,recv_ns,source,provider,lat,lon,alt_m,h_acc_m,v_acc_m,speed_mps,speed_acc_mps,bearing_deg,bearing_acc_deg,wall_time_ms,is_mock,sats_used",
            records.filterIsInstance<LocationMeasurement>()) {
            listOf(it.tNs, it.receivedNs, it.source, it.provider, it.lat, it.lon, it.altM, it.hAccM, it.vAccM, it.speedMps, it.speedAccMps, it.bearingDeg, it.bearingAccDeg, it.wallTimeMs, it.isMock, it.satsUsed)
        }
        table("imu", "t_ns,kind,x,y,z,bx,by,bz,accuracy", records.filterIsInstance<ImuSample>()) {
            listOf(it.tNs, it.kind, it.x, it.y, it.z, it.bx, it.by, it.bz, it.accuracy)
        }
        table("orientation", "t_ns,kind,qw,qx,qy,qz,heading_acc_rad", records.filterIsInstance<OrientationSample>()) {
            listOf(it.tNs, it.kind, it.qw, it.qx, it.qy, it.qz, it.headingAccRad)
        }
        val status = records.filterIsInstance<GnssStatusSnapshot>()
        if (status.isNotEmpty()) {
            val f = File(dir, "gnss_sat.csv")
            f.bufferedWriter().use { w ->
                w.write("t_ns,svid,constellation,cn0_dbhz,elev_deg,az_deg,used_in_fix,carrier_hz\n")
                for (s in status) for (sat in s.sats) {
                    w.write(listOf(s.tNs, sat.svid, sat.constellation, sat.cn0DbHz, sat.elevDeg, sat.azDeg, sat.usedInFix, sat.carrierHz).joinToString(",") { fmt(it) })
                    w.write("\n")
                }
            }
            files += f
        }
        table("vehicle_speed", "t_ns,speed_mps,std_mps,source", records.filterIsInstance<VehicleSpeedMeasurement>()) { listOf(it.tNs, it.speedMps, it.stdMps, it.source) }
        table("annotation", "t_ns,label,note", records.filterIsInstance<Annotation>()) { listOf(it.tNs, it.label, it.note) }
        table("trust", "t_ns,source,provider,state,confidence,reasons,innovation_nis,implied_speed_mps", records.filterIsInstance<TrustAssessment>()) {
            listOf(it.tNs, it.source, it.provider, it.state, it.confidence, it.reasons.joinToString("|"), it.innovationNis, it.impliedSpeedMps)
        }
        table("estimate", "t_ns,estimator,lat,lon,accuracy_m,cov_ee,cov_en,cov_nn,heading_deg,heading_std_deg,speed_mps,speed_std_mps,mode,confidence",
            records.filterIsInstance<PositionEstimate>()) {
            listOf(
                it.tNs, it.estimator, it.lat, it.lon, it.accuracyM, it.cov.ee, it.cov.en, it.cov.nn,
                it.headingRad?.let(Math::toDegrees), it.headingStdRad?.let(Math::toDegrees), it.speedMps, it.speedStdMps, it.mode, it.confidence,
            )
        }
        return files
    }

    /**
     * Best-effort export in Google GnssLogger text format, so gps-measurement-tools and Smartphone
     * Decimeter Challenge tooling can read our drives. Fields we don't have are left empty.
     */
    fun gnssLogger(records: List<DriveRecord>, out: Writer) {
        val session = records.filterIsInstance<SessionInfo>().firstOrNull()
        fun utc(tNs: Long): String = session?.wallMs(tNs)?.toString() ?: ""
        out.write("# \n# Header Description:\n# \n# Version: gpes-patron export (GnssLogger-compatible subset)\n# \n")
        out.write("# Raw,utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,BiasNanos,BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond,HardwareClockDiscontinuityCount,Svid,TimeOffsetNanos,State,ReceivedSvTimeNanos,ReceivedSvTimeUncertaintyNanos,Cn0DbHz,PseudorangeRateMetersPerSecond,PseudorangeRateUncertaintyMetersPerSecond,AccumulatedDeltaRangeState,AccumulatedDeltaRangeMeters,AccumulatedDeltaRangeUncertaintyMeters,CarrierFrequencyHz,CarrierCycles,CarrierPhase,CarrierPhaseUncertainty,MultipathIndicator,SnrInDb,ConstellationType,AgcDb,BasebandCn0DbHz,FullInterSignalBiasNanos,FullInterSignalBiasUncertaintyNanos,SatelliteInterSignalBiasNanos,SatelliteInterSignalBiasUncertaintyNanos,CodeType,ChipsetElapsedRealtimeNanos\n")
        out.write("# Fix,Provider,LatitudeDegrees,LongitudeDegrees,AltitudeMeters,SpeedMps,AccuracyMeters,BearingDegrees,UnixTimeMillis,SpeedAccuracyMps,BearingAccuracyDegrees,elapsedRealtimeNanos,VerticalAccuracyMeters,MockLocation\n")
        out.write("# Status,UnixTimeMillis,SignalCount,SignalIndex,ConstellationType,Svid,CarrierFrequencyHz,Cn0DbHz,AzimuthDegrees,ElevationDegrees,UsedInFix,HasAlmanacData,HasEphemerisData,BasebandCn0DbHz\n")
        out.write("# UncalAccel,utcTimeMillis,elapsedRealtimeNanos,UncalAccelXMps2,UncalAccelYMps2,UncalAccelZMps2,BiasXMps2,BiasYMps2,BiasZMps2\n")
        out.write("# UncalGyro,utcTimeMillis,elapsedRealtimeNanos,UncalGyroXRadPerSec,UncalGyroYRadPerSec,UncalGyroZRadPerSec,DriftXRadPerSec,DriftYRadPerSec,DriftZRadPerSec\n")
        out.write("# UncalMag,utcTimeMillis,elapsedRealtimeNanos,UncalMagXMicroT,UncalMagYMicroT,UncalMagZMicroT,BiasXMicroT,BiasYMicroT,BiasZMicroT\n")
        out.write("# OrientationDeg,utcTimeMillis,elapsedRealtimeNanos,yawDeg,rollDeg,pitchDeg\n")
        out.write("# Agc,utcTimeMillis,TimeNanos,LeapSecond,TimeUncertaintyNanos,FullBiasNanos,BiasNanos,BiasUncertaintyNanos,DriftNanosPerSecond,DriftUncertaintyNanosPerSecond,HardwareClockDiscontinuityCount,AgcDb,CarrierFrequencyHz,ConstellationType\n")
        out.write("# \n")
        fun line(vararg v: Any?) { out.write(v.joinToString(",") { fmt(it) }); out.write("\n") }
        for (r in records) when (r) {
            is GnssMeasurementBatch -> {
                val c = r.clock
                val clk = arrayOf<Any?>(c.timeNanos, c.leapSecond, c.timeUncNanos, c.fullBiasNanos, c.biasNanos, c.biasUncNanos, c.driftNsps, c.driftUncNsps, c.hwClockDiscontinuityCount)
                for (m in r.meas) line(
                    "Raw", utc(r.tNs), *clk, m.svid, m.timeOffsetNanos, m.state, m.receivedSvTimeNanos, m.receivedSvTimeUncNanos, m.cn0DbHz,
                    m.pseudorangeRateMps, m.pseudorangeRateUncMps, m.adrState, m.adrM, m.adrUncM, m.carrierHz, null, null, null, m.multipath,
                    m.snrDb, m.constellation, m.agcDb, m.basebandCn0DbHz, null, null, null, null, m.codeType, r.tNs,
                )
                for (a in r.agc) line("Agc", utc(r.tNs), *clk, a.levelDb, a.carrierHz, a.constellation)
            }
            is LocationMeasurement -> line(
                "Fix", r.provider.uppercase(), r.lat, r.lon, r.altM, r.speedMps, r.hAccM, r.bearingDeg, r.wallTimeMs,
                r.speedAccMps, r.bearingAccDeg, r.tNs, r.vAccM, if (r.isMock) 1 else 0,
            )
            is GnssStatusSnapshot -> r.sats.forEachIndexed { i, s ->
                line("Status", utc(r.tNs), r.sats.size, i, s.constellation, s.svid, s.carrierHz, s.cn0DbHz, s.azDeg, s.elevDeg, if (s.usedInFix) 1 else 0, if (s.hasAlmanac) 1 else 0, if (s.hasEphemeris) 1 else 0, s.basebandCn0DbHz)
            }
            is ImuSample -> {
                val tag = when (r.kind) {
                    ImuKind.ACCEL_UNCAL -> "UncalAccel"
                    ImuKind.GYRO_UNCAL -> "UncalGyro"
                    ImuKind.MAG_UNCAL -> "UncalMag"
                    else -> null
                }
                if (tag != null) line(tag, utc(r.tNs), r.tNs, r.x, r.y, r.z, r.bx ?: 0.0, r.by ?: 0.0, r.bz ?: 0.0)
            }
            is OrientationSample -> {
                val (yaw, roll, pitch) = eulerDeg(r)
                line("OrientationDeg", utc(r.tNs), r.tNs, yaw, roll, pitch)
            }
            else -> Unit
        }
        out.flush()
    }

    /** Yaw/roll/pitch like `SensorManager.getOrientation` (yaw = azimuth, degrees). */
    private fun eulerDeg(o: OrientationSample): Triple<Double, Double, Double> {
        val (w, x, y, z) = listOf(o.qw, o.qx, o.qy, o.qz)
        val r01 = 2 * (x * y - w * z)
        val r11 = 1 - 2 * (x * x + z * z)
        val r20 = 2 * (x * z - w * y)
        val r21 = 2 * (y * z + w * x)
        val r22 = 1 - 2 * (x * x + y * y)
        val azimuth = atan2(r01, r11)
        val pitch = asin((-r21).coerceIn(-1.0, 1.0))
        val roll = atan2(-r20, r22)
        return Triple(Math.toDegrees(azimuth), Math.toDegrees(roll), Math.toDegrees(pitch))
    }

    private fun fmt(v: Any?): String = when (v) {
        null -> ""
        is Boolean -> if (v) "1" else "0"
        is String -> if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"" + v.replace("\"", "\"\"") + "\"" else v
        else -> v.toString()
    }
}
