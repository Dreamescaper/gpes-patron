package gpes.app.source

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.SystemClock
import gpes.core.model.PowerState
import gpes.core.pipeline.MeasurementSink
import gpes.core.pipeline.MeasurementSource

/**
 * Charging state, battery current and temperature, polled every second. A wireless-charging holder
 * is a time-varying magnetic field next to the magnetometer, and temperature shifts magnetometer
 * offsets, so the compass needs to know. A sample is emitted on any plug/charging change, and
 * otherwise every [periodMs].
 */
class PowerSource(
    private val context: Context,
    private val handler: Handler,
    private val pollMs: Long = 1000,
    private val periodMs: Long = 5000,
) : MeasurementSource {
    private val bm = context.getSystemService(BatteryManager::class.java)
    private var sink: MeasurementSink? = null
    private var last: PowerState? = null

    private val poll = object : Runnable {
        override fun run() {
            val s = sink ?: return
            read()?.let { p ->
                val prev = last
                if (prev == null || prev.plug != p.plug || prev.charging != p.charging || p.tNs - prev.tNs >= periodMs * 1_000_000) {
                    last = p
                    s.emit(p)
                }
            }
            handler.postDelayed(this, pollMs)
        }
    }

    private fun read(): PowerState? {
        val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val plug = when (i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
            0 -> "NONE"
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            BatteryManager.BATTERY_PLUGGED_DOCK -> "DOCK"
            else -> "OTHER"
        }
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val current = bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)?.takeIf { it != Long.MIN_VALUE && it != 0L }
        return PowerState(
            tNs = SystemClock.elapsedRealtimeNanos(),
            plug = plug,
            charging = charging,
            currentUa = current,
            voltageMv = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 },
            levelPct = if (level >= 0 && scale > 0) level * 100 / scale else null,
            temperatureC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 },
        )
    }

    override fun start(sink: MeasurementSink) {
        this.sink = sink
        last = null
        handler.post(poll)
    }

    override fun stop() {
        sink = null
        handler.removeCallbacks(poll)
    }
}
