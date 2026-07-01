package ch.ywesee.movementlogger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
                }
            }
        }
    }
}
