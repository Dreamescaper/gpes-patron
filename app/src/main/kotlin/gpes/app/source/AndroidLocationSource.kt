package gpes.app.source

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import gpes.core.model.LocSource
import gpes.core.model.LocationMeasurement
import gpes.core.model.ProviderEvent
import gpes.core.pipeline.MeasurementSink
import gpes.core.pipeline.MeasurementSource

/** Maps an Android [Location] to the project-owned model. Nothing downstream sees Android types. */
fun Location.toMeasurement(source: LocSource): LocationMeasurement {
    val extrasMap = HashMap<String, String>()
    extras?.let { b ->
        for (k in b.keySet()) {
            @Suppress("DEPRECATION")
            b.get(k)?.let { v -> extrasMap[k] = v.toString() }
        }
    }
    val mock = if (Build.VERSION.SDK_INT >= 31) isMock else @Suppress("DEPRECATION") isFromMockProvider
    return LocationMeasurement(
        tNs = elapsedRealtimeNanos,
        receivedNs = SystemClock.elapsedRealtimeNanos(),
        source = source,
        provider = provider ?: "unknown",
        lat = latitude,
        lon = longitude,
        altM = if (hasAltitude()) altitude else null,
        hAccM = if (hasAccuracy()) accuracy.toDouble() else null,
        vAccM = if (hasVerticalAccuracy()) verticalAccuracyMeters.toDouble() else null,
        speedMps = if (hasSpeed()) speed.toDouble() else null,
        speedAccMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond.toDouble() else null,
        bearingDeg = if (hasBearing()) bearing.toDouble() else null,
        bearingAccDeg = if (hasBearingAccuracy()) bearingAccuracyDegrees.toDouble() else null,
        wallTimeMs = time,
        isMock = mock,
        satsUsed = extrasMap["satellites"]?.toIntOrNull(),
        extras = extrasMap,
    )
}

fun providerSource(provider: String?): LocSource = when (provider) {
    LocationManager.GPS_PROVIDER -> LocSource.GNSS
    LocationManager.NETWORK_PROVIDER -> LocSource.NETWORK
    "fused" -> LocSource.FUSED
    LocationManager.PASSIVE_PROVIDER -> LocSource.PASSIVE
    else -> LocSource.OTHER
}

/**
 * Platform `gps` and `network` providers plus the Play Services fused provider. The `passive`
 * provider is deliberately not used: it re-delivers other providers' fixes under their original
 * provider name, which would double-count GNSS.
 */
class AndroidLocationSource(
    private val context: Context,
    private val handler: Handler,
    private val gnssIntervalMs: Long = 1000,
    private val networkIntervalMs: Long = 10_000,
    private val useFused: Boolean = true,
) : MeasurementSource {
    private val lm = context.getSystemService(LocationManager::class.java)
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private var sink: MeasurementSink? = null

    private val platformListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            sink?.emit(location.toMeasurement(providerSource(location.provider)))
        }

        override fun onLocationChanged(locations: MutableList<Location>) = locations.forEach(::onLocationChanged)

        override fun onProviderEnabled(provider: String) {
            sink?.emit(ProviderEvent(SystemClock.elapsedRealtimeNanos(), provider, ProviderEvent.Kind.ENABLED))
        }

        override fun onProviderDisabled(provider: String) {
            sink?.emit(ProviderEvent(SystemClock.elapsedRealtimeNanos(), provider, ProviderEvent.Kind.DISABLED))
        }
    }

    private val fusedCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { sink?.emit(it.toMeasurement(LocSource.FUSED)) }
        }
    }

    @SuppressLint("MissingPermission")
    override fun start(sink: MeasurementSink) {
        this.sink = sink
        val providers = lm.allProviders
        if (LocationManager.GPS_PROVIDER in providers) {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, gnssIntervalMs, 0f, platformListener, handler.looper)
        }
        if (LocationManager.NETWORK_PROVIDER in providers) {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, networkIntervalMs, 0f, platformListener, handler.looper)
        }
        if (useFused) {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, gnssIntervalMs)
                .setMinUpdateIntervalMillis(gnssIntervalMs)
                .setWaitForAccurateLocation(false)
                .build()
            fused.requestLocationUpdates(req, fusedCallback, handler.looper)
        }
    }

    override fun stop() {
        lm.removeUpdates(platformListener)
        fused.removeLocationUpdates(fusedCallback)
        sink = null
    }
}
