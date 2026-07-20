package ch.ywesee.movementlogger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.ywesee.movementlogger.ble.BleGpsSurvey
import ch.ywesee.movementlogger.ble.FileSyncCore

/**
 * GPS Debug — live u-blox UBX diagnostics tunnelled over the box's BLE link
 * (no cable). Matches the desktop app's "GPS Debug" tab: fix quality, per-
 * signal C/N0, and RF/antenna health, for antenna selection + mounting. Polls
 * the receiver once a second; it never reconfigures it persistently.
 */
@Composable
fun BleGpsDebugScreen() {
    val context = LocalContext.current
    // Make sure the BLE core (which owns the survey wiring) is up.
    remember { FileSyncCore.ensureInit(context); true }

    val survey by BleGpsSurvey.state.collectAsStateWithLifecycle()
    val sync by FileSyncCore.state.collectAsStateWithLifecycle()
    val connected = sync.connection == Connection.Connected

    var label by rememberSaveable { mutableStateOf(survey.label) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("GPS Debug", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Live u-blox UBX diagnostics over BLE (no cable) — fix quality, per-signal " +
                "C/N0, and RF/antenna health for antenna selection + mounting.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (connected) "● Box connected" else "○ Not connected — connect on the Sync tab first",
            style = MaterialTheme.typography.bodySmall,
            color = if (connected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Live RF health straight from the SensorStream (firmware v0.0.55+;
        // no survey needed): C/N0 max plus Peter's assembly metrics — fix
        // type, sats used, top-6 GPS+Galileo C/N0 and the MON-RF EMI set.
        // Moved here from the Live tab: all GPS debugging lives on this tab.
        if (connected) LiveRfCard(sync.live.latestSample)

        OutlinedTextField(
            value = label,
            onValueChange = { label = it; FileSyncCore.setGpsLabel(it) },
            label = { Text("Label") },
            singleLine = true,
            enabled = !survey.running,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (survey.running) {
                Button(onClick = { FileSyncCore.stopGpsDebug() }) { Text("Stop") }
                Spacer(Modifier.width(12.dp))
                CircularProgressIndicator(modifier = Modifier.width(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(
                    "polling… ${survey.epochCount} epoch${if (survey.epochCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Button(onClick = { FileSyncCore.startGpsDebug(label) }, enabled = connected) {
                    Text("Start")
                }
            }
        }

        Text(
            "BLE: connect the box on the Sync tab, then Start — the survey tunnels the " +
                "u-blox over the box's link. Needs box firmware v0.0.17+ (the GPS-bridge " +
                "opcode); on older firmware it just shows “no NAV-PVT reply”.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        survey.log.lastOrNull()?.let { latest ->
            if (survey.running) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Latest epoch", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(latest, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Survey log", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (survey.log.isEmpty()) {
                    Text("No output yet. Start a survey to poll the receiver.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    survey.log.takeLast(40).forEach { line ->
                        Text(
                            line,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (line.startsWith("(no NAV-PVT")) Color(0xFFB26A00)
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }

        survey.epochCsvPath?.let { path ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("CSV output (also in Download/MovementLogger)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(path.substringAfterLast('/'),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall)
                    survey.signalsCsvPath?.let {
                        Text(it.substringAfterLast('/'),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall)
                    }
                    survey.spectrumCsvPath?.let {
                        Text(it.substringAfterLast('/'),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

/**
 * Live RF health from the 0.5 Hz SensorStream (moved from the Live tab):
 * "GPS C/N0" (strongest single satellite, GSV/NAV-SAT), "GPS RF" (fix type,
 * sats used, top-6 GPS+Galileo C/N0 avg/min/max) and "GPS EMI" (MON-RF
 * noise/agc, jamming state, antenna supervisor). Colors follow the desktop:
 * jam ok green / warn yellow / CRIT red; ant SHORT/OPEN red. `rf == null`
 * (legacy 46-byte packets, firmware < v0.0.55) hides the two RF rows.
 */
@Composable
private fun LiveRfCard(s: ch.ywesee.movementlogger.ble.LiveSample?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Live RF (SensorStream)", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (s == null) {
                Text("Waiting for first SensorStream notify…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                RfLabelCell("GPS C/N0")
                RfText(if (s.gpsCn0Max > 0) "${s.gpsCn0Max} dB-Hz max" else "—", 1f)
                RfText(
                    when {
                        s.gpsCn0Max == 0  -> "no GSV / no data"
                        s.gpsCn0Max >= 40 -> "good antenna"
                        s.gpsCn0Max >= 30 -> "ok"
                        else              -> "weak signal"
                    },
                    2f,
                )
            }
            s.rf?.let { rf -> GpsRfRows(rf) }
        }
    }
}

/**
 * The two GPS RF-extension rows (firmware v0.0.55+): "GPS RF" and "GPS EMI" —
 * Peter's assembly metrics over the normal BLE link, same values as the
 * survey's live line, no bridge needed.
 */
@Composable
private fun GpsRfRows(rf: ch.ywesee.movementlogger.ble.GpsRfLive) {
    val green = Color(0xFF2E7D32)
    val yellow = Color(0xFFB58B00)
    val red = Color(0xFFD32F2F)
    val gray = Color(0xFF888888)

    val fixName = when (rf.fixType) {
        0 -> "no fix"
        2 -> "2D"
        3 -> "3D"
        4 -> "3D+DR"
        5 -> "time"
        else -> "?"
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RfLabelCell("GPS RF")
        RfText("fix $fixName · ${rf.usedSv} used", 1f)
        if (rf.avg6X10 > 0) {
            RfText("avg6 %.1f / min %d / max %d dB-Hz".format(rf.avg6X10 / 10.0, rf.min6, rf.max6), 2f)
        } else {
            RfText("no C/N0 data", 2f, yellow)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        RfLabelCell("GPS EMI")
        if (rf.fresh) {
            RfText("noise ${rf.noisePerMs} · agc ${rf.agcCnt}", 1f)
            val (jamText, jamColor) = when (rf.jamState) {
                1 -> "jam ok" to green
                2 -> "jam warn" to yellow
                3 -> "jam CRIT" to red
                else -> "jam ?" to gray
            }
            RfText("$jamText (ind ${rf.jamInd})", 1f, jamColor)
            val (antText, antColor) = when (rf.antStatus) {
                2 -> "ant ok" to green
                3 -> "ant SHORT" to red
                4 -> "ant OPEN" to red
                else -> "ant ?" to gray
            }
            RfText(antText, 1f, antColor)
        } else {
            RfText("no MON-RF reply (module quiet)", 3f, gray)
        }
    }
}

@Composable
private fun RowScope.RfText(text: String, weight: Float, color: Color = Color.Unspecified) {
    Text(
        text,
        color = color,
        modifier = Modifier.weight(weight),
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
}

@Composable
private fun RfLabelCell(text: String) {
    Text(text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(end = 8.dp))
}
