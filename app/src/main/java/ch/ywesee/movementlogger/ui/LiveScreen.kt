package ch.ywesee.movementlogger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.ywesee.movementlogger.ble.LiveSample
import ch.ywesee.movementlogger.sync.AgentConfig
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Live tab — renders the most recent SensorStream snapshot at 0.5 Hz.
 *
 * Mirrors the desktop `ui_live_tab` in `stbox-viz-gui/src/main.rs`:
 * status strip, six-row readout grid (Accel / Gyro / Mag / Baro / GPS /
 * GPS aux / Flags), two sparklines (acc magnitude, pressure). Gated on
 * already being connected via the Sync tab — Connect involves scan +
 * device picking that doesn't fit nicely above a 6-row readout.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScreen(onGoToSync: () -> Unit) {
    val vm: FileSyncViewModel = viewModel()
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        // Outer MainNav Scaffold already insets for the status bar — zero
        // here so the title isn't pushed down by a doubled inset.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("Live") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { padding ->
        LiveContent(state, onGoToSync, Modifier.padding(padding))
    }
}

@Composable
private fun LiveContent(
    state: FileSyncUiState,
    onGoToSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            "SensorStream — 0.5 Hz packed all-sensor snapshot (IMU + mag + baro + GPS).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (state.connection != Connection.Connected) {
            Spacer(Modifier.height(24.dp))
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Not connected.", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Open the Sync tab, run Scan, and Connect to a box (PIN 123456). " +
                        "The live stream starts automatically — no extra button needed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = onGoToSync) { Text("Go to Sync") }
            }
            return@Column
        }

        Spacer(Modifier.height(12.dp))
        FreshnessStrip(state.live)
        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        val sample = state.live.latestSample
        if (sample == null) {
            Text(
                "Waiting for first SensorStream notify…",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        val ctx = LocalContext.current
        var magOffset by remember { mutableStateOf(AgentConfig.magOffset(ctx)) }

        ReadoutGrid(sample, magOffset)

        Spacer(Modifier.height(16.dp))
        OrientationSection(sample, magOffset, onCalibrated = { magOffset = it })

        Spacer(Modifier.height(16.dp))
        Text("Acc magnitude (g)", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Sparkline(
            points = state.live.accHistory,
            color = Color(0xFF50B4FA),
            yRange = 0.5 to 1.5,
        )

        Spacer(Modifier.height(12.dp))
        Text("Pressure (hPa)", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Sparkline(
            points = state.live.pressureHistory,
            color = Color(0xFFFAB450),
            yRange = 980.0 to 1030.0,
        )
    }
}

/**
 * Top status strip — "N samples received" on the left, freshness label on
 * the right. Recomposes every 250 ms via a wall-clock tick so the
 * "X ms ago" label keeps ticking between samples (which arrive at 0.5 Hz).
 */
@Composable
private fun FreshnessStrip(live: LiveState) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(250)
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("${live.sampleCount} samples received", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        val t = live.latestSampleAtMs
        when {
            t == null -> Text(
                "waiting for first SensorStream notify…",
                color = Color(0xFFB58B00),
                style = MaterialTheme.typography.bodySmall,
            )
            nowMs - t < 5_000 -> Text(
                "last sample ${nowMs - t} ms ago",
                color = Color(0xFF2E7D32),
                style = MaterialTheme.typography.bodySmall,
            )
            else -> Text(
                "no sample for ${(nowMs - t) / 1000} s — check connection",
                color = Color(0xFFB58B00),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ReadoutGrid(s: LiveSample, magOffset: FloatArray?) {
    val accG = { i: Int -> s.accMg[i] / 1000f }
    val gyroDps = { i: Int -> s.gyroCdps[i] / 100f }
    val presHpa = s.pressurePa / 100.0
    val tempC = s.temperatureCc / 100f

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ReadoutRow("Accel (g)",
                "X %+.3f".format(accG(0)),
                "Y %+.3f".format(accG(1)),
                "Z %+.3f".format(accG(2)),
            )
            ReadoutRow("Gyro (°/s)",
                "X %+.2f".format(gyroDps(0)),
                "Y %+.2f".format(gyroDps(1)),
                "Z %+.2f".format(gyroDps(2)),
            )
            ReadoutRow("Mag (mG)",
                "X %+d".format(s.magMg[0].toInt()),
                "Y %+d".format(s.magMg[1].toInt()),
                "Z %+d".format(s.magMg[2].toInt()),
            )
            ReadoutRow("Angles (°)",
                "Roll %+6.1f".format(s.rollDeg()),
                "Pitch %+6.1f".format(s.pitchDeg()),
                "Yaw %5.1f".format(s.headingDeg(magOffset)),
            )
            ReadoutRow("Baro",
                "%.2f hPa".format(presHpa),
                "%+.2f °C".format(tempC),
                "",
            )
            val ll = s.latLonDeg()
            if (ll != null) {
                ReadoutRow("GPS",
                    "%.6f°".format(ll.first),
                    "%.6f°".format(ll.second),
                    "${s.gpsAltM} m",
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LabelCell("GPS")
                    Text("no fix", color = Color(0xFFB58B00), modifier = Modifier.weight(3f))
                }
            }
            ReadoutRow("GPS aux",
                "${s.gpsNsat} sats",
                "fix-q ${s.gpsFixQ}",
                "%.2f km/h  (%.1f°)".format(
                    s.gpsSpeedCmh.toDouble() / 100.0,
                    s.gpsCourseCdeg.toDouble() / 100.0,
                ),
            )
            ReadoutRow("GPS C/N0",
                if (s.gpsCn0Max > 0) "${s.gpsCn0Max} dB-Hz max" else "—",
                when {
                    s.gpsCn0Max == 0  -> "no GSV / no data"
                    s.gpsCn0Max >= 40 -> "good antenna"
                    s.gpsCn0Max >= 30 -> "ok"
                    else              -> "weak signal"
                },
                "",
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                LabelCell("Flags")
                Row(Modifier.weight(3f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlagChip("gps_valid", s.gpsValid)
                    FlagChip("logging", s.loggingActive)
                    FlagChip("low_batt", s.lowBattery)
                }
            }
        }
    }
}

@Composable
private fun ReadoutRow(label: String, a: String, b: String, c: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LabelCell(label)
        Text(a, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Text(b, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Text(c, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}

@Composable
private fun LabelCell(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
}

@Composable
private fun FlagChip(label: String, on: Boolean) {
    Text(
        label,
        color = if (on) Color(0xFF2E7D32) else Color(0xFF888888),
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        fontSize = 12.sp,
    )
}

/**
 * "Box orientation" section — desktop v0.0.47 parity: calibrate button +
 * stored hard-iron offset + how-to text + the 3D wireframe. Calibration
 * collects per-axis mag min/max for 30 s while the user tumbles the box;
 * the midpoints become the offset (persisted via [AgentConfig]).
 */
@Composable
private fun OrientationSection(
    s: LiveSample,
    magOffset: FloatArray?,
    onCalibrated: (FloatArray) -> Unit,
) {
    val ctx = LocalContext.current
    var calUntilMs by remember { mutableStateOf<Long?>(null) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val calMin = remember { floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE) }
    val calMax = remember { floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE) }

    // Fold each new sample into the min/max envelope while calibrating.
    // LiveSample holds arrays (reference equality), so every notify is a
    // fresh instance and re-triggers this effect.
    LaunchedEffect(s) {
        if (calUntilMs != null) {
            for (i in 0..2) {
                val v = s.magMg[i].toFloat()
                if (v < calMin[i]) calMin[i] = v
                if (v > calMax[i]) calMax[i] = v
            }
        }
    }
    // Countdown + completion.
    LaunchedEffect(calUntilMs) {
        while (calUntilMs != null) {
            nowMs = System.currentTimeMillis()
            val until = calUntilMs ?: break
            if (nowMs >= until) {
                val off = FloatArray(3) { (calMin[it] + calMax[it]) / 2f }
                AgentConfig.setMagOffset(ctx, off)
                onCalibrated(off)
                calUntilMs = null
                break
            }
            delay(250)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Box orientation", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(12.dp))
        val until = calUntilMs
        if (until != null) {
            Text(
                "calibrating — tumble the box… ${((until - nowMs).coerceAtLeast(0) + 999) / 1000}s",
                color = Color(0xFFB58B00),
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            OutlinedButton(onClick = {
                for (i in 0..2) {
                    calMin[i] = Float.MAX_VALUE
                    calMax[i] = -Float.MAX_VALUE
                }
                calUntilMs = System.currentTimeMillis() + 30_000
            }) { Text("Calibrate compass (30 s)") }
        }
    }
    if (calUntilMs == null && magOffset != null) {
        Text(
            "offset [%+.0f %+.0f %+.0f] mG".format(magOffset[0], magOffset[1], magOffset[2]),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))
    Text(
        "How to calibrate: tap the button, then rotate the box slowly flat on " +
            "the table through one full circle — pause about 3 s per quarter " +
            "turn (the box sends one sample every 2 s). Then briefly tip it on " +
            "its nose and on its side. The offset is stored and survives " +
            "restarts. Calibrate away from laptops, speakers and steel " +
            "surfaces; re-calibrate if the arrow stops following the rotation.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    OrientationBoxCanvas(
        pitchDeg = s.pitchDeg(),
        rollDeg = s.rollDeg(),
        headingDeg = s.headingDeg(magOffset),
    )
}

/**
 * Wireframe cuboid rotated by the live eCompass angles — desktop
 * `draw_orientation_box` parity. Body frame is NED (x forward, y right,
 * z down) with the box's LONG side on body Y; rotation body->world is
 * Rz(yaw)·Ry(pitch)·Rx(roll), viewed from the south at ~28° elevation
 * (orthographic). Blue lid, green nose arrow, fixed N/E ground cross.
 */
@Composable
private fun OrientationBoxCanvas(pitchDeg: Double, rollDeg: Double, headingDeg: Double) {
    val textMeasurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.bodyMedium.copy(color = labelColor)
    val sideColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lidColor = Color(0xFF50B4FA)
    val noseColor = Color(0xFF43A047)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(200.dp),
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val scale = size.height / 3.2f

        val sr = sin(Math.toRadians(rollDeg)).toFloat()
        val cr = cos(Math.toRadians(rollDeg)).toFloat()
        val sp = sin(Math.toRadians(pitchDeg)).toFloat()
        val cp = cos(Math.toRadians(pitchDeg)).toFloat()
        val sy = sin(Math.toRadians(headingDeg)).toFloat()
        val cyw = cos(Math.toRadians(headingDeg)).toFloat()

        // body -> world (NED): Rz(yaw) * Ry(pitch) * Rx(roll)
        fun rot(v: FloatArray): FloatArray {
            var x = v[0]; var y = v[1]; var z = v[2]
            val y1 = y * cr - z * sr; val z1 = y * sr + z * cr   // Rx
            val x2 = x * cp + z1 * sp; val z2 = -x * sp + z1 * cp // Ry
            return floatArrayOf(x2 * cyw - y1 * sy, x2 * sy + y1 * cyw, z2) // Rz
        }
        // Orthographic camera looking north from the south, 28° elevation.
        val el = Math.toRadians(28.0)
        val sel = sin(el).toFloat(); val cel = cos(el).toFloat()
        fun project(w: FloatArray): Offset =
            Offset(cx + w[1] * scale, cy - (w[0] * sel - w[2] * cel) * scale)
        fun p3(v: FloatArray): Offset = project(rot(v))

        // Fixed ground-plane compass cross (world frame).
        drawLine(axisColor, project(floatArrayOf(1.35f, 0f, 0f)), project(floatArrayOf(-1.35f, 0f, 0f)), 2f)
        drawLine(axisColor, project(floatArrayOf(0f, 1.35f, 0f)), project(floatArrayOf(0f, -1.35f, 0f)), 2f)
        val n = project(floatArrayOf(1.5f, 0f, 0f))
        val e = project(floatArrayOf(0f, 1.5f, 0f))
        drawText(textMeasurer, "N", topLeft = Offset(n.x - 6f, n.y - 20f), style = labelStyle)
        drawText(textMeasurer, "E", topLeft = Offset(e.x + 4f, e.y - 10f), style = labelStyle)

        // Cuboid — long side is body Y. z down in NED: lid has z = -hz.
        val hx = 0.62f; val hy = 1.0f; val hz = 0.28f
        val v = arrayOf(
            floatArrayOf(hx, hy, -hz), floatArrayOf(hx, -hy, -hz),
            floatArrayOf(-hx, -hy, -hz), floatArrayOf(-hx, hy, -hz),   // lid
            floatArrayOf(hx, hy, hz), floatArrayOf(hx, -hy, hz),
            floatArrayOf(-hx, -hy, hz), floatArrayOf(-hx, hy, hz),     // bottom
        )
        val pts = v.map { p3(it) }
        for ((a, b) in listOf(4 to 5, 5 to 6, 6 to 7, 7 to 4, 0 to 4, 1 to 5, 2 to 6, 3 to 7)) {
            drawLine(sideColor, pts[a], pts[b], 2.5f)
        }
        for ((a, b) in listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0)) {
            drawLine(lidColor, pts[a], pts[b], 4f)
        }
        // Nose arrow along the long (+y) end of the lid.
        val lidC = p3(floatArrayOf(0f, 0f, -hz))
        val nose = p3(floatArrayOf(0f, hy * 1.25f, -hz))
        drawLine(noseColor, lidC, nose, 4f)
        val ang = atan2(nose.y - lidC.y, nose.x - lidC.x)
        val ah = 14f
        for (side in floatArrayOf(1f, -1f)) {
            val a2 = ang + side * 2.6f
            drawLine(
                noseColor, nose,
                Offset(nose.x + ah * cos(a2), nose.y + ah * sin(a2)), 4f,
            )
        }
    }
}

/**
 * Bare polyline sparkline. Y is clamped to `yRange`; X auto-scales to the
 * full point set so the line drifts left as new samples arrive (matches the
 * desktop `draw_sparkline`).
 */
@Composable
private fun Sparkline(
    points: List<LivePoint>,
    color: Color,
    yRange: Pair<Double, Double>,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        // Subtle baseline so an empty panel doesn't look broken.
        drawRect(
            color = gridColor.copy(alpha = 0.15f),
            size = size,
        )
        if (points.size < 2) return@Canvas
        val tMin = points.first().tSec
        val tMax = points.last().tSec
        val dt = (tMax - tMin).coerceAtLeast(1e-9)
        val (yMin, yMax) = yRange
        val dy = (yMax - yMin).coerceAtLeast(1e-9)
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path()
        points.forEachIndexed { i, p ->
            val x = (((p.tSec - tMin) / dt) * w).toFloat()
            val ny = ((p.value - yMin) / dy).coerceIn(0.0, 1.0)
            val y = (h - ny * h).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path = path,
            color = color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
        )
        // Faint axis bounds — top and bottom of the Y range.
        drawLine(
            color = gridColor,
            start = Offset(0f, 0f),
            end = Offset(w, 0f),
            strokeWidth = 1f,
        )
        drawLine(
            color = gridColor,
            start = Offset(0f, h - 1f),
            end = Offset(w, h - 1f),
            strokeWidth = 1f,
        )
    }
}
