package gpes.core.trust

import gpes.core.model.GnssStatusSnapshot
import gpes.core.model.LocSource
import gpes.core.model.TrustAssessment
import gpes.core.model.TrustReason
import gpes.core.model.TrustState
import kotlinx.serialization.Serializable
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Policy for finding out whether real GNSS is back while we replace the platform `gps` provider (D-052).
 *
 * With the test provider installed, real GNSS *fixes* do not reach us, but `GnssStatus` keeps arriving. When
 * the chip looks healthy for a while, the app removes the test provider for a short window so that real fixes
 * flow through trust and the estimator again. This class only decides *when* to open and close the window; it
 * has no clock and no Android dependencies (times are `elapsedRealtimeNanos` passed in).
 */
@Serializable
data class GnssProbeConfig(
    /** The chip must use at least this many satellites in its fix. */
    val minUsedSats: Int = 5,
    val minMeanCn0DbHz: Double = 20.0,
    /**
     * Spread of C/N0 among the used satellites; real skies are varied, a spoofer's signals are uniform. The same
     * 1.0 dB as trust's `CN0_UNIFORM`. Indoors the spread of 5–7 satellites dips to 0.6–1.4 dB for a few seconds
     * (Pixel 8, 2026-10-02), so the largest spread over the last [spreadStatuses] statuses is used.
     */
    val minCn0StdDb: Double = 1.0,
    val spreadStatuses: Int = 5,
    /** The chip must look healthy continuously for this long before a window opens. */
    val healthyForS: Double = 10.0,
    /** Minimum time between windows; doubled after each failed window, back to this after a success. */
    val intervalS: Double = 60.0,
    val maxIntervalS: Double = 300.0,
    /**
     * Longest a window stays open. Trust accepts returning GNSS after a consistent stream of 10 s
     * (questionable, after an outage) or 15 s (rejected, with a agreeing network fix).
     */
    val windowMaxS: Double = 20.0,
    /**
     * Close when no real fix arrives within this time after opening. Measured on a Pixel 8: the first real fix came
     * 1.1–3.1 s after the provider was given back in three windows, and not within 4 s and 8 s in two others (NMEA
     * kept flowing). Until it arrives other apps have no GPS at all, so this also bounds that outage.
     */
    val noFixAbortS: Double = 12.0,
    /** `GnssStatus` older than this does not count as healthy. */
    val statusMaxAgeS: Double = 3.0,
    /**
     * Passthrough (D-070): after this many RECOVERED windows in a row the test provider stays removed, so other apps
     * get the real GPS while it is TRUSTED, healthy and agrees with us. 0 disables (every window ends with the mock
     * back).
     */
    val passthroughAfterRecovered: Int = 2,
    /** Leave passthrough when no TRUSTED real fix arrived for this long (tunnel, jamming, rejected or doubtful fixes). */
    val passthroughLossS: Double = 5.0,
    /** Leave passthrough when the chip stops looking healthy (few satellites, uniform signals) for this long. */
    val passthroughUnhealthyS: Double = 5.0,
)

enum class ProbePhase { IDLE, WINDOW, PASSTHROUGH }

/** LOST: a passthrough ended because real GPS stopped being trusted. */
enum class ProbeResult { NONE, RECOVERED, FAILED, LOST }

/** HOLD: the open window becomes passthrough (the provider stays removed). */
enum class ProbeAction { NONE, OPEN, CLOSE, HOLD }

/** What the GNSS chip says about itself; independent of the Location fixes we replace. */
data class ChipHealth(val used: Int, val meanCn0DbHz: Double?, val stdCn0Db: Double?, val healthy: Boolean)

data class ProbeStatus(
    val phase: ProbePhase,
    val health: ChipHealth?,
    /** Seconds until a window may open, or null while the chip is not healthy. */
    val nextProbeInS: Double?,
    val lastResult: ProbeResult,
    val lastResultAgoS: Double?,
)

class GnssProbeController(private val cfg: GnssProbeConfig = GnssProbeConfig()) {
    var phase = ProbePhase.IDLE
        private set
    var lastResult = ProbeResult.NONE
        private set

    private var health: ChipHealth? = null
    private var statusNs: Long? = null
    private var healthySinceNs: Long? = null
    private var intervalS = cfg.intervalS
    private var lastEndNs: Long? = null
    private var lastResultNs: Long? = null
    private var windowStartNs = 0L
    private var firstFixNs: Long? = null
    private var pending: ProbeResult? = null
    private var recoveredStreak = 0
    private var lastTrustedNs = 0L
    private var unhealthySinceNs: Long? = null
    private val recentStd = ArrayDeque<Double>()

    fun onStatus(s: GnssStatusSnapshot) {
        val used = s.sats.filter { it.usedInFix }
        val mean = if (used.isEmpty()) null else used.sumOf { it.cn0DbHz } / used.size
        val std = if (used.size < 2) null else sqrt(used.sumOf { (it.cn0DbHz - mean!!) * (it.cn0DbHz - mean) } / (used.size - 1))
        recentStd.addLast(std ?: 0.0)
        while (recentStd.size > cfg.spreadStatuses) recentStd.removeFirst()
        val spread = recentStd.max()
        val ok = used.size >= cfg.minUsedSats && mean!! >= cfg.minMeanCn0DbHz && spread >= cfg.minCn0StdDb
        health = ChipHealth(used.size, mean, std, ok)
        statusNs = s.tNs
        healthySinceNs = if (ok) healthySinceNs ?: s.tNs else null
    }

    /** Feed every GNSS trust assessment; only those of fixes delivered inside the open window count. */
    fun onAssessment(a: TrustAssessment) {
        if (phase == ProbePhase.PASSTHROUGH) {
            if (a.source != LocSource.GNSS || TrustReason.SOURCE_OVERRIDDEN in a.reasons || TrustReason.SYNTHETIC_INPUT in a.reasons) return
            if (a.state == TrustState.TRUSTED) lastTrustedNs = a.tNs
            // Hard evidence against the real GPS ends passthrough at once; ambiguous doubt waits for the loss timer.
            else if (a.state == TrustState.REJECTED && !a.reasons.all { it in AMBIGUOUS }) pending = ProbeResult.LOST
            return
        }
        if (phase != ProbePhase.WINDOW || a.source != LocSource.GNSS || a.tNs < windowStartNs) return
        if (TrustReason.SOURCE_OVERRIDDEN in a.reasons || TrustReason.SYNTHETIC_INPUT in a.reasons) return
        if (firstFixNs == null) firstFixNs = a.tNs
        pending = when {
            a.state == TrustState.TRUSTED -> ProbeResult.RECOVERED
            // Disagreeing with our estimate is ambiguous: our drift or a spoofer, and trust itself waits for a
            // consistent stream. Anything else (impossible speed, bad course, OBD mismatch...) is not.
            a.state == TrustState.REJECTED && !a.reasons.all { it in AMBIGUOUS } -> ProbeResult.FAILED
            else -> pending
        }
    }

    /** Call about once a second. OPEN: remove the test provider. CLOSE: put it back (see [lastResult]). */
    fun step(tNs: Long): ProbeAction {
        when (phase) {
            ProbePhase.IDLE -> {
                val fresh = statusNs?.let { (tNs - it) / 1e9 <= cfg.statusMaxAgeS } == true
                val since = healthySinceNs
                val waited = lastEndNs?.let { (tNs - it) / 1e9 >= intervalS } ?: true
                if (fresh && since != null && (tNs - since) / 1e9 >= cfg.healthyForS && waited) {
                    phase = ProbePhase.WINDOW
                    windowStartNs = tNs
                    firstFixNs = null
                    pending = null
                    return ProbeAction.OPEN
                }
            }
            ProbePhase.WINDOW -> {
                val elapsed = (tNs - windowStartNs) / 1e9
                val result = pending ?: when {
                    elapsed >= cfg.windowMaxS -> ProbeResult.FAILED
                    firstFixNs == null && elapsed >= cfg.noFixAbortS -> ProbeResult.FAILED
                    else -> null
                }
                if (result == ProbeResult.RECOVERED && cfg.passthroughAfterRecovered > 0 &&
                    recoveredStreak + 1 >= cfg.passthroughAfterRecovered
                ) {
                    phase = ProbePhase.PASSTHROUGH
                    recoveredStreak = 0
                    lastTrustedNs = tNs
                    unhealthySinceNs = null
                    pending = null
                    lastResult = ProbeResult.RECOVERED
                    lastResultNs = tNs
                    return ProbeAction.HOLD
                }
                if (result != null) {
                    recoveredStreak = if (result == ProbeResult.RECOVERED) recoveredStreak + 1 else 0
                    phase = ProbePhase.IDLE
                    lastResult = result
                    lastResultNs = tNs
                    lastEndNs = tNs
                    intervalS = if (result == ProbeResult.RECOVERED) cfg.intervalS else min(intervalS * 2, cfg.maxIntervalS)
                    return ProbeAction.CLOSE
                }
            }
            ProbePhase.PASSTHROUGH -> {
                val chip = health
                if (chip != null && !chip.healthy) unhealthySinceNs = unhealthySinceNs ?: tNs else unhealthySinceNs = null
                val result = pending ?: when {
                    (tNs - lastTrustedNs) / 1e9 >= cfg.passthroughLossS -> ProbeResult.LOST
                    unhealthySinceNs?.let { (tNs - it) / 1e9 >= cfg.passthroughUnhealthyS } == true -> ProbeResult.LOST
                    else -> null
                }
                if (result != null) {
                    phase = ProbePhase.IDLE
                    lastResult = result
                    lastResultNs = tNs
                    lastEndNs = tNs
                    intervalS = cfg.intervalS
                    recoveredStreak = 0
                    return ProbeAction.CLOSE
                }
            }
        }
        return ProbeAction.NONE
    }

    fun status(tNs: Long): ProbeStatus {
        val since = healthySinceNs
        val next = if (since == null) null else maxOf(
            cfg.healthyForS - (tNs - since) / 1e9,
            lastEndNs?.let { intervalS - (tNs - it) / 1e9 } ?: 0.0,
            0.0,
        )
        return ProbeStatus(phase, health, next, lastResult, lastResultNs?.let { (tNs - it) / 1e9 })
    }

    private companion object {
        val AMBIGUOUS = setOf(TrustReason.INNOVATION_GATE, TrustReason.RECOVERING)
    }
}
