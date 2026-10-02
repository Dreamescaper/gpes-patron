package gpes.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import gpes.app.BuildConfig
import gpes.app.R
import gpes.app.mock.MockLocationPublisher
import gpes.app.mock.MockTarget
import gpes.app.road.RoadMapManager
import gpes.app.source.AndroidLocationSource
import gpes.app.source.GnssRawSource
import gpes.app.source.CellSource
import gpes.app.source.PowerSource
import gpes.app.source.ObdSource
import gpes.app.source.SensorSource
import gpes.app.source.WifiSource
import android.telephony.TelephonyManager
import gpes.core.model.CellObs
import gpes.core.model.CellScan
import gpes.core.model.WifiScan
import gpes.app.ui.MainActivity
import gpes.core.estimator.BaselineConfig
import gpes.core.estimator.BaselineDrEstimator
import gpes.core.io.DriveJson
import gpes.core.model.GnssStatusSnapshot
import gpes.core.model.LocSource
import gpes.core.model.Measurement
import gpes.core.model.PositionEstimate
import gpes.core.model.SessionInfo
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustState
import gpes.core.pipeline.MeasurementPipeline
import gpes.core.pipeline.MeasurementSink
import gpes.core.pipeline.MeasurementSource
import gpes.core.pipeline.PipelineConfig
import gpes.core.pipeline.PipelineListener
import gpes.core.trust.DefaultTrustEvaluator
import gpes.recording.DriveWriter
import gpes.recording.db.DriveDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Foreground service that owns one drive session.
 *
 * Threading: every Android callback (location, GNSS, sensors) is delivered on one HandlerThread.
 * That thread is also the only one touching the pipeline, so the pipeline needs no locking. The
 * recorder buffer is thread-safe and is flushed from a separate scheduler thread.
 *
 * Data flow:
 *
 *     sources ──► sink ──► recorder (everything, including rejected/mock inputs)
 *                   └────► pipeline (trust → estimator) ──► recorder, UI, mock publisher
 *
 * The mock publisher is never a source; its output returns only through Android and is rejected
 * as synthetic.
 */
class DriveService : Service() {

    companion object {
        const val ACTION_START = "gpes.START"
        const val ACTION_STOP = "gpes.STOP"
        const val ACTION_ANNOTATE = "gpes.ANNOTATE"
        /** Re-post the notification after the user swiped it away (Android 14+ allows that for foreground services). */
        const val ACTION_REFRESH_NOTIFICATION = "gpes.REFRESH_NOTIFICATION"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TARGETS = "targets"
        const val EXTRA_LABEL = "label"
        const val EXTRA_USE_QUESTIONABLE = "useQuestionable"
        const val EXTRA_OBD_ADDRESS = "obdAddress"
        const val EXTRA_ROADS = "roads"
        private const val CHANNEL = "drive"
        /** Louder than [CHANNEL] (still silent): shown on the lock screen while our output replaces the system location. */
        private const val CHANNEL_SPOOF = "spoof"
        private const val NOTIF_ID = 1

        fun start(ctx: Context, mode: RunMode, targets: Set<MockTarget>, useQuestionable: Boolean = false, obdAddress: String? = null, roads: Boolean = false) {
            val i = Intent(ctx, DriveService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_ROADS, roads)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_OBD_ADDRESS, obdAddress)
                .putExtra(EXTRA_USE_QUESTIONABLE, useQuestionable)
                .putExtra(EXTRA_TARGETS, targets.map { it.name }.toTypedArray())
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, DriveService::class.java).setAction(ACTION_STOP))
        }

        fun annotate(ctx: Context, label: String) {
            ctx.startService(Intent(ctx, DriveService::class.java).setAction(ACTION_ANNOTATE).putExtra(EXTRA_LABEL, label))
        }
    }

    private var session: Session? = null
    private var notifMode: RunMode = RunMode.RECORD_ONLY
    private var notifText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (session == null) {
                val mode = RunMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: RunMode.RECORD_ONLY.name)
                val targets = intent.getStringArrayExtra(EXTRA_TARGETS)?.map { MockTarget.valueOf(it) }?.toSet() ?: emptySet()
                notifMode = mode
                notifText = null
                goForeground()
                val baseline = BaselineConfig(questionableRScale = if (intent.getBooleanExtra(EXTRA_USE_QUESTIONABLE, false)) 4.0 else null)
                session = Session(
                    this, mode, if (mode.mock) targets else emptySet(), baseline, intent.getStringExtra(EXTRA_OBD_ADDRESS),
                    roads = intent.getBooleanExtra(EXTRA_ROADS, false),
                    onStatus = ::updateNotification,
                ).also { it.start() }
            }
            ACTION_STOP -> {
                session?.stop()
                session = null
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_ANNOTATE -> session?.annotate(intent.getStringExtra(EXTRA_LABEL) ?: "mark")
            ACTION_REFRESH_NOTIFICATION -> if (session != null) notifyNow()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        super.onDestroy()
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_drive), NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SPOOF, getString(R.string.channel_spoof), NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null) },
        )
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    private fun notifyNow() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    /** Called by the session once a second; the notification is re-posted only when its text changes. */
    private fun updateNotification(s: Status) {
        val e = s.estimate
        val text = if (!notifMode.estimate) getString(R.string.notif_text)
        else if (e == null) getString(R.string.notif_waiting)
        else {
            val gnss = (s.sourceStates[LocSource.GNSS] ?: s.sourceStates[LocSource.FUSED])?.let { getString(trustRes(it)) }
            getString(R.string.notif_position, e.accuracyM, gnss ?: getString(R.string.notif_no_gnss))
        }
        if (text == notifText) return
        notifText = text
        notifyNow()
    }

    private fun buildNotification(): Notification {
        val spoof = notifMode.mock
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, DriveService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val refresh = PendingIntent.getService(
            this, 2, Intent(this, DriveService::class.java).setAction(ACTION_REFRESH_NOTIFICATION), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, if (spoof) CHANNEL_SPOOF else CHANNEL)
            .setContentTitle(if (spoof) getString(R.string.notif_title_spoof) else getString(R.string.notif_title, getString(notifMode.labelRes)))
            .setContentText(notifText ?: getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .setDeleteIntent(refresh)
            .addAction(0, getString(if (spoof) R.string.notif_action_stop_spoof else R.string.notif_action_stop), stop)
            .build()
    }

    private fun trustRes(s: TrustState) = when (s) {
        TrustState.TRUSTED -> R.string.trust_trusted
        TrustState.QUESTIONABLE -> R.string.trust_questionable
        TrustState.REJECTED -> R.string.trust_rejected
        TrustState.UNAVAILABLE -> R.string.trust_unavailable
    }
}

/** One recording session. Created and stopped by [DriveService]. */
private class Session(
    private val ctx: Context,
    private val mode: RunMode,
    private val targets: Set<MockTarget>,
    private val baselineConfig: BaselineConfig,
    private val obdAddress: String?,
    private val roads: Boolean,
    private val onStatus: (Status) -> Unit,
) {
    private val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
    private val file = File(DriveStorage.dir(ctx), "$id.db")
    private val thread = HandlerThread("gpes-io", Process.THREAD_PRIORITY_MORE_FAVORABLE).also { it.start() }
    private val handler = Handler(thread.looper)
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
    private val wakeLock = ctx.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gpes:drive")

    private lateinit var driver: AndroidSqliteDriver
    private lateinit var writer: DriveWriter
    private var pipeline: MeasurementPipeline? = null
    private var estimator: BaselineDrEstimator? = null
    private var publisher: MockLocationPublisher? = null
    /** Road tiles around the car (Phase 2); only when estimating and the user enabled the road map. */
    private val roadMap: RoadMapManager? = if (roads && mode.estimate) RoadMapManager(ctx) else null
    private val sensorSource = SensorSource(ctx.getSystemService(SensorManager::class.java), handler)
    private val gnssRaw = GnssRawSource(ctx, handler)
    private val wifi = WifiSource(ctx, handler)
    private val obd: ObdSource? = obdAddress?.let { addr ->
        ObdSource(ctx, addr, post = { m -> handler.post { sink.emit(m) } }, record = { r -> writer.write(r) })
    }
    private val sources: List<MeasurementSource> = buildList {
        add(AndroidLocationSource(ctx, handler)); add(gnssRaw); add(sensorSource); add(wifi); add(PowerSource(ctx, handler))
        ctx.getSystemService(TelephonyManager::class.java)?.let { add(CellSource(it, handler)) }
        obd?.let { add(it) }
    }

    // Written on the handler thread, read by the status ticker.
    @Volatile private var lastStatus: GnssStatusSnapshot? = null
    @Volatile private var lastCells: CellScan? = null
    @Volatile private var lastWifi: WifiScan? = null
    private val lastTrust = HashMap<LocSource, TrustAssessment>()
    @Volatile private var sourceStates: Map<LocSource, TrustState> = emptyMap()
    @Volatile private var lastEstimate: PositionEstimate? = null
    private var prevCounts: Map<String, Long> = emptyMap()
    private var prevCountsT = 0L
    private val startNs = SystemClock.elapsedRealtimeNanos()

    private var geomagAt: gpes.core.model.LocationMeasurement? = null

    private val sink: MeasurementSink = MeasurementSink { m: Measurement ->
        writer.write(m)
        if (m is gpes.core.model.LocationMeasurement && !m.isSynthetic) {
            maybeGeomag(m)
            // Before the estimator has a position, a network fix is enough to pick road tiles.
            if (m.source == LocSource.NETWORK && lastEstimate == null) roadMap?.onPosition(m.lat, m.lon, System.currentTimeMillis())
        }
        when (m) {
            is GnssStatusSnapshot -> lastStatus = m
            is CellScan -> lastCells = m
            is WifiScan -> lastWifi = m
            else -> Unit
        }
        pipeline?.emit(m)
    }

    fun start() {
        wakeLock.acquire(12 * 60 * 60 * 1000L)
        driver = DriveStorage.open(ctx, file)
        writer = DriveWriter(DriveDatabase(driver))

        val config = buildJsonObject {
            put("mode", mode.name)
            putJsonArray("mockTargets") { targets.forEach { add(it.name) } }
            put("imuPeriodUs", 10_000)
            put("estimator", if (mode.estimate) "baseline" else "none")
            put("obdAddress", obdAddress ?: "")
            put("roads", roadMap != null)
            put("baseline", DriveJson.json.encodeToJsonElement(BaselineConfig.serializer(), baselineConfig))
        }
        writer.write(
            SessionInfo(
                tNs = startNs, sessionId = id, anchorElapsedNs = startNs, anchorWallMs = System.currentTimeMillis(),
                device = Build.DEVICE, manufacturer = Build.MANUFACTURER, model = Build.MODEL, androidSdk = Build.VERSION.SDK_INT,
                appVersion = BuildConfig.VERSION_NAME, mode = mode.name, configJson = config.toString(),
            ),
        )
        sensorSource.sensorInfo().forEach(writer::write)

        if (mode.estimate) {
            estimator = BaselineDrEstimator(baselineConfig, roads = roadMap?.let { rm -> { rm.network } })
            pipeline = MeasurementPipeline(PipelineConfig(), DefaultTrustEvaluator(), estimator).also {
                it.listener = object : PipelineListener {
                    override fun onTrust(a: TrustAssessment) {
                        writer.write(a)
                        lastTrust[a.source] = a
                    }

                    override fun onEstimate(e: PositionEstimate) {
                        writer.write(e)
                        lastEstimate = e
                        roadMap?.onPosition(e.lat, e.lon, System.currentTimeMillis())
                        publisher?.publish(e)
                    }

                    override fun onTick(tNs: Long, sourceStates: Map<LocSource, TrustState>) {
                        this@Session.sourceStates = sourceStates
                    }
                }
            }
        }

        handler.post {
            if (mode.mock) {
                publisher = MockLocationPublisher(ctx, targets) { ev -> sink.emit(ev) }.also { it.start() }
            }
            sources.forEach { it.start(sink) }
        }

        LiveStatus.set(Status(running = true, mode = mode, mockTargets = targets, sessionId = id, startedElapsedNs = startNs))
        scheduler.scheduleWithFixedDelay({ runCatching { writer.flush() } }, 500, 500, TimeUnit.MILLISECONDS)
        scheduler.scheduleWithFixedDelay({ runCatching { publishStatus() } }, 1000, 1000, TimeUnit.MILLISECONDS)
    }

    /**
     * Emit the WMM field model (declination, inclination, strength) for the current area: once at the first
     * real fix of any provider (coarse is fine; declination varies slowly), and again after moving > 50 km.
     */
    private fun maybeGeomag(m: gpes.core.model.LocationMeasurement) {
        val prev = geomagAt
        if (prev != null && gpes.core.geo.Geo.haversineM(prev.lat, prev.lon, m.lat, m.lon) < 50_000) return
        geomagAt = m
        val f = android.hardware.GeomagneticField(m.lat.toFloat(), m.lon.toFloat(), (m.altM ?: 0.0).toFloat(), System.currentTimeMillis())
        sink.emit(
            gpes.core.model.GeomagneticReference(
                SystemClock.elapsedRealtimeNanos(), m.lat, m.lon, f.declination.toDouble(), f.inclination.toDouble(), f.fieldStrength / 1000.0, "WMM (android GeomagneticField)",
            ),
        )
    }

    fun annotate(label: String) {
        handler.post { sink.emit(gpes.core.model.Annotation(SystemClock.elapsedRealtimeNanos(), label)) }
    }

    private fun publishStatus() {
        val now = SystemClock.elapsedRealtimeNanos()
        val counts = writer.counts()
        val dt = (now - prevCountsT) / 1e9
        val rates = if (prevCountsT == 0L) emptyMap() else counts.mapValues { (k, v) -> (v - (prevCounts[k] ?: 0)) / dt }
        prevCounts = counts
        prevCountsT = now
        val st = lastStatus
        val used = st?.sats?.filter { it.usedInFix }.orEmpty()
        val trust: Map<LocSource, TrustAssessment> = handlerSnapshot()
        LiveStatus.update {
            it.copy(
                counts = counts, ratesHz = rates, sourceStates = sourceStates, lastTrust = trust, estimate = lastEstimate,
                satsUsed = used.size, satsVisible = st?.sats?.size ?: 0,
                meanCn0 = if (used.isEmpty()) null else used.sumOf { s -> s.cn0DbHz } / used.size,
                gnssMeasurements = gnssRaw.measurementsStatus,
                mockPublished = publisher?.published ?: 0, mockError = publisher?.lastError,
                lateMeasurements = pipeline?.stats?.late ?: 0,
                cells = lastCells?.let { c -> c.cells.size to c.cells.firstOrNull { x -> x.registered }?.let(::describeCell) },
                wifiAps = lastWifi?.let { w -> w.aps.size to ((now - w.tNs) / 1_000_000_000) },
                wifiScans = wifi.scansRequested to wifi.scansThrottled,
                compass = estimator?.compassStatus,
                obd = obd?.status,
                speedScale = estimator?.speedScaleStatus,
                roadMap = roadMap?.status,
            )
        }
        onStatus(LiveStatus.flow.value)
    }

    private fun handlerSnapshot(): Map<LocSource, TrustAssessment> {
        // lastTrust is mutated on the handler thread; copy it there.
        val latch = java.util.concurrent.CountDownLatch(1)
        var copy: Map<LocSource, TrustAssessment> = emptyMap()
        if (!handler.post { copy = HashMap(lastTrust); latch.countDown() }) return emptyMap()
        latch.await(500, TimeUnit.MILLISECONDS)
        return copy
    }

    fun stop() {
        val done = java.util.concurrent.CountDownLatch(1)
        handler.post {
            sources.forEach { runCatching { it.stop() } }
            publisher?.stop() // emits RESTORED events into the sink
            pipeline?.flush()
            done.countDown()
        }
        done.await(3, TimeUnit.SECONDS)
        roadMap?.stop()
        scheduler.shutdown()
        scheduler.awaitTermination(2, TimeUnit.SECONDS)
        runCatching { writer.flush() }
        driver.close()
        thread.quitSafely()
        if (wakeLock.isHeld) wakeLock.release()
        LiveStatus.update { it.copy(running = false) }
    }
}

private fun describeCell(c: CellObs): String = buildString {
    append(c.rat)
    if (c.mcc != null) append(" ${c.mcc}-${c.mnc}")
    if (c.area != null) append(" area ${c.area}")
    if (c.cid != null) append(" cid ${c.cid}")
    (c.rsrpDbm ?: c.rssiDbm)?.let { append(" ${it} dBm") }
    c.timingAdvance?.let { append(" TA $it") }
}
