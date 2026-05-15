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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.ywesee.movementlogger.ble.LiveSample
import kotlinx.coroutines.delay

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
        topBar = { TopAppBar(title = { Text("Live") }) },
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

        ReadoutGrid(sample)

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
private fun ReadoutGrid(s: LiveSample) {
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
                "Yaw %5.1f".format(s.headingDeg()),
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
