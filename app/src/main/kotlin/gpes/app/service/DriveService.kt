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
import gpes.app.source.AndroidLocationSource
import gpes.app.source.GnssRawSource
import gpes.app.source.CellSource
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
        const val EXTRA_MODE = "mode"
        const val EXTRA_TARGETS = "targets"
        const val EXTRA_LABEL = "label"
        const val EXTRA_USE_QUESTIONABLE = "useQuestionable"
        private const val CHANNEL = "drive"
        private const val NOTIF_ID = 1

        fun start(ctx: Context, mode: RunMode, targets: Set<MockTarget>, useQuestionable: Boolean = false) {
            val i = Intent(ctx, DriveService::class.java).setAction(ACTION_START)
                .putExtra(EXTRA_MODE, mode.name)
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> if (session == null) {
                val mode = RunMode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: RunMode.RECORD_ONLY.name)
                val targets = intent.getStringArrayExtra(EXTRA_TARGETS)?.map { MockTarget.valueOf(it) }?.toSet() ?: emptySet()
                goForeground(mode)
                val baseline = BaselineConfig(questionableRScale = if (intent.getBooleanExtra(EXTRA_USE_QUESTIONABLE, false)) 4.0 else null)
                session = Session(this, mode, if (mode.mock) targets else emptySet(), baseline).also { it.start() }
            }
            ACTION_STOP -> {
                session?.stop()
                session = null
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_ANNOTATE -> session?.annotate(intent.getStringExtra(EXTRA_LABEL) ?: "mark")
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        session?.stop()
        session = null
        super.onDestroy()
    }

    private fun goForeground(mode: RunMode) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_drive), NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.notif_title, getString(mode.labelRes)))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
        ServiceCompat.startForeground(this, NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }
}

/** One recording session. Created and stopped by [DriveService]. */
private class Session(
    private val ctx: Context,
    private val mode: RunMode,
    private val targets: Set<MockTarget>,
    private val baselineConfig: BaselineConfig,
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
    private var publisher: MockLocationPublisher? = null
    private val sensorSource = SensorSource(ctx.getSystemService(SensorManager::class.java), handler)
    private val gnssRaw = GnssRawSource(ctx, handler)
    private val wifi = WifiSource(ctx, handler)
    private val sources: List<MeasurementSource> = buildList {
        add(AndroidLocationSource(ctx, handler)); add(gnssRaw); add(sensorSource); add(wifi)
        ctx.getSystemService(TelephonyManager::class.java)?.let { add(CellSource(it, handler)) }
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
        if (m is gpes.core.model.LocationMeasurement && !m.isSynthetic) maybeGeomag(m)
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
            pipeline = MeasurementPipeline(PipelineConfig(), DefaultTrustEvaluator(), BaselineDrEstimator(baselineConfig)).also {
                it.listener = object : PipelineListener {
                    override fun onTrust(a: TrustAssessment) {
                        writer.write(a)
                        lastTrust[a.source] = a
                    }

                    override fun onEstimate(e: PositionEstimate) {
                        writer.write(e)
                        lastEstimate = e
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
            )
        }
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
