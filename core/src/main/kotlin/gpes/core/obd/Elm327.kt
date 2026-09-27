package gpes.core.obd

import gpes.core.model.ObdExchange
import gpes.core.model.VehicleSpeedMeasurement

/** Byte-stream link to an ELM327 (Bluetooth SPP on Android, a fake in tests). Blocking. */
interface ElmTransport {
    fun write(command: String)

    /** Read until the '>' prompt or timeout; returns the text before the prompt, or null on timeout. */
    fun readUntilPrompt(timeoutMs: Long): String?
}

data class ElmInfo(val version: String, val protocol: String?, val countSuffix: Boolean)

sealed interface PollResult {
    data class Speed(val m: VehicleSpeedMeasurement) : PollResult
    /** The adapter answered but without the value (NO DATA, STOPPED, CAN ERROR, ?…). */
    data class NoData(val response: String) : PollResult
    /** No answer at all: the link is probably dead. */
    data object Timeout : PollResult
}

/**
 * Minimal ELM327 client for vehicle speed (Mode 01 PID 0D). It is written for cheap clones:
 * commands a clone does not know are tolerated, and the "response count" suffix (`010D1`, which
 * makes the adapter return right after the first ECU answers instead of waiting its timeout) is
 * probed and dropped if unsupported.
 *
 * Timestamps are elapsedRealtimeNanos from [clockNs]. A speed sample is stamped at the **midpoint**
 * of request and response: adapter latency is typically 50–200 ms, which is 1–3 m at city speed.
 * Every exchange is reported to [log] so real adapters can be debugged from recordings.
 */
class Elm327(
    private val io: ElmTransport,
    private val clockNs: () -> Long,
    private val log: (ObdExchange) -> Unit = {},
    private val source: String = "obd:elm327",
) {
    var info: ElmInfo? = null
        private set
    private var countSuffix = true

    private fun exchange(cmd: String, timeoutMs: Long): Pair<String?, Long> {
        val t0 = clockNs()
        io.write(cmd)
        val resp = io.readUntilPrompt(timeoutMs)
        val t1 = clockNs()
        log(ObdExchange(t0, cmd, resp?.let(::clean), ((t1 - t0) / 1_000_000).toInt()))
        return resp?.let(::clean) to (t0 + t1) / 2
    }

    /** Reset and configure. Throws [IllegalStateException] if the adapter does not respond at all. */
    fun init(): ElmInfo {
        val (reset, _) = exchange("ATZ", 5000)
        checkNotNull(reset) { "no response to ATZ" }
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0", "ATAT2")) exchange(c, 2000) // unknown ones answer "?"
        // First PID request triggers protocol detection ("SEARCHING..." can take several seconds).
        val (first, _) = exchange("0100", 15000)
        if (first == null || !first.replace(" ", "").contains("4100")) {
            throw IllegalStateException("ECU not responding to 0100: ${first ?: "timeout"}")
        }
        val (proto, _) = exchange("ATDPN", 2000)
        val (probe, _) = exchange("010D1", 2000)
        countSuffix = probe != null && parseSpeedKmh(probe) != null
        val version = reset.lines().map { it.trim() }.lastOrNull { it.startsWith("ELM", ignoreCase = true) } ?: reset.trim()
        return ElmInfo(version, proto?.trim(), countSuffix).also { info = it }
    }

    fun pollSpeed(timeoutMs: Long = 1000): PollResult {
        val (resp, mid) = exchange(if (countSuffix) "010D1" else "010D", timeoutMs)
        resp ?: return PollResult.Timeout
        val kmh = parseSpeedKmh(resp) ?: return PollResult.NoData(resp)
        // PID 0D is an integer km/h: quantization ±0.5 km/h (σ≈0.08 m/s) plus timing jitter.
        return PollResult.Speed(VehicleSpeedMeasurement(mid, kmh / 3.6, SPEED_STD_MPS, source))
    }

    companion object {
        const val SPEED_STD_MPS = 0.3

        /** Strip echo artefacts, prompts, "SEARCHING...", and normalize line endings. */
        fun clean(raw: String): String = raw.replace("\r", "\n").lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("SEARCHING", ignoreCase = true) && it != ">" }
            .joinToString("\n")

        /** Parse the first "41 0D XX" in a response (spaces/headers tolerated). */
        fun parseSpeedKmh(resp: String): Int? {
            for (line in resp.lines()) {
                val hex = line.replace(" ", "").uppercase()
                val i = hex.indexOf("410D")
                if (i >= 0 && hex.length >= i + 6) return hex.substring(i + 4, i + 6).toIntOrNull(16)
            }
            return null
        }
    }
}
