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
        // Refresh calibration values on every new sample — the background
        // auto-calibration (FileSyncCore) updates prefs while we render.
        var magOffset by remember { mutableStateOf(AgentConfig.magOffset(ctx)) }
        var headingBias by remember { mutableStateOf(AgentConfig.headingBiasDeg(ctx)) }
        var nosePlusY by remember { mutableStateOf(AgentConfig.nosePlusY(ctx)) }
        var lateralSign by remember { mutableStateOf(AgentConfig.lateralSign(ctx)) }
        LaunchedEffect(sample) {
            magOffset = AgentConfig.magOffset(ctx)
            headingBias = AgentConfig.headingBiasDeg(ctx)
        }

        ReadoutGrid(sample, magOffset, headingBias)

        Spacer(Modifier.height(16.dp))
        OrientationSection(
            s = sample,
            magOffset = magOffset,
            headingBias = headingBias,
            nosePlusY = nosePlusY,
            lateralSign = lateralSign,
            onSetDirection = { bias ->
                AgentConfig.setHeadingBias(ctx, bias)
                headingBias = bias
            },
            onNoseConfirm = { v ->
                AgentConfig.setNosePlusY(ctx, v)
                nosePlusY = v
            },
            onLateralConfirm = { v ->
                AgentConfig.setLateralSign(ctx, v)
                lateralSign = v
            },
            onReset = {
                AgentConfig.resetMagCalibration(ctx)
                magOffset = null
                headingBias = 0f
                nosePlusY = null
                lateralSign = null
            },
        )

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
private fun ReadoutGrid(s: LiveSample, magOffset: FloatArray?, headingBias: Float) {
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
                "Yaw %5.1f".format(normDeg(s.headingDeg(magOffset) - headingBias)),
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

/** Wrap a degree value into [0, 360). */
private fun normDeg(d: Double): Double {
    var v = d % 360.0
    if (v < 0) v += 360.0
    return v
}

/**
 * "Box orientation" section — field-validated iOS design, ported 1:1:
 * ONE "USB-C points SOUTH — set direction" tap (box flat), plus two
 * one-time pose confirms (USB-C up / right side) that pin the discrete
 * render signs, plus Reset. The hard-iron offset is learned silently in
 * the background (FileSyncCore.autoCalibrate) with bias compensation.
 */
@Composable
private fun OrientationSection(
    s: LiveSample,
    magOffset: FloatArray?,
    headingBias: Float,
    nosePlusY: Boolean?,
    lateralSign: Float?,
    onSetDirection: (Float) -> Unit,
    onNoseConfirm: (Boolean) -> Unit,
    onLateralConfirm: (Float) -> Unit,
    onReset: () -> Unit,
) {
    // Render heading is frozen while the box is tilted: vertical poses
    // have no defined compass heading (the horizontal field projection
    // collapses) and the preview used to spin on noise.
    var lastFlatHeading by remember { mutableStateOf(0.0) }
    LaunchedEffect(s) {
        if (kotlin.math.abs(s.accMg[2].toInt()) >= 600) {
            lastFlatHeading = normDeg(s.headingDeg(magOffset) - headingBias)
        }
    }

    Text("Box orientation", fontWeight = FontWeight.SemiBold)
    if (magOffset != null) {
        Text(
            "offset [%+.0f %+.0f %+.0f] mG".format(magOffset[0], magOffset[1], magOffset[2]),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(4.dp))

    val flat = kotlin.math.abs(s.accMg[2].toInt()) >= 900
    Text(
        "Front of the box = USB-C connector end. Lay the box flat, USB-C " +
            "end pointing SOUTH, then tap:",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = { onSetDirection(normDeg(s.headingDeg(magOffset) - 180.0).toFloat()) },
        enabled = flat,
    ) { Text("USB-C points SOUTH — set direction") }

    val upright = kotlin.math.abs(s.accMg[1].toInt()) >= 900
    Text(
        "Once, for tilt: stand the box upright, USB-C end UP, and tap:",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = { onNoseConfirm(s.accMg[1].toInt() > 0) },
        enabled = upright,
    ) { Text("USB-C end is UP — confirm") }

    val onSide = kotlin.math.abs(s.accMg[0].toInt()) >= 900
    Text(
        "Once, for side-tips: tip the box 90° onto its RIGHT side and tap:",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(
        onClick = { onLateralConfirm(if (s.accMg[0].toInt() > 0) -1f else 1f) },
        enabled = onSide,
    ) { Text("Box is on its RIGHT side — confirm") }

    OutlinedButton(onClick = onReset) { Text("Reset calibration") }

    Spacer(Modifier.height(4.dp))
    Text(
        "The magnetic offset is learned silently while the box moves. " +
            "Preview is a fixed map, top = SOUTH. Keep away from laptops, " +
            "speakers and steel surfaces; Reset wipes everything.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    OrientationBoxCanvas(
        pitchDeg = s.pitchDeg(),
        rollDeg = s.rollDeg(),
        headingDeg = lastFlatHeading,
        nosePlusY = nosePlusY ?: false,
        lateralSign = lateralSign,
    )
}

/**
 * Wireframe cuboid rotated by the live eCompass angles — final
 * field-validated iOS design, ported 1:1. Fixed map view with screen-up
 * = SOUTH (matches the calibration protocol), all four compass labels
 * (N red), semi-transparent filled lid, nose arrow on the -y end, and
 * the discrete render signs from the pose-confirm taps.
 */
@Composable
private fun OrientationBoxCanvas(
    pitchDeg: Double,
    rollDeg: Double,
    headingDeg: Double,
    nosePlusY: Boolean,
    lateralSign: Float?,
) {
    val textMeasurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outline
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = MaterialTheme.typography.bodyMedium.copy(color = labelColor)
    val northStyle = MaterialTheme.typography.bodyMedium.copy(
        color = Color(0xFFD32F2F), fontWeight = FontWeight.Bold,
    )
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

        // Front-end frame change (iOS parity): USB-C on body +Y renders in
        // the 180-degree-rotated frame — roll negates; the lateral sign
        // comes from the right-side confirm tap when present.
        val pitchSign = lateralSign ?: (if (nosePlusY) -1f else 1f)
        val pDeg = pitchSign * pitchDeg
        val rDeg = if (nosePlusY) -rollDeg else rollDeg

        // Baseline drawing sign flip (z-up sensor vs z-down NED math).
        val sr = -sin(Math.toRadians(rDeg)).toFloat()
        val cr = cos(Math.toRadians(rDeg)).toFloat()
        val sp = -sin(Math.toRadians(pDeg)).toFloat()
        val cp = cos(Math.toRadians(pDeg)).toFloat()
        // +90: the eCompass yaw references the SHORT body axis; the shift
        // makes the nose end's drawn azimuth equal the displayed heading.
        val sy = sin(Math.toRadians(headingDeg + 90.0)).toFloat()
        val cyw = cos(Math.toRadians(headingDeg + 90.0)).toFloat()

        // body -> world (NED): Rz(yaw) * Ry(pitch) * Rx(roll)
        fun rot(v: FloatArray): FloatArray {
            val x = v[0]; val y = v[1]; val z = v[2]
            val y1 = y * cr - z * sr; val z1 = y * sr + z * cr   // Rx
            val x2 = x * cp + z1 * sp; val z2 = -x * sp + z1 * cp // Ry
            return floatArrayOf(x2 * cyw - y1 * sy, x2 * sy + y1 * cyw, z2) // Rz
        }
        // Fixed scene, screen-up = SOUTH: world rotated 180 degrees before
        // the orthographic 28-degree-elevation projection.
        val el = Math.toRadians(28.0)
        val sel = sin(el).toFloat(); val cel = cos(el).toFloat()
        fun project(w: FloatArray): Offset {
            val n = -w[0]; val e = -w[1]   // Rz(-180) view rotation
            return Offset(cx + e * scale, cy - (n * sel - w[2] * cel) * scale)
        }
        fun p3(v: FloatArray): Offset = project(rot(v))

        // Ground-plane compass cross, all four labels (N red).
        drawLine(axisColor, project(floatArrayOf(1.35f, 0f, 0f)), project(floatArrayOf(-1.35f, 0f, 0f)), 2f)
        drawLine(axisColor, project(floatArrayOf(0f, 1.35f, 0f)), project(floatArrayOf(0f, -1.35f, 0f)), 2f)
        val n = project(floatArrayOf(1.5f, 0f, 0f))
        val e = project(floatArrayOf(0f, 1.5f, 0f))
        val sPt = project(floatArrayOf(-1.5f, 0f, 0f))
        val w = project(floatArrayOf(0f, -1.5f, 0f))
        drawText(textMeasurer, "N", topLeft = Offset(n.x - 6f, n.y - 10f), style = northStyle)
        drawText(textMeasurer, "E", topLeft = Offset(e.x - 6f, e.y - 10f), style = labelStyle)
        drawText(textMeasurer, "S", topLeft = Offset(sPt.x - 6f, sPt.y - 10f), style = labelStyle)
        drawText(textMeasurer, "W", topLeft = Offset(w.x - 6f, w.y - 10f), style = labelStyle)

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
        // Semi-transparent lid fill — a bare wireframe is a Necker cube.
        val lidPath = androidx.compose.ui.graphics.Path().apply {
            moveTo(pts[0].x, pts[0].y)
            lineTo(pts[1].x, pts[1].y)
            lineTo(pts[2].x, pts[2].y)
            lineTo(pts[3].x, pts[3].y)
            close()
        }
        drawPath(lidPath, lidColor.copy(alpha = 0.25f))
        for ((a, b) in listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0)) {
            drawLine(lidColor, pts[a], pts[b], 4f)
        }
        // Nose arrow: always the -y end — in the (possibly rotated) render
        // frame that IS the user-confirmed front end.
        val lidC = p3(floatArrayOf(0f, 0f, -hz))
        val nose = p3(floatArrayOf(0f, -hy * 1.25f, -hz))
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
