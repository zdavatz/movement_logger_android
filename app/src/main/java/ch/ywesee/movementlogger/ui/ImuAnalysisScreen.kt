package ch.ywesee.movementlogger.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.ywesee.movementlogger.data.ImuAnalysis
import ch.ywesee.movementlogger.data.ImuAnalysisResult
import ch.ywesee.movementlogger.data.ImuMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val GLIDE_C = Color(0xFF2563EB)
private val PADDLE_C = Color(0xFFEA7317)
private val WAIT_C = Color(0xFF94A3B8)
private val SPEED_C = Color(0xFF2E7D32)
private val PITCH_C = Color(0xFF2563EB)
private val ROLL_C = Color(0xFFEA7317)
private val ENERGY_C = Color(0xFF0D9488)

private fun modeColor(m: ImuMode) = when (m) {
    ImuMode.GLIDE -> GLIDE_C; ImuMode.PADDLE -> PADDLE_C; ImuMode.WAIT -> WAIT_C
}

private data class ImuRec(val sens: File, val gps: File?)
private data class BandRun(val start: Float, val end: Float, val mode: ImuMode)

private fun scanRecordings(dir: File?): List<ImuRec> {
    if (dir == null) return emptyList()
    val files = dir.listFiles { f -> f.name.endsWith(".csv", true) } ?: return emptyList()
    return files.filter { it.name.startsWith("SensPhone_") }
        .map { s -> ImuRec(s, files.firstOrNull { it.name == s.name.replace("SensPhone", "GpsPhone") }) }
        .sortedByDescending { it.sens.name }
}

private fun sizeLabel(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1000 -> "%.0f KB".format(bytes / 1000.0)
    else -> "$bytes B"
}

/**
 * "Analyze" tab — on-device IMU propulsion analysis of a recorded board-mounted
 * phone-logger ride. Lists `SensPhone_*` recordings, pairs each with its
 * `GpsPhone_*`, and renders the [ImuAnalysis] mode strip + cadence + board
 * angles natively. Android twin of the iOS `ImuAnalysisScreen`.
 */
@Composable
fun ImuAnalysisScreen() {
    val ctx = LocalContext.current
    var recordings by remember { mutableStateOf(scanRecordings(ctx.getExternalFilesDir(null))) }
    var selected by remember { mutableStateOf<ImuRec?>(null) }

    val sel = selected
    if (sel == null) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Analyze", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                Text(
                    "Refresh",
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { recordings = scanRecordings(ctx.getExternalFilesDir(null)) },
                )
            }
            Spacer(Modifier.height(8.dp))
            if (recordings.isEmpty()) {
                Text(
                    "No phone-logger recordings. Record with the Phone logger (GPS tab), then come back.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    items(recordings) { rec ->
                        Card(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { selected = rec },
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(rec.sens.name, fontWeight = FontWeight.Medium)
                                Text(
                                    "Phone logger · ${sizeLabel(rec.sens.length())}" +
                                        if (rec.gps == null) " · no GPS pair" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    } else {
        AnalysisDetail(sel) { selected = null }
    }
}

@Composable
private fun AnalysisDetail(rec: ImuRec, onBack: () -> Unit) {
    var result by remember { mutableStateOf<ImuAnalysisResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(rec.sens.path) {
        loading = true; error = null; result = null
        try {
            result = withContext(Dispatchers.Default) {
                val gps = rec.gps ?: throw ImuAnalysis.AnalysisException("No paired GpsPhone file for ${rec.sens.name}.")
                ImuAnalysis.fromPhoneLogger(rec.sens, gps)
            }
        } catch (e: Exception) {
            error = e.message ?: "Analysis failed."
        }
        loading = false
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("IMU analysis", style = MaterialTheme.typography.titleMedium)
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text("Analyzing ${rec.sens.name}…", style = MaterialTheme.typography.bodySmall)
                }
            }
            error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(error!!, color = MaterialTheme.colorScheme.error)
            }
            result != null -> ResultView(result!!)
        }
    }
}

@Composable
private fun ResultView(r: ImuAnalysisResult) {
    val bands = remember(r) { computeBands(r) }
    var zoom by remember { mutableFloatStateOf(1f) }
    val hScroll = rememberScrollState()
    val transformState = rememberTransformableState { zoomChange, _, _ -> zoom = (zoom * zoomChange).coerceIn(1f, 40f) }
    val screenW = LocalConfiguration.current.screenWidthDp
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        // header
        Text(r.sourceName, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Text(
            "%.0f min · %.0f Hz · %d foil runs".format(r.durationMin, r.fs, r.foilRuns),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row {
            statTile("Glide", r.glideSec / 60, GLIDE_C)
            Spacer(Modifier.width(16.dp)); statTile("Paddle", r.paddleSec / 60, PADDLE_C)
            Spacer(Modifier.width(16.dp)); statTile("Wait", r.waitSec / 60, WAIT_C)
            if (r.medianStrokeRate.isFinite()) {
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Stroke", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%.0f/min".format(r.medianStrokeRate), fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Pinch to zoom · %.0f× · drag to pan".format(zoom),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (zoom > 1.01f) {
                Text("Reset", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { zoom = 1f })
            }
        }
        Spacer(Modifier.height(4.dp))

        // Panels live in a (width × zoom) horizontal scroll so a long ride can be
        // spread out and panned; pinch (transformable) sets the zoom.
        val panelW = ((screenW - 32) * zoom).dp
        Box(Modifier.horizontalScroll(hScroll).transformable(transformState)) {
            Column(Modifier.width(panelW)) {
                if (r.speedKmh.any { it > 0.5 }) {
                    LinePanel("GPS speed", "km/h", r.minutes, r.speedKmh, SPEED_C, hLine = ImuAnalysis.FOIL_KMH,
                        hColor = Color(0xFF0284C7), bands = bands, fill = true)
                }
                LinePanel("Board angle (mount-relative)", "deg", r.minutes, r.pitchDeg, PITCH_C,
                    bands = bands, symmetric = true, second = r.rollDeg, secondColor = ROLL_C)
                LinePanel("Stroke energy (0.35–1.3 Hz)", "RMS", r.minutes, r.strokeEnergy, ENERGY_C,
                    hLine = r.seThreshold, hColor = Color(0xFFB45309), bands = bands, fill = true)
                ModeStrip(r)
            }
        }
        legend(listOf("Pitch (up/down)" to PITCH_C, "Roll (lean L/R)" to ROLL_C))
        legend(listOf("Glide (on foil)" to GLIDE_C, "Paddle / pump" to PADDLE_C, "Wait / drift" to WAIT_C))
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun statTile(label: String, minutes: Int, c: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
            Spacer(Modifier.width(4.dp))
            Text("${minutes}m", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun legend(items: List<Pair<String, Color>>) {
    Row(Modifier.padding(top = 2.dp, bottom = 6.dp)) {
        items.forEach { (label, c) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 14.dp)) {
                Box(Modifier.size(width = 14.dp, height = 8.dp).clip(RoundedCornerShape(2.dp)).background(c))
                Spacer(Modifier.width(4.dp))
                Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun computeBands(r: ImuAnalysisResult): List<BandRun> {
    val out = ArrayList<BandRun>()
    var i = 0
    while (i < r.mode.size) {
        var j = i
        while (j + 1 < r.mode.size && r.mode[j + 1] == r.mode[i]) j++
        if (r.mode[i] != ImuMode.WAIT) {
            out.add(BandRun(r.minutes[i].toFloat(), r.minutes[minOf(j + 1, r.minutes.size - 1)].toFloat(), r.mode[i]))
        }
        i = j + 1
    }
    return out
}

@Composable
private fun LinePanel(
    title: String, unit: String, minutes: DoubleArray, ys: DoubleArray, color: Color,
    hLine: Double? = null, hColor: Color = Color.Gray, bands: List<BandRun> = emptyList(),
    symmetric: Boolean = false, fill: Boolean = false,
    second: DoubleArray? = null, secondColor: Color = Color.Gray,
) {
    val surface = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(unit, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(8.dp)).background(surface)) {
            if (minutes.size < 2) return@Canvas
            val xmin = minutes.first(); val xmax = minutes.last(); val xr = (xmax - xmin).coerceAtLeast(1e-6)
            val all = if (second != null) ys.filter { it.isFinite() } + second.filter { it.isFinite() } else ys.filter { it.isFinite() }
            if (all.isEmpty()) return@Canvas
            var ymin = all.min(); var ymax = all.max()
            if (symmetric) { val m = maxOf(kotlin.math.abs(ymin), kotlin.math.abs(ymax), 1.0); ymin = -m; ymax = m }
            if (hLine != null) { ymin = minOf(ymin, hLine); ymax = maxOf(ymax, hLine) }
            if (fill) ymin = minOf(ymin, 0.0)
            val yr = (ymax - ymin).coerceAtLeast(1e-6)
            fun px(x: Double) = ((x - xmin) / xr * size.width).toFloat()
            fun py(y: Double) = (size.height - (y - ymin) / yr * size.height).toFloat()

            for (b in bands) {
                drawRect(modeColor(b.mode).copy(alpha = 0.12f),
                    topLeft = Offset(px(b.start.toDouble()), 0f),
                    size = Size((px(b.end.toDouble()) - px(b.start.toDouble())).coerceAtLeast(1f), size.height))
            }
            if (symmetric) drawLine(Color.Gray.copy(alpha = 0.4f), Offset(0f, py(0.0)), Offset(size.width, py(0.0)), 1f)
            if (hLine != null) drawLine(hColor.copy(alpha = 0.8f), Offset(0f, py(hLine)), Offset(size.width, py(hLine)), 1.2f)

            drawSeries(minutes, ys, color, fill, ymin, ymax, ::px, ::py)
            if (second != null) drawSeries(minutes, second, secondColor, false, ymin, ymax, ::px, ::py)
        }
    }
}

private fun DrawScope.drawSeries(
    minutes: DoubleArray, ys: DoubleArray, color: Color, fill: Boolean,
    ymin: Double, ymax: Double, px: (Double) -> Float, py: (Double) -> Float,
) {
    val baseY = py(0.0.coerceIn(ymin, ymax))
    val line = Path(); val filled = Path(); var started = false
    for (i in minutes.indices) {
        if (!ys[i].isFinite()) continue
        val x = px(minutes[i]); val y = py(ys[i])
        if (!started) { line.moveTo(x, y); filled.moveTo(x, baseY); filled.lineTo(x, y); started = true }
        else { line.lineTo(x, y); filled.lineTo(x, y) }
    }
    if (started && fill) {
        filled.lineTo(px(minutes.last()), baseY)
        drawPath(filled, color.copy(alpha = 0.16f))
    }
    drawPath(line, color, style = Stroke(width = 2.2f))
}

@Composable
private fun ModeStrip(r: ImuAnalysisResult) {
    Column(Modifier.padding(top = 4.dp)) {
        Text("Mode", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Canvas(Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(6.dp))) {
            if (r.minutes.size < 2) return@Canvas
            val xmin = r.minutes.first(); val xmax = r.minutes.last(); val xr = (xmax - xmin).coerceAtLeast(1e-6)
            fun px(x: Double) = ((x - xmin) / xr * size.width).toFloat()
            var i = 0
            while (i < r.mode.size) {
                var j = i
                while (j + 1 < r.mode.size && r.mode[j + 1] == r.mode[i]) j++
                val x0 = px(r.minutes[i]); val x1 = px(r.minutes[minOf(j + 1, r.minutes.size - 1)])
                drawRect(modeColor(r.mode[i]), topLeft = Offset(x0, 0f), size = Size((x1 - x0).coerceAtLeast(1f), size.height))
                i = j + 1
            }
        }
    }
}
