package ch.ywesee.movementlogger.usb

import android.content.Context
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

/**
 * Race mode — live position uplink to the desktop app's Race tab.
 *
 * One small JSON datagram per fix, throttled to 2 Hz, fired at the
 * configured `host:port` (the desktop shows its LAN ip:port in the Race
 * tab; over cellular a relay forwarding the same datagrams works
 * unchanged). Wire format shared with iOS `RaceUplink.swift` and parsed
 * by desktop `race.rs`:
 *
 * ```json
 * {"v":1,"rider":"Zeno","src":"ublox","lat":37.3838,"lon":23.2472,
 *  "kmh":4.2,"deg":181.0,"ts":1783948000123,"batt":85}
 * ```
 *
 * UDP + fire-and-forget by design: a lost datagram is stale 500 ms
 * later anyway, and nothing here may ever stall the NMEA reader thread
 * — sends run on a dedicated single-thread executor and any failure
 * just lands in [RaceUplinkState.lastError].
 */
object RaceUplink {
    private const val TAG = "RaceUplink"
    private const val PREFS = "movement_logger_race"
    private const val MIN_SEND_INTERVAL_MS = 450L // ≤ ~2 Hz on a 5–10 Hz fix stream
    const val DEFAULT_PORT = 47777

    data class RaceUplinkState(
        val enabled: Boolean = false,
        val rider: String = "",
        val host: String = "",
        val port: Int = DEFAULT_PORT,
        val sent: Long = 0,
        val lastError: String? = null,
    )

    private val _state = MutableStateFlow(RaceUplinkState())
    val state: StateFlow<RaceUplinkState> = _state.asStateFlow()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "race-uplink").apply { isDaemon = true }
    }
    private var socket: DatagramSocket? = null
    private var resolved: InetAddress? = null
    private var lastSentAtMs = 0L
    private var appContext: Context? = null
    private var battery: BatteryManager? = null

    /** Load persisted rider/host/port (enabled always starts off — an
     *  uplink nobody is listening to just drains battery). */
    fun init(ctx: Context) {
        if (appContext != null) return
        appContext = ctx.applicationContext
        battery = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _state.update {
            it.copy(
                rider = p.getString("rider", "") ?: "",
                host = p.getString("host", "") ?: "",
                port = p.getInt("port", DEFAULT_PORT),
            )
        }
    }

    fun configure(rider: String, host: String, port: Int) {
        _state.update { it.copy(rider = rider, host = host, port = port) }
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString("rider", rider)
            ?.putString("host", host)
            ?.putInt("port", port)
            ?.apply()
        // A new target invalidates the cached DNS result.
        resolved = null
    }

    fun setEnabled(on: Boolean) {
        _state.update { it.copy(enabled = on, sent = 0, lastError = null) }
        if (!on) {
            executor.execute {
                socket?.close()
                socket = null
                resolved = null
            }
        }
    }

    /**
     * Called from [UbloxGpsCore] on every merged fix update (reader IO
     * thread). Cheap early-outs; the actual network work hops onto the
     * uplink executor.
     */
    fun maybeSend(s: UbloxUiState) {
        val cfg = _state.value
        if (!cfg.enabled || cfg.host.isBlank() || cfg.rider.isBlank()) return
        if (s.fixQuality <= 0) return
        val lat = s.latDeg ?: return
        val lon = s.lonDeg ?: return
        val now = System.currentTimeMillis()
        if (now - lastSentAtMs < MIN_SEND_INTERVAL_MS) return
        lastSentAtMs = now

        val payload = encode(
            rider = cfg.rider,
            lat = lat,
            lon = lon,
            kmh = s.speedKmh,
            deg = s.courseDeg,
            ts = now,
            batt = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1,
            // Rough horizontal accuracy from HDOP: dimensionless HDOP ×
            // ~3 m UERE. Feeds the desktop's per-rider accuracy circle.
            accM = s.hdop?.let { it * 3.0 },
            sat = s.numSat,
        )
        executor.execute {
            try {
                val addr = resolved ?: InetAddress.getByName(cfg.host).also { resolved = it }
                val sock = socket ?: DatagramSocket().also { socket = it }
                sock.send(DatagramPacket(payload, payload.size, addr, cfg.port))
                _state.update { it.copy(sent = it.sent + 1, lastError = null) }
            } catch (e: Exception) {
                Log.w(TAG, "send failed: $e")
                resolved = null // re-resolve next time (DHCP change, typo fixed…)
                _state.update { it.copy(lastError = e.message ?: e.toString()) }
            }
        }
    }

    /** JSON wire encoding — pulled out so the unit test locks the format. */
    internal fun encode(
        rider: String, lat: Double, lon: Double,
        kmh: Double?, deg: Double?, ts: Long, batt: Int,
        accM: Double? = null, sat: Int = -1,
    ): ByteArray {
        val o = JSONObject()
        o.put("v", 1)
        o.put("rider", rider)
        o.put("src", "ublox")
        o.put("lat", lat)
        o.put("lon", lon)
        if (kmh != null && kmh.isFinite()) o.put("kmh", kmh)
        if (deg != null && deg.isFinite()) o.put("deg", deg)
        o.put("ts", ts)
        if (batt >= 0) o.put("batt", batt)
        if (accM != null && accM.isFinite() && accM > 0) o.put("acc", accM)
        if (sat > 0) o.put("sat", sat)
        return o.toString().toByteArray(Charsets.UTF_8)
    }
}
