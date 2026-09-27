package gpes.app.mock

import android.annotation.SuppressLint
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.google.android.gms.location.LocationServices
import gpes.app.R
import gpes.core.model.LocationMeasurement
import gpes.core.model.PositionEstimate
import gpes.core.model.ProviderEvent

enum class MockTarget {
    /** Play Services fused provider (Google Maps and most apps). The platform `gps` stays real. */
    FUSED,
    /** Platform `gps` test provider. This overrides real GNSS Location input for the whole device, us included. */
    GPS,
    /** Platform `network` test provider. */
    NETWORK,
}

/**
 * Publishes estimates as Android mock locations. It is an output only: it is never a measurement
 * source. Every published location carries `isMock` (set by the system) and the
 * [LocationMeasurement.SYNTHETIC_EXTRA] tag, and the trust evaluator rejects both, so the input
 * and output paths cannot form a feedback loop.
 *
 * Requires this app to be the selected mock location app (Developer options, or
 * `adb shell appops set gpes.patron android:mock_location allow`).
 */
class MockLocationPublisher(
    private val context: Context,
    private val targets: Set<MockTarget>,
    /** Called with provider override events, so they can be recorded and fed to the trust evaluator. */
    private val onProviderEvent: (ProviderEvent) -> Unit,
) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val active = HashSet<MockTarget>()

    @Volatile var lastError: String? = null
        private set
    @Volatile var published: Long = 0
        private set

    private fun platformName(t: MockTarget) = when (t) {
        MockTarget.GPS -> LocationManager.GPS_PROVIDER
        MockTarget.NETWORK -> LocationManager.NETWORK_PROVIDER
        MockTarget.FUSED -> "fused"
    }

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        lastError = null
        for (t in targets) {
            try {
                when (t) {
                    MockTarget.FUSED -> fused.setMockMode(true)
                    else -> {
                        val name = platformName(t)
                        addTestProvider(name)
                        lm.setTestProviderEnabled(name, true)
                        onProviderEvent(ProviderEvent(SystemClock.elapsedRealtimeNanos(), name, ProviderEvent.Kind.OVERRIDDEN))
                    }
                }
                active += t
            } catch (e: SecurityException) {
                lastError = context.getString(R.string.mock_err_not_selected, e.message)
            } catch (e: Exception) {
                lastError = context.getString(R.string.mock_err_failed, t.name, e.message)
            }
        }
        return lastError == null
    }

    // Pre-31 overload takes Criteria constants; lint only knows the ProviderProperties annotation (same values).
    @SuppressLint("WrongConstant")
    private fun addTestProvider(name: String) {
        if (Build.VERSION.SDK_INT >= 31) {
            lm.addTestProvider(
                name,
                ProviderProperties.Builder()
                    .setHasSatelliteRequirement(name == LocationManager.GPS_PROVIDER)
                    .setHasNetworkRequirement(name == LocationManager.NETWORK_PROVIDER)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setHasAltitudeSupport(false)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(if (name == LocationManager.GPS_PROVIDER) ProviderProperties.ACCURACY_FINE else ProviderProperties.ACCURACY_COARSE)
                    .build(),
            )
        } else {
            @Suppress("DEPRECATION")
            lm.addTestProvider(
                name, name == LocationManager.NETWORK_PROVIDER, name == LocationManager.GPS_PROVIDER, false, false,
                false, true, true, Criteria.POWER_LOW,
                if (name == LocationManager.GPS_PROVIDER) Criteria.ACCURACY_FINE else Criteria.ACCURACY_COARSE,
            )
        }
    }

    @SuppressLint("MissingPermission")
    fun publish(e: PositionEstimate) {
        for (t in active) {
            val loc = toLocation(e, platformName(t))
            try {
                if (t == MockTarget.FUSED) fused.setMockLocation(loc) else lm.setTestProviderLocation(platformName(t), loc)
                published++
            } catch (ex: Exception) {
                lastError = context.getString(R.string.mock_err_publish, t.name, ex.message)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        for (t in active) {
            try {
                if (t == MockTarget.FUSED) fused.setMockMode(false) else {
                    val name = platformName(t)
                    lm.removeTestProvider(name)
                    onProviderEvent(ProviderEvent(SystemClock.elapsedRealtimeNanos(), name, ProviderEvent.Kind.RESTORED))
                }
            } catch (_: Exception) {
            }
        }
        active.clear()
    }

    private fun toLocation(e: PositionEstimate, provider: String) = Location(provider).apply {
        latitude = e.lat
        longitude = e.lon
        accuracy = e.accuracyM.toFloat().coerceAtLeast(1f)
        time = System.currentTimeMillis()
        // Publish "now". The estimate is at most one tick old; the delay is small compared with the stated accuracy.
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        e.speedMps?.let { speed = it.toFloat() }
        e.speedStdMps?.let { speedAccuracyMetersPerSecond = it.toFloat() }
        e.headingRad?.let { bearing = Math.toDegrees(it).toFloat() }
        e.headingStdRad?.let { bearingAccuracyDegrees = Math.toDegrees(it).toFloat().coerceAtMost(180f) }
        extras = Bundle().apply {
            putString(LocationMeasurement.SYNTHETIC_EXTRA, "1")
            putString("gpes.mode", e.mode.name)
        }
        if (Build.VERSION.SDK_INT >= 31) isMock = true
    }
}
