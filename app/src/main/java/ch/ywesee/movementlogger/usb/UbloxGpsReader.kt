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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
 * the CDC-ACM bulk-in pipe. Default factory rate is 1 Hz; we send a
 * `UBX-CFG-RATE` command at [start] to push the receiver to 10 Hz so
 * the live signal matches the SensorTile.box GPS log cadence (1:10
 * ratio against the 100 Hz IMU/quaternion grid — clean integer factor
 * for replay interpolation and above the ~4 Hz Nyquist floor for pump
 * detection). The reader buffers bytes into lines on `\n`, validates
 * the `*HH` checksum, and routes RMC/GGA to the sink in arrival order.
 *
 * **Self-healing**: a read error or a silent port (no bytes for
 * [STALL_NS] while a healthy receiver talks continuously) recycles the
 * connection and re-opens the device as soon as it re-enumerates with
 * permission — the loop retries until [stop] is called, so a transient
 * USB glitch mid-ride no longer kills the session until the user notices
 * and taps Connect again. Re-attach usually re-grants permission
 * automatically via the manifest's USB_DEVICE_ATTACHED filter; when it
 * doesn't, the loop waits and picks up the grant from the user's next
 * Connect tap.
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
    /** vid/pid template for the reconnect path's re-enumeration. */
    private var lastDevice: UsbDevice? = null
    /** Set by [stop] before closing the port so the read loop can tell a
     *  user-initiated close from a USB glitch (only the latter reconnects). */
    @Volatile private var stopRequested = false

    /** True while [start] has succeeded and the reader is pumping bytes
     *  (or trying to re-open the device after a glitch). */
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
        stopRequested = false
        val p = openPort(device) ?: return false
        lastDevice = device
        val msg = "opened ${device.productName} @ ${device.deviceName}"
        Log.i(TAG, msg)
        sink.onLog("reader: $msg")
        job = scope.launch { readLoop(p) }
        return true
    }

    fun stop() {
        stopRequested = true
        job?.cancel()
        job = null
        closeQuietly()
        Log.i(TAG, "stopped")
        sink.onLog("reader: stopped")
    }

    /** Probe / open / configure one device. Sets [connection]/[port] and
     *  returns the port, or null (everything closed) on any failure. */
    private fun openPort(device: UsbDevice): UsbSerialPort? {
        val driver: UsbSerialDriver? = UsbSerialProber.getDefaultProber().probeDevice(device)
        if (driver == null) {
            sink.onLog("reader: no CDC-ACM driver for ${device.productName}")
            return null
        }
        val conn = usbManager.openDevice(driver.device)
        if (conn == null) {
            sink.onLog("reader: openDevice() returned null")
            return null
        }
        val p = driver.ports.firstOrNull()
        if (p == null) {
            conn.close()
            sink.onLog("reader: driver has no ports")
            return null
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
            // Bump the receiver from its factory 1 Hz to 10 Hz. Best-
            // effort: a write failure (or a chip too old to accept the
            // command, e.g. some MAX-M8 variants when running multi-
            // GNSS) just leaves the unit at whatever rate it booted at;
            // the reader stays functional either way.
            try {
                val cfgRate = buildUbxCfgRate(measRateMs = TARGET_MEAS_RATE_MS)
                p.write(cfgRate, UBX_WRITE_TIMEOUT_MS)
                Log.i(TAG, "sent UBX-CFG-RATE for ${1000 / TARGET_MEAS_RATE_MS} Hz")
                sink.onLog("reader: requested ${1000 / TARGET_MEAS_RATE_MS} Hz")
            } catch (e: Exception) {
                Log.w(TAG, "UBX-CFG-RATE write failed", e)
                sink.onLog("reader: rate-config write failed — ${e.message}")
            }
        } catch (e: Exception) {
            try { p.close() } catch (_: Exception) {}
            conn.close()
            sink.onLog("reader: open failed — ${e.message}")
            return null
        }
        connection = conn
        port = p
        return p
    }

    private fun closeQuietly() {
        try { port?.close() } catch (_: Exception) {}
        port = null
        try { connection?.close() } catch (_: Exception) {}
        connection = null
    }

    private suspend fun readLoop(initial: UsbSerialPort) {
        var p: UsbSerialPort? = initial
        val buf = ByteArray(4096)
        val line = StringBuilder(256)
        var lastData = System.nanoTime()
        while (currentCoroutineContext().isActive) {
            if (p == null) {
                p = reopenWithRetry() ?: break   // null = stopped/cancelled
                lastData = System.nanoTime()
                line.setLength(0)                // drop any torn partial line
            }
            val n = try {
                p.read(buf, READ_TIMEOUT_MS)
            } catch (e: Exception) {
                if (stopRequested) break
                Log.w(TAG, "read error", e)
                sink.onLog("reader: read error — ${e.message}; reconnecting…")
                closeQuietly()
                p = null
                continue
            }
            val monoNs = System.nanoTime()
            if (n <= 0) {
                // A healthy receiver talks continuously (10 Hz). Prolonged
                // silence means a stale port after a USB glitch the driver
                // didn't surface as an error — recycle the connection.
                if (monoNs - lastData > STALL_NS && !stopRequested) {
                    sink.onLog("reader: no data for ${STALL_NS / 1_000_000_000} s — reconnecting…")
                    closeQuietly()
                    p = null
                }
                continue
            }
            lastData = monoNs
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

    /**
     * Poll the USB device list until the receiver is back (same vid/pid as
     * the device [start] opened) with permission, then re-open it. Returns
     * the fresh port, or null when [stop] cancelled the wait. Unbounded on
     * purpose: mid-ride the right behaviour is "keep trying until the user
     * gives up", and one enumeration per second is free.
     */
    private suspend fun reopenWithRetry(): UsbSerialPort? {
        val template = lastDevice ?: return null
        var attempt = 0
        while (currentCoroutineContext().isActive && !stopRequested) {
            delay(RECONNECT_DELAY_MS)
            attempt++
            val dev = usbManager.deviceList.values.firstOrNull {
                it.vendorId == template.vendorId && it.productId == template.productId
            }
            when {
                dev == null -> {
                    if (attempt == 1 || attempt % LOG_EVERY == 0) {
                        sink.onLog("reader: receiver not enumerated — waiting for re-attach")
                    }
                }
                !usbManager.hasPermission(dev) -> {
                    if (attempt == 1 || attempt % LOG_EVERY == 0) {
                        sink.onLog("reader: waiting for USB permission — tap Connect if asked")
                    }
                }
                else -> {
                    val p = openPort(dev)
                    if (p != null) {
                        lastDevice = dev
                        sink.onLog("reader: reconnected after $attempt attempt(s)")
                        return p
                    }
                    // openPort logged the failure; keep polling.
                }
            }
        }
        return null
    }

    private fun dispatch(rawLine: String, monoNs: Long) {
        // Per-line wire tap for off-device debugging, opt-in via
        // `adb shell setprop log.tag.UbloxGpsReader V`. Unconditional
        // Log.v flooded logd with ~40 lines/s and rolled the main buffer
        // to ~20 min, which is exactly the window you need after a ride.
        if (Log.isLoggable(TAG, Log.VERBOSE)) Log.v(TAG, rawLine)
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
        private const val UBX_WRITE_TIMEOUT_MS = 200
        /** Target measurement interval in ms. 100 = 10 Hz (matches the box). */
        private const val TARGET_MEAS_RATE_MS = 100
        /** Silent-port watchdog: a live receiver never pauses this long. */
        private const val STALL_NS = 5_000_000_000L
        private const val RECONNECT_DELAY_MS = 1_000L
        /** Throttle for the reconnect loop's status lines (~15 s apart). */
        private const val LOG_EVERY = 15

        /**
         * Build a UBX-CFG-RATE frame (class 0x06, id 0x08) requesting the
         * given measurement interval, 1 nav cycle per measurement, and
         * GPS time reference. Two-byte Fletcher-8 checksum (CK_A/CK_B)
         * is computed over class+id+length+payload per the u-blox spec.
         */
        internal fun buildUbxCfgRate(measRateMs: Int): ByteArray {
            val payload = byteArrayOf(
                (measRateMs and 0xFF).toByte(),
                ((measRateMs ushr 8) and 0xFF).toByte(),
                0x01, 0x00,  // navRate: 1 nav solution per measurement
                0x01, 0x00,  // timeRef: 1 = GPS time
            )
            return buildUbxFrame(classId = 0x06, msgId = 0x08, payload = payload)
        }

        private fun buildUbxFrame(classId: Int, msgId: Int, payload: ByteArray): ByteArray {
            val len = payload.size
            val body = ByteArray(4 + len)
            body[0] = (classId and 0xFF).toByte()
            body[1] = (msgId and 0xFF).toByte()
            body[2] = (len and 0xFF).toByte()
            body[3] = ((len ushr 8) and 0xFF).toByte()
            System.arraycopy(payload, 0, body, 4, len)
            var ckA = 0
            var ckB = 0
            for (b in body) {
                ckA = (ckA + (b.toInt() and 0xFF)) and 0xFF
                ckB = (ckB + ckA) and 0xFF
            }
            val out = ByteArray(2 + body.size + 2)
            out[0] = 0xB5.toByte()
            out[1] = 0x62.toByte()
            System.arraycopy(body, 0, out, 2, body.size)
            out[out.size - 2] = ckA.toByte()
            out[out.size - 1] = ckB.toByte()
            return out
        }
    }
}
