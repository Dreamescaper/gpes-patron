package gpes.core.pipeline

import gpes.core.estimator.PositionEstimator
import gpes.core.model.LocSource
import gpes.core.model.LocationMeasurement
import gpes.core.model.Measurement
import gpes.core.model.PositionEstimate
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustState
import gpes.core.motion.MotionTracker
import gpes.core.trust.LocationTrustEvaluator
import gpes.core.trust.MotionView
import gpes.core.trust.TrustContext
import java.util.PriorityQueue

fun interface MeasurementSink {
    fun emit(m: Measurement)
}

/** A producer of measurements: Android sources, replay readers, future OBD. */
interface MeasurementSource {
    fun start(sink: MeasurementSink)
    fun stop()
}

interface PipelineListener {
    fun onTrust(a: TrustAssessment) {}
    fun onEstimate(e: PositionEstimate) {}
    fun onTick(tNs: Long, sourceStates: Map<LocSource, TrustState>) {}
}

data class PipelineConfig(
    /** Measurements are held this long, so that late-arriving fixes can be sorted in. 0 for sorted replay. */
    val reorderWindowNs: Long = 500_000_000,
    val tickPeriodNs: Long = 1_000_000_000,
    val historyNs: Long = 600_000_000_000,
    val snapshotPeriodNs: Long = 1_000_000_000,
)

/**
 * The single processing path shared by the Android service and offline replay:
 *
 *     measurements → reorder buffer → motion tracker → trust evaluator → estimator → ticks → listener
 *
 * Only measurement timestamps drive it; it never reads the wall clock, so it is deterministic.
 * It is **not** thread-safe: callers must serialize [emit] (the Android service uses one thread).
 *
 * A history ring buffer keeps the recent measurements plus periodic snapshots of all state. That
 * way, when spoofing is recognized late, [rollbackAndReplay] can rewind to before it started and
 * re-process with different decisions.
 */
class MeasurementPipeline(
    private val cfg: PipelineConfig,
    private val trust: LocationTrustEvaluator,
    private val estimator: PositionEstimator?,
    private val motion: MotionTracker = MotionTracker(),
    var listener: PipelineListener = object : PipelineListener {},
) : MeasurementSink {

    private data class Entry(val seq: Long, val m: Measurement)

    private data class Snapshot(
        val tNs: Long, val seq: Long, val estimator: Any?, val trust: Any,
        val motion: MotionTracker.State, val nextTick: Long,
    )

    data class Stats(var processed: Long = 0, var late: Long = 0, var ticks: Long = 0)

    val stats = Stats()

    var latestEstimate: PositionEstimate? = null
        private set

    private var seqCounter = 0L
    private val pending = PriorityQueue<Entry>(compareBy<Entry> { it.m.tNs }.thenBy { it.seq })
    private var maxSeenT = Long.MIN_VALUE
    private var lastProcessedT = Long.MIN_VALUE
    private var nextTick = Long.MIN_VALUE
    private var nextSnapshotT = Long.MIN_VALUE

    private val history = ArrayDeque<Entry>()
    private val snapshots = ArrayDeque<Snapshot>()

    private val motionView = object : MotionView {
        override fun stationaryForS() = motion.stationaryForS()
        override fun bearingChange(t1: Long, t2: Long) = motion.bearingChange(t1, t2)
    }

    override fun emit(m: Measurement) {
        pending.add(Entry(seqCounter++, m))
        if (m.tNs > maxSeenT) maxSeenT = m.tNs
        while (pending.isNotEmpty() && pending.peek().m.tNs <= maxSeenT - cfg.reorderWindowNs) {
            process(pending.poll().m)
        }
    }

    /** Process everything still buffered (end of replay / stop). */
    fun flush() {
        while (pending.isNotEmpty()) process(pending.poll().m)
    }

    private fun process(m: Measurement) {
        if (lastProcessedT != Long.MIN_VALUE && m.tNs < lastProcessedT) stats.late++
        if (nextTick == Long.MIN_VALUE) nextTick = ceilTo(m.tNs, cfg.tickPeriodNs)
        while (m.tNs >= nextTick) {
            tick(nextTick)
            nextTick += cfg.tickPeriodNs
        }
        val seq = seqCounter++
        if (m.tNs >= nextSnapshotT) {
            snapshots.addLast(Snapshot(m.tNs, seq, estimator?.snapshot(), trust.snapshot(), motion.snapshot(), nextTick))
            nextSnapshotT = m.tNs + cfg.snapshotPeriodNs
        }
        history.addLast(Entry(seq, m))
        trimHistory(m.tNs)
        apply(m)
        if (m.tNs > lastProcessedT) lastProcessedT = m.tNs
        stats.processed++
    }

    private fun apply(m: Measurement) {
        motion.onMeasurement(m)?.let { estimator?.onMotion(it) }
        trust.observe(m)
        if (m is LocationMeasurement) {
            val pred = estimator?.estimate(m.tNs)
            val a = trust.assess(m, TrustContext(pred, motionView))
            listener.onTrust(a)
            estimator?.onMeasurement(m, a)
        } else {
            estimator?.onMeasurement(m, null)
        }
    }

    private fun tick(t: Long) {
        stats.ticks++
        estimator?.estimate(t)?.let {
            latestEstimate = it
            listener.onEstimate(it)
        }
        listener.onTick(t, LocSource.entries.associateWith { trust.sourceState(t, it) })
    }

    private fun trimHistory(now: Long) {
        while (history.isNotEmpty() && history.first().m.tNs < now - cfg.historyNs) history.removeFirst()
        val oldest = history.firstOrNull()?.seq ?: return
        // Keep only snapshots whose replay range is fully in history.
        while (snapshots.size > 1 && snapshots.first().seq < oldest) snapshots.removeFirst()
    }

    data class RollbackResult(val fromNs: Long, val replayed: Int, val estimates: List<PositionEstimate>, val trust: List<TrustAssessment>)

    /**
     * Rewind to the latest snapshot at or before [fromNs], then re-process all later measurements
     * through [transform]. The transform may modify a measurement or drop it (return null), for example
     * to discard GNSS that is now known to be spoofed. Revised outputs are returned rather than sent to
     * the listener. Buffered (not yet processed) measurements are not affected.
     */
    fun rollbackAndReplay(fromNs: Long, transform: (Measurement) -> Measurement? = { it }): RollbackResult? {
        val snap = snapshots.lastOrNull { it.tNs <= fromNs } ?: return null
        estimator?.let { e -> snap.estimator?.let { e.restore(it) } }
        trust.restore(snap.trust)
        motion.restore(snap.motion)
        nextTick = snap.nextTick
        val replay = history.filter { it.seq >= snap.seq }.map { it.m }
        while (history.isNotEmpty() && history.last().seq >= snap.seq) history.removeLast()
        while (snapshots.isNotEmpty() && snapshots.last().seq > snap.seq) snapshots.removeLast()
        snapshots.removeLast()
        nextSnapshotT = Long.MIN_VALUE
        lastProcessedT = snap.tNs

        val estimates = ArrayList<PositionEstimate>()
        val trusts = ArrayList<TrustAssessment>()
        val saved = listener
        listener = object : PipelineListener {
            override fun onTrust(a: TrustAssessment) { trusts += a }
            override fun onEstimate(e: PositionEstimate) { estimates += e }
        }
        try {
            for (m in replay) transform(m)?.let { process(it) }
        } finally {
            listener = saved
        }
        return RollbackResult(snap.tNs, replay.size, estimates, trusts)
    }

    private fun ceilTo(t: Long, p: Long): Long = Math.floorDiv(t + p - 1, p) * p
}
