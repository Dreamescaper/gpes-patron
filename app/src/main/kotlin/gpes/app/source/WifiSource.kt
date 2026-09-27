package gpes.app.source

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import androidx.core.content.ContextCompat
import gpes.core.model.WifiObs
import gpes.core.model.WifiScan
import gpes.core.pipeline.MeasurementSink
import gpes.core.pipeline.MeasurementSource

/**
 * Wi-Fi access points (BSSID, RSSI, frequency). Android throttles app-initiated scans to about 4 per
 * 2 minutes, so we request a scan every [requestPeriodMs] and also take results from scans that
 * other apps or the system triggered (SCAN_RESULTS_AVAILABLE broadcasts). Only APs seen since the
 * previous emission are recorded, each with its own `seenNs`. SSIDs are not recorded.
 */
class WifiSource(
    private val context: Context,
    private val handler: Handler,
    private val requestPeriodMs: Long = 30_000,
) : MeasurementSource {
    private val wm = context.applicationContext.getSystemService(WifiManager::class.java)
    private var sink: MeasurementSink? = null
    private var lastSeenNs = 0L

    @Volatile var scansRequested = 0
        private set
    @Volatile var scansThrottled = 0
        private set

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) = collect()
    }

    private val requester = object : Runnable {
        override fun run() {
            if (sink == null) return
            @Suppress("DEPRECATION")
            val ok = runCatching { wm.startScan() }.getOrDefault(false)
            if (ok) scansRequested++ else scansThrottled++
            handler.postDelayed(this, requestPeriodMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun collect() {
        val results = runCatching { wm.scanResults }.getOrNull() ?: return
        val fresh = results.mapNotNull { r ->
            val seen = r.timestamp * 1000 // µs since boot → ns (elapsedRealtime base)
            if (seen <= lastSeenNs) return@mapNotNull null
            WifiObs(
                bssid = r.BSSID ?: return@mapNotNull null,
                rssiDbm = r.level,
                freqMhz = r.frequency,
                channelWidth = r.channelWidth,
                seenNs = seen,
                standard = if (Build.VERSION.SDK_INT >= 30) r.wifiStandard else null,
            )
        }
        if (fresh.isEmpty()) return
        lastSeenNs = maxOf(lastSeenNs, fresh.maxOf { it.seenNs })
        sink?.emit(WifiScan(SystemClock.elapsedRealtimeNanos(), fresh.sortedBy { it.bssid }))
    }

    override fun start(sink: MeasurementSink) {
        this.sink = sink
        lastSeenNs = 0L
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), null, handler,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        handler.post { collect() } // whatever the system already has
        handler.post(requester)
    }

    override fun stop() {
        sink = null
        handler.removeCallbacks(requester)
        runCatching { context.unregisterReceiver(receiver) }
    }
}
