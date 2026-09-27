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
    val type: Int,
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
