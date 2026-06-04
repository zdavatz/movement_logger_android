package ch.ywesee.movementlogger.usb

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground reader for the u-blox GNSS receiver over USB CDC-ACM. Owns
 * one [UsbSerialPort], spins a single read loop on [Dispatchers.IO], and
 * fans out parsed fixes to a [Sink] callback.
 *
 * No StateFlow here — the singleton owner ([UbloxGpsCore]) does that.
 * Keeping the reader minimal and callback-driven makes it easy to unit
 * test the NMEA path without an Android USB device.
 *
 * Wire format: u-blox emits ASCII NMEA sentences terminated by CR LF on
 * the CDC-ACM bulk-in pipe. Default rate is 1 Hz (`UBX-CFG-RATE` can
 * push it higher; we don't reconfigure here). The reader buffers bytes
 * into lines on `\n`, validates the `*HH` checksum, and routes RMC/GGA
 * to the sink in arrival order.
 */
class UbloxGpsReader(
    private val usbManager: UsbManager,
    private val sink: Sink,
) {

    interface Sink {
        /** Called for every successfully-checksum'd RMC sentence. */
        fun onRmc(fix: RmcFix, monoNanos: Long)
        /** Called for every successfully-checksum'd GGA sentence. */
        fun onGga(fix: GgaFix, monoNanos: Long)
        /** Lifecycle / error reports for the UI status line. */
        fun onLog(message: String)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null

    /** True while [start] has succeeded and the reader is pumping bytes. */
    val isRunning: Boolean get() = job?.isActive == true

    /**
     * Open the named device, claim its CDC-ACM interface, and start the
     * read loop. Caller must already have USB permission for [device]
     * (use [UsbManager.requestPermission] first — see [UbloxGpsCore]).
     *
     * Idempotent: a subsequent call without a prior [stop] is rejected
     * with a log line and false return.
     */
    fun start(device: UsbDevice): Boolean {
        if (isRunning) {
            sink.onLog("reader: already running")
            return false
        }
        val driver: UsbSerialDriver? = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            sink.onLog("reader: no CDC-ACM driver for ${device.productName}")
            return false
        }
        val conn = usbManager.openDevice(driver.device)
        if (conn == null) {
            sink.onLog("reader: openDevice() returned null")
            return false
        }
        val p = driver.ports.firstOrNull()
        if (p == null) {
            conn.close()
            sink.onLog("reader: driver has no ports")
            return false
        }
        try {
            p.open(conn)
            // u-blox default UART rate is 38400 8-N-1; on USB CDC the
            // SetLineCoding is largely cosmetic but a few firmwares
            // reject pulled-data until line coding lands, so we still set
            // it explicitly.
            p.setParameters(38400, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            p.dtr = true
            p.rts = true
        } catch (e: Exception) {
            try { p.close() } catch (_: Exception) {}
            conn.close()
            sink.onLog("reader: open failed — ${e.message}")
            return false
        }
        connection = conn
        port = p
        val msg = "opened ${device.productName} @ ${device.deviceName}"
        Log.i(TAG, msg)
        sink.onLog("reader: $msg")
        job = scope.launch { readLoop(p) }
        return true
    }

    fun stop() {
        job?.cancel()
        job = null
        try { port?.close() } catch (_: Exception) {}
        port = null
        try { connection?.close() } catch (_: Exception) {}
        connection = null
        Log.i(TAG, "stopped")
        sink.onLog("reader: stopped")
    }

    private suspend fun readLoop(port: UsbSerialPort) {
        val buf = ByteArray(4096)
        val line = StringBuilder(256)
        while (scope.isActive) {
            val n = try {
                port.read(buf, READ_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.w(TAG, "read error", e)
                sink.onLog("reader: read error — ${e.message}")
                break
            }
            if (n <= 0) continue
            val monoNs = System.nanoTime()
            for (i in 0 until n) {
                val b = buf[i].toInt() and 0xFF
                when (b) {
                    0x0A -> {
                        if (line.isNotEmpty()) {
                            dispatch(line.toString(), monoNs)
                            line.setLength(0)
                        }
                    }
                    0x0D -> { /* swallow CR */ }
                    else -> {
                        if (line.length < MAX_LINE_LEN) line.append(b.toChar())
                        // Hard cap on line length to defend against a
                        // stuck stream — drop and re-sync on next '\n'.
                    }
                }
            }
        }
    }

    private fun dispatch(rawLine: String, monoNs: Long) {
        // Verbose: every raw NMEA line ends up in logcat for off-device
        // debugging. Filter `UbloxGpsReader:V` to see them; default level
        // is INFO so this is essentially free in release builds.
        Log.v(TAG, rawLine)
        val payload = Nmea.verifyAndStrip(rawLine) ?: return
        // payload like "GPRMC,..." or "GNGGA,...". The 2-char talker
        // (GN/GP/GL/GA/BD) precedes the 3-char message ID, so match on
        // the comma-delimited first field's suffix.
        val firstField = payload.substringBefore(',')
        when {
            firstField.endsWith("RMC") -> {
                Nmea.parseRmc(payload)?.let { sink.onRmc(it, monoNs) }
            }
            firstField.endsWith("GGA") -> {
                Nmea.parseGga(payload)?.let { sink.onGga(it, monoNs) }
            }
            // GSA / GSV / VTG / GLL / ZDA — ignored. Could surface sat-by-sat
            // SNR later, not interesting for the on-screen Hz counter.
        }
    }

    companion object {
        private const val READ_TIMEOUT_MS = 250
        private const val MAX_LINE_LEN = 256
        private const val TAG = "UbloxGpsReader"
    }
}
