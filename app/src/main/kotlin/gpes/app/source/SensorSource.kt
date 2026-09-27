package gpes.app.source

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.SystemClock
import gpes.core.model.Annotation
import gpes.core.model.ImuKind
import gpes.core.model.ImuSample
import gpes.core.model.OrientationKind
import gpes.core.model.OrientationSample
import gpes.core.model.SensorInfo
import gpes.core.pipeline.MeasurementSink
import gpes.core.pipeline.MeasurementSource
import kotlin.math.abs

/**
 * IMU, magnetometer and rotation-vector sensors. Timestamps are `SensorEvent.timestamp`, which is
 * elapsedRealtimeNanos on API 29+ devices. A per-session sanity check detects devices that use
 * a different base; it records an annotation and corrects the timestamps.
 */
class SensorSource(
    private val sm: SensorManager,
    private val handler: Handler,
    private val imuPeriodUs: Int = 10_000,
    private val otherPeriodUs: Int = 20_000,
    private val maxReportLatencyUs: Int = 100_000,
) : MeasurementSource {

    private data class Spec(val type: Int, val periodUs: Int, val imu: ImuKind? = null, val orient: OrientationKind? = null)

    private val specs = listOf(
        Spec(Sensor.TYPE_ACCELEROMETER, imuPeriodUs, imu = ImuKind.ACCEL),
        Spec(Sensor.TYPE_GYROSCOPE, imuPeriodUs, imu = ImuKind.GYRO),
        Spec(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED, imuPeriodUs, imu = ImuKind.ACCEL_UNCAL),
        Spec(Sensor.TYPE_GYROSCOPE_UNCALIBRATED, imuPeriodUs, imu = ImuKind.GYRO_UNCAL),
        Spec(Sensor.TYPE_MAGNETIC_FIELD, otherPeriodUs, imu = ImuKind.MAG),
        Spec(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED, otherPeriodUs, imu = ImuKind.MAG_UNCAL),
        Spec(Sensor.TYPE_GRAVITY, otherPeriodUs, imu = ImuKind.GRAVITY),
        Spec(Sensor.TYPE_LINEAR_ACCELERATION, otherPeriodUs, imu = ImuKind.LINEAR_ACCEL),
        Spec(Sensor.TYPE_ROTATION_VECTOR, otherPeriodUs, orient = OrientationKind.ROTATION_VECTOR),
        Spec(Sensor.TYPE_GAME_ROTATION_VECTOR, otherPeriodUs, orient = OrientationKind.GAME_ROTATION_VECTOR),
        Spec(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR, otherPeriodUs, orient = OrientationKind.GEOMAG_ROTATION_VECTOR),
    )

    private val byType = specs.associateBy { it.type }
    private var sink: MeasurementSink? = null
    private var timeOffsetNs: Long? = null
    private val q = FloatArray(4)

    /** Sensors present on this device, as recorded metadata. */
    fun sensorInfo(): List<SensorInfo> {
        val t = SystemClock.elapsedRealtimeNanos()
        return specs.mapNotNull { sm.getDefaultSensor(it.type) }.map { s ->
            SensorInfo(
                t, s.type, s.stringType, s.name, s.vendor, s.version, s.resolution.toDouble(), s.maximumRange.toDouble(),
                s.minDelay, s.maxDelay, s.fifoMaxEventCount, s.power.toDouble(),
            )
        }
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val spec = byType[e.sensor.type] ?: return
            val sink = sink ?: return
            val off = timeOffsetNs ?: run {
                // If the sensor timebase is not elapsedRealtimeNanos (off by > 1 s), correct it and say so.
                val raw = SystemClock.elapsedRealtimeNanos() - e.timestamp
                val o = if (abs(raw) > 1_000_000_000L) raw else 0L
                timeOffsetNs = o
                sink.emit(Annotation(SystemClock.elapsedRealtimeNanos(), "timebase", "sensorOffsetNs=$raw applied=$o sensor=${e.sensor.name}"))
                o
            }
            val t = e.timestamp + off
            val v = e.values
            when {
                spec.imu != null -> {
                    val uncal = spec.type == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED ||
                        spec.type == Sensor.TYPE_GYROSCOPE_UNCALIBRATED || spec.type == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED
                    sink.emit(
                        ImuSample(
                            t, spec.imu, v[0].toDouble(), v[1].toDouble(), v[2].toDouble(),
                            if (uncal && v.size >= 6) v[3].toDouble() else null,
                            if (uncal && v.size >= 6) v[4].toDouble() else null,
                            if (uncal && v.size >= 6) v[5].toDouble() else null,
                            e.accuracy,
                        ),
                    )
                }
                spec.orient != null -> {
                    SensorManager.getQuaternionFromVector(q, v)
                    val headingAcc = if (spec.type != Sensor.TYPE_GAME_ROTATION_VECTOR && v.size >= 5 && v[4] >= 0) v[4].toDouble() else null
                    sink.emit(OrientationSample(t, spec.orient, q[0].toDouble(), q[1].toDouble(), q[2].toDouble(), q[3].toDouble(), headingAcc))
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    override fun start(sink: MeasurementSink) {
        this.sink = sink
        timeOffsetNs = null
        for (spec in specs) {
            val s = sm.getDefaultSensor(spec.type) ?: continue
            sm.registerListener(listener, s, spec.periodUs, maxReportLatencyUs, handler)
        }
    }

    override fun stop() {
        sm.unregisterListener(listener)
        sink = null
    }
}
