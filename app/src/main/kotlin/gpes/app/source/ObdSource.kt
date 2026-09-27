package gpes.app.source

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.SystemClock
import gpes.core.model.DriveRecord
import gpes.core.model.Measurement
import gpes.core.obd.Elm327
import gpes.core.obd.ElmTransport
import gpes.core.obd.PollResult
import gpes.core.pipeline.MeasurementSink
import gpes.core.pipeline.MeasurementSource
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

enum class ObdState { CONNECTING, INITIALIZING, POLLING, RETRYING, STOPPED }

/** Snapshot for the UI (written by the OBD thread, read by the status ticker). */
data class ObdStatus(
    val state: ObdState,
    val device: String,
    val version: String? = null,
    val protocol: String? = null,
    val lastKmh: Int? = null,
    val samples: Long = 0,
    val noData: Long = 0,
    val error: String? = null,
)

/**
 * Vehicle speed from an ELM327 Bluetooth Classic (SPP) adapter, e.g. the cheap "ELM327 Mini
 * v1.5/v2.1" clones. The adapter must be paired in Android settings first (PIN is usually 1234 or
 * 0000).
 *
 * It runs its own blocking I/O thread and posts measurements through [post], which delivers them
 * on the pipeline thread. Raw exchanges go straight to [record] (the thread-safe recorder). It
 * reconnects automatically with backoff. Read-only: it only sends AT configuration commands and
 * Mode 01 requests.
 */
class ObdSource(
    context: Context,
    private val address: String,
    private val post: (Measurement) -> Unit,
    private val record: (DriveRecord) -> Unit,
    private val minPeriodMs: Long = 100,
) : MeasurementSource {
    private val adapter: BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter
    @Volatile private var running = false
    @Volatile private var socket: BluetoothSocket? = null
    private var thread: Thread? = null

    @Volatile var status = ObdStatus(ObdState.STOPPED, address)
        private set

    override fun start(sink: MeasurementSink) {
        running = true
        thread = Thread({ loop() }, "gpes-obd").also { it.isDaemon = true; it.start() }
    }

    override fun stop() {
        running = false
        runCatching { socket?.close() }
        thread?.interrupt()
        status = status.copy(state = ObdState.STOPPED)
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        var backoffMs = 1000L
        while (running) {
            try {
                val dev = adapter?.getRemoteDevice(address) ?: error("Bluetooth unavailable")
                status = ObdStatus(ObdState.CONNECTING, dev.name ?: address)
                val s = connect(dev)
                socket = s
                status = status.copy(state = ObdState.INITIALIZING, error = null)
                val elm = Elm327(SocketTransport(s.inputStream, s.outputStream), { SystemClock.elapsedRealtimeNanos() }, record)
                val info = elm.init()
                status = status.copy(state = ObdState.POLLING, version = info.version, protocol = info.protocol)
                backoffMs = 1000L
                var timeouts = 0
                while (running) {
                    val t0 = SystemClock.elapsedRealtime()
                    when (val r = elm.pollSpeed()) {
                        is PollResult.Speed -> {
                            timeouts = 0
                            post(r.m)
                            status = status.copy(lastKmh = Math.round(r.m.speedMps * 3.6).toInt(), samples = status.samples + 1)
                        }
                        is PollResult.NoData -> { timeouts = 0; status = status.copy(noData = status.noData + 1) }
                        PollResult.Timeout -> if (++timeouts >= 3) error("adapter stopped responding")
                    }
                    val dt = SystemClock.elapsedRealtime() - t0
                    if (dt < minPeriodMs) Thread.sleep(minPeriodMs - dt)
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                status = status.copy(state = ObdState.RETRYING, error = e.message ?: e.javaClass.simpleName)
            } finally {
                runCatching { socket?.close() }
                socket = null
            }
            if (!running) break
            try { Thread.sleep(backoffMs) } catch (_: InterruptedException) { break }
            backoffMs = (backoffMs * 2).coerceAtMost(30_000)
        }
        status = status.copy(state = ObdState.STOPPED)
    }

    /** SPP connect with the usual fallbacks for clones (insecure socket, then channel 1 via reflection). */
    @SuppressLint("MissingPermission")
    private fun connect(dev: BluetoothDevice): BluetoothSocket {
        runCatching { adapter?.cancelDiscovery() }
        val attempts = listOf<() -> BluetoothSocket>(
            { dev.createRfcommSocketToServiceRecord(SPP) },
            { dev.createInsecureRfcommSocketToServiceRecord(SPP) },
            { dev.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(dev, 1) as BluetoothSocket },
        )
        var last: Exception? = null
        for (make in attempts) {
            val s = runCatching { make() }.getOrElse { last = it as? Exception; null } ?: continue
            try {
                s.connect()
                return s
            } catch (e: Exception) {
                last = e
                runCatching { s.close() }
            }
        }
        throw last ?: IllegalStateException("cannot connect")
    }

    private class SocketTransport(private val input: InputStream, private val output: OutputStream) : ElmTransport {
        override fun write(command: String) {
            // Drop any stale bytes from a previous timed-out command.
            while (input.available() > 0) input.read(ByteArray(input.available()))
            output.write((command + "\r").toByteArray(Charsets.US_ASCII))
            output.flush()
        }

        override fun readUntilPrompt(timeoutMs: Long): String? {
            val sb = StringBuilder()
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            val buf = ByteArray(256)
            while (SystemClock.elapsedRealtime() < deadline) {
                if (input.available() > 0) {
                    val n = input.read(buf)
                    for (i in 0 until n) {
                        val c = buf[i].toInt().toChar()
                        if (c == '>') return sb.toString()
                        if (c != '\u0000') sb.append(c)
                    }
                } else {
                    Thread.sleep(5)
                }
            }
            return null
        }
    }

    private companion object {
        val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
