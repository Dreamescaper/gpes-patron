package gpes.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything that can appear in a recorded drive. All times are `tNs`: nanoseconds in the Android
 * `elapsedRealtimeNanos` timebase (monotonic, includes deep sleep). `SensorEvent.timestamp` and
 * `Location.elapsedRealtimeNanos` share this base on API 29+ devices; [SessionInfo] stores one
 * (elapsedNs, wallMs) anchor so wall-clock time can be reconstructed.
 *
 * These are project-owned models: nothing here depends on Android, so recorded drives replay
 * through the same estimator on a desktop JVM.
 */
@Serializable
sealed interface DriveRecord {
    val tNs: Long
}

/** An input to the estimation pipeline. Outputs (trust, estimates) are not measurements. */
@Serializable
sealed interface Measurement : DriveRecord

// ---------------------------------------------------------------------------------------------
// Location
// ---------------------------------------------------------------------------------------------

@Serializable
enum class LocSource { GNSS, FUSED, NETWORK, PASSIVE, OTHER }

/**
 * A single location fix from any provider. GNSS fixes are *untrusted* measurements: they may be
 * correct, stale, inaccurate, jammed or spoofed. `hAccM` follows Android semantics (radius of 68%
 * confidence).
 */
@Serializable
@SerialName("location")
data class LocationMeasurement(
    override val tNs: Long,
    /** When the app received the fix (elapsedRealtimeNanos). `receivedNs - tNs` is the latency. */
    val receivedNs: Long,
    val source: LocSource,
    val provider: String,
    val lat: Double,
    val lon: Double,
    val altM: Double? = null,
    val hAccM: Double? = null,
    val vAccM: Double? = null,
    val speedMps: Double? = null,
    val speedAccMps: Double? = null,
    val bearingDeg: Double? = null,
    val bearingAccDeg: Double? = null,
    val wallTimeMs: Long = 0,
    val isMock: Boolean = false,
    val satsUsed: Int? = null,
    val extras: Map<String, String> = emptyMap(),
) : Measurement {
    val isSynthetic: Boolean get() = isMock || extras[SYNTHETIC_EXTRA] == "1"

    companion object {
        /** Tag set on every location this app publishes as mock output. Input rejects it. */
        const val SYNTHETIC_EXTRA = "gpes.synthetic"
    }
}

/** Provider lifecycle events: enable/disable, and override by our own mock output. */
@Serializable
@SerialName("provider")
data class ProviderEvent(
    override val tNs: Long,
    val provider: String,
    val event: Kind,
) : Measurement {
    @Serializable
    enum class Kind { ENABLED, DISABLED, OVERRIDDEN, RESTORED }
}

// ---------------------------------------------------------------------------------------------
// Inertial / orientation
// ---------------------------------------------------------------------------------------------

@Serializable
enum class ImuKind { ACCEL, GYRO, MAG, ACCEL_UNCAL, GYRO_UNCAL, MAG_UNCAL, GRAVITY, LINEAR_ACCEL }

/**
 * A three-axis sensor sample in the phone frame. Units: m/s² for accelerometers, rad/s for gyros
 * and µT for magnetometers. Bias fields are only set for *_UNCAL kinds.
 */
@Serializable
@SerialName("imu")
data class ImuSample(
    override val tNs: Long,
    val kind: ImuKind,
    val x: Double,
    val y: Double,
    val z: Double,
    val bx: Double? = null,
    val by: Double? = null,
    val bz: Double? = null,
    val accuracy: Int = 0,
) : Measurement

@Serializable
enum class OrientationKind { ROTATION_VECTOR, GAME_ROTATION_VECTOR, GEOMAG_ROTATION_VECTOR }

/** Android rotation-vector sensors, stored as a unit quaternion (phone → world ENU). */
@Serializable
@SerialName("orientation")
data class OrientationSample(
    override val tNs: Long,
    val kind: OrientationKind,
    val qw: Double,
    val qx: Double,
    val qy: Double,
    val qz: Double,
    val headingAccRad: Double? = null,
) : Measurement

// ---------------------------------------------------------------------------------------------
// Raw GNSS
// ---------------------------------------------------------------------------------------------

@Serializable
data class SatInfo(
    val svid: Int,
    /** android.location.GnssStatus.CONSTELLATION_* */
    val constellation: Int,
    val cn0DbHz: Double,
    val elevDeg: Double,
    val azDeg: Double,
    val usedInFix: Boolean,
    val hasEphemeris: Boolean = false,
    val hasAlmanac: Boolean = false,
    val carrierHz: Double? = null,
    val basebandCn0DbHz: Double? = null,
)

@Serializable
@SerialName("gnss_status")
data class GnssStatusSnapshot(
    override val tNs: Long,
    val sats: List<SatInfo>,
) : Measurement {
    val usedCount: Int get() = sats.count { it.usedInFix }
}

@Serializable
data class GnssClockInfo(
    val timeNanos: Long,
    val leapSecond: Int? = null,
    val timeUncNanos: Double? = null,
    val fullBiasNanos: Long? = null,
    val biasNanos: Double? = null,
    val biasUncNanos: Double? = null,
    val driftNsps: Double? = null,
    val driftUncNsps: Double? = null,
    val hwClockDiscontinuityCount: Int = 0,
)

@Serializable
data class GnssRawMeas(
    val svid: Int,
    val constellation: Int,
    val timeOffsetNanos: Double,
    val state: Int,
    val receivedSvTimeNanos: Long,
    val receivedSvTimeUncNanos: Long,
    val cn0DbHz: Double,
    val pseudorangeRateMps: Double,
    val pseudorangeRateUncMps: Double,
    val adrState: Int,
    val adrM: Double,
    val adrUncM: Double,
    val carrierHz: Double? = null,
    val multipath: Int = 0,
    val snrDb: Double? = null,
    val agcDb: Double? = null,
    val basebandCn0DbHz: Double? = null,
    val codeType: String? = null,
)

/** Automatic gain control per band (API 33+). A sudden AGC drop is a classic jamming indicator. */
@Serializable
data class AgcInfo(
    val constellation: Int,
    val carrierHz: Double,
    val levelDb: Double,
)

@Serializable
@SerialName("gnss_meas")
data class GnssMeasurementBatch(
    override val tNs: Long,
    val clock: GnssClockInfo,
    val meas: List<GnssRawMeas>,
    val agc: List<AgcInfo> = emptyList(),
) : Measurement

@Serializable
@SerialName("nmea")
data class NmeaSentence(
    override val tNs: Long,
    val text: String,
) : Measurement

// ---------------------------------------------------------------------------------------------
// Radio environment: cellular and Wi-Fi (raw, for coarse positioning without Google/internet)
// ---------------------------------------------------------------------------------------------

/**
 * One observed cell. Field meaning depends on [rat]:
 *  - [area]: LAC (GSM/WCDMA/TD-SCDMA), TAC (LTE/NR), network id (CDMA)
 *  - [cid]: CID (GSM/WCDMA), ECI (LTE, 28 bit), NCI (NR, 36 bit), base-station id (CDMA)
 *  - [pci]: BSIC (GSM), PSC (WCDMA), PCI (LTE/NR), CPID (TD-SCDMA)
 *  - [arfcn]: ARFCN / UARFCN / EARFCN / NR-ARFCN
 *  - [timingAdvance]: raw TA units (GSM: 0..63 ≈ 550 m steps; LTE: 0..1282 ≈ 78 m steps)
 * Neighbour cells often have only [pci]/[arfcn] and signal, with no identity.
 */
@Serializable
data class CellObs(
    val rat: String,
    val registered: Boolean,
    val mcc: Int? = null,
    val mnc: Int? = null,
    val area: Long? = null,
    val cid: Long? = null,
    val pci: Int? = null,
    val arfcn: Int? = null,
    val bandwidthKhz: Int? = null,
    val rssiDbm: Int? = null,
    val rsrpDbm: Int? = null,
    val rsrqDb: Int? = null,
    val sinrDb: Int? = null,
    val timingAdvance: Int? = null,
    val asuLevel: Int? = null,
    /** When the modem measured this cell (elapsedRealtimeNanos), if reported (API 30+). */
    val measuredNs: Long? = null,
    val connectionStatus: Int? = null,
)

@Serializable
@SerialName("cell_scan")
data class CellScan(
    override val tNs: Long,
    val cells: List<CellObs>,
) : Measurement

/** One Wi-Fi access point. SSIDs are deliberately not recorded (privacy); BSSID is what positioning needs. */
@Serializable
data class WifiObs(
    val bssid: String,
    val rssiDbm: Int,
    val freqMhz: Int,
    val channelWidth: Int? = null,
    /** When the AP was last seen (elapsedRealtimeNanos, from ScanResult.timestamp). */
    val seenNs: Long,
    val standard: Int? = null,
)

@Serializable
@SerialName("wifi_scan")
data class WifiScan(
    override val tNs: Long,
    val aps: List<WifiObs>,
) : Measurement

/**
 * Earth's magnetic field model at a place (for example from `android.hardware.GeomagneticField`,
 * i.e. WMM). It gives true-vs-magnetic north (declination, east positive) and the expected field
 * strength, used to gate magnetometer disturbances.
 */
@Serializable
@SerialName("geomag")
data class GeomagneticReference(
    override val tNs: Long,
    val lat: Double,
    val lon: Double,
    val declinationDeg: Double,
    val inclinationDeg: Double,
    val fieldUt: Double,
    val source: String = "WMM",
) : Measurement

/**
 * Battery / charging state. Relevant to the magnetometer: a wireless-charging holder puts a coil
 * with a varying current next to it (a time-varying hard iron). Temperature also shifts magnetometer
 * offsets.
 */
@Serializable
@SerialName("power")
data class PowerState(
    override val tNs: Long,
    /** NONE, AC, USB, WIRELESS, DOCK, OTHER */
    val plug: String,
    val charging: Boolean,
    val currentUa: Long? = null,
    val voltageMv: Int? = null,
    val levelPct: Int? = null,
    val temperatureC: Double? = null,
) : Measurement {
    val wireless: Boolean get() = plug == "WIRELESS"
}

// ---------------------------------------------------------------------------------------------
// Future / synthetic inputs
// ---------------------------------------------------------------------------------------------

/**
 * Timestamped vehicle speed from any source: OBD in the future, or synthetic in replay (derived
 * from truth plus noise). The estimator does not know or care which.
 */
@Serializable
@SerialName("vehicle_speed")
data class VehicleSpeedMeasurement(
    override val tNs: Long,
    val speedMps: Double,
    val stdMps: Double,
    val source: String,
) : Measurement

/** A user annotation made while driving ("tunnel", "jamming seen", ...). */
@Serializable
@SerialName("annotation")
data class Annotation(
    override val tNs: Long,
    val label: String,
    val note: String = "",
) : Measurement

// ---------------------------------------------------------------------------------------------
// Session metadata
// ---------------------------------------------------------------------------------------------

@Serializable
@SerialName("session")
data class SessionInfo(
    /** Equal to [anchorElapsedNs]. */
    override val tNs: Long,
    val sessionId: String,
    val anchorElapsedNs: Long,
    val anchorWallMs: Long,
    val device: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val androidSdk: Int = 0,
    val appVersion: String = "",
    val mode: String = "",
    val notes: String = "",
    val configJson: String = "",
) : DriveRecord {
    fun wallMs(tNs: Long): Long = anchorWallMs + (tNs - anchorElapsedNs) / 1_000_000
}

@Serializable
@SerialName("sensor_info")
data class SensorInfo(
    override val tNs: Long,
    /** android.hardware.Sensor.getType(). Serialized as "sensorType": "type" is the JSONL discriminator. */
    @SerialName("sensorType") val type: Int,
    val stringType: String,
    val name: String,
    val vendor: String,
    val version: Int,
    val resolution: Double,
    val maxRange: Double,
    val minDelayUs: Int,
    val maxDelayUs: Int,
    val fifoMaxEvents: Int,
    val powerMa: Double,
) : DriveRecord

/**
 * Canonical ordering of records: by time, then by kind. Sources such as live Android callbacks,
 * SQLite tables and JSONL all normalize to this, so replay is identical whatever storage produced
 * it. Sorting is stable, so records of the same kind at the same time keep their order.
 */
object RecordOrder {
    private fun rank(r: DriveRecord): Int = when (r) {
        is SessionInfo -> 0
        is SensorInfo -> 1
        is ProviderEvent -> 2
        is ImuSample -> 3
        is OrientationSample -> 4
        is GnssStatusSnapshot -> 5
        is GnssMeasurementBatch -> 6
        is NmeaSentence -> 7
        is CellScan -> 8
        is WifiScan -> 9
        is GeomagneticReference -> 10
        is PowerState -> 11
        is VehicleSpeedMeasurement -> 12
        is LocationMeasurement -> 13
        is Annotation -> 14
        else -> 15
    }

    val comparator: Comparator<DriveRecord> = compareBy<DriveRecord> { it.tNs }.thenBy { rank(it) }
}
