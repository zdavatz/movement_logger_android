package ch.ywesee.movementlogger.usb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** One tracked rider on the race map — latest fix + live data + a trail. */
data class RaceRider(
    val id: String,
    val lat: Double = Double.NaN,
    val lon: Double = Double.NaN,
    val kmh: Double = Double.NaN,
    val deg: Double = Double.NaN,
    val accM: Double = Double.NaN,
    val batt: Int? = null,
    val hasBoard: Boolean = false,
    val pitch: Double = Double.NaN,
    val roll: Double = Double.NaN,
    val yaw: Double = Double.NaN,
    val waterTempC: Double? = null,
    val altM: Double = Double.NaN,
    val lastUpdate: Long = 0L,
    /** (lat, lon) trail points. */
    val trail: List<Pair<Double, Double>> = emptyList(),
) {
    val hasFix: Boolean get() = lat.isFinite() && lon.isFinite()
    fun isStale(now: Long): Boolean = now - lastUpdate > 12_000
}

/**
 * Live multi-rider race map source — a relay *viewer* that subscribes to the
 * (user-configurable) `race-relay` and tracks every rider streaming to it:
 * position from race fixes, board angles from `typ:"board"` snapshots, merged by
 * rider name. The Android peer of the iOS `RaceViewer` and the desktop Race tab.
 * Because the wire format + relay are shared, iOS, Android and desktop riders
 * all appear together.
 */
object RaceViewer {
    private const val TRAIL_MAX = 1500          // ~5 min at 5 Hz
    private const val TRAIL_MIN_STEP_M = 3.0    // dead-band
    private const val MAX_TRAIL_ACC_M = 20.0    // worse fixes: dot only, no trail

    private val _riders = MutableStateFlow<Map<String, RaceRider>>(emptyMap())
    val riders: StateFlow<Map<String, RaceRider>> = _riders.asStateFlow()

    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var running = false
    @Volatile private var host = ""
    @Volatile private var port = 47777
    @Volatile private var token = ""

    fun start(host: String, port: Int, token: String) {
        stop()
        if (host.isBlank() || port <= 0) return
        this.host = host; this.port = port; this.token = token
        running = true
        val sock = try { DatagramSocket() } catch (e: Exception) { running = false; return }
        socket = sock
        subscribe()
        thread(isDaemon = true, name = "race-viewer-rx") {
            val buf = ByteArray(2048)
            while (running) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    ingest(String(pkt.data, 0, pkt.length, Charsets.UTF_8))
                } catch (e: Exception) {
                    if (!running) break     // closed by stop()
                }
            }
        }
        thread(isDaemon = true, name = "race-viewer-ka") {
            while (running) {
                subscribe()
                try { Thread.sleep(8000) } catch (e: InterruptedException) { break }
            }
        }
    }

    fun stop() {
        running = false
        socket?.close(); socket = null
    }

    fun clear() { _riders.value = emptyMap() }

    private fun subscribe() {
        val sock = socket ?: return
        try {
            val b = JSONObject().put("v", 1).put("sub", true).put("race", token)
                .toString().toByteArray(Charsets.UTF_8)
            sock.send(DatagramPacket(b, b.size, InetAddress.getByName(host), port))
        } catch (e: Exception) { /* transient */ }
    }

    private fun ingest(json: String) {
        val o = try { JSONObject(json) } catch (e: Exception) { return }
        val rider = o.optString("rider", "")
        if (rider.isBlank()) return
        val now = System.currentTimeMillis()
        val isBoard = o.optString("typ", "") == "board"
        _riders.update { map ->
            val prev = map[rider] ?: RaceRider(id = rider)
            var lat = prev.lat; var lon = prev.lon; var acc = prev.accM; var trail = prev.trail
            if (o.has("lat") && o.has("lon")) {
                val nlat = o.optDouble("lat"); val nlon = o.optDouble("lon")
                if (nlat.isFinite() && nlon.isFinite()) {
                    acc = if (o.has("acc")) o.optDouble("acc") else Double.NaN
                    trail = if (prev.lat.isFinite()) {
                        val moved = distM(prev.lat, prev.lon, nlat, nlon)
                        if (moved >= TRAIL_MIN_STEP_M && (acc.isNaN() || acc <= MAX_TRAIL_ACC_M)) {
                            (prev.trail + (nlat to nlon)).let { if (it.size > TRAIL_MAX) it.takeLast(TRAIL_MAX) else it }
                        } else prev.trail
                    } else listOf(nlat to nlon)
                    lat = nlat; lon = nlon
                }
            }
            map + (rider to prev.copy(
                lat = lat, lon = lon, accM = acc, trail = trail,
                kmh = if (o.has("kmh")) o.optDouble("kmh") else prev.kmh,
                deg = if (o.has("deg")) o.optDouble("deg") else prev.deg,
                batt = if (o.has("batt")) o.optInt("batt").takeIf { it >= 0 } else prev.batt,
                hasBoard = prev.hasBoard || isBoard,
                pitch = if (isBoard && o.has("p")) o.optDouble("p") else prev.pitch,
                roll = if (isBoard && o.has("r")) o.optDouble("r") else prev.roll,
                yaw = if (isBoard && o.has("y")) o.optDouble("y") else prev.yaw,
                waterTempC = if (isBoard && o.has("wt")) o.optDouble("wt") else prev.waterTempC,
                altM = if (isBoard && o.has("alt")) o.optDouble("alt") else prev.altM,
                lastUpdate = now,
            ))
        }
    }

    fun distM(la1: Double, lo1: Double, la2: Double, lo2: Double): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(la2 - la1)
        val dLon = Math.toRadians(lo2 - lo1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(la1)) * cos(Math.toRadians(la2)) * sin(dLon / 2).pow(2)
        return 2 * R * atan2(sqrt(a), sqrt(1 - a))
    }
}
