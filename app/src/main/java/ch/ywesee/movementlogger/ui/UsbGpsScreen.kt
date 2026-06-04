package ch.ywesee.movementlogger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.ywesee.movementlogger.usb.UbloxGpsCore
import java.util.Locale

/**
 * GPS tab — driver UI for the u-blox GNSS receiver plugged into the
 * phone's USB-C port. Three states:
 *
 *  - **No device** — prompt to plug in.
 *  - **Device found, not connected** — Connect button (triggers USB
 *    permission dialog if needed).
 *  - **Reading** — live fix readout + Hz counter + Record CSV toggle.
 *
 * Mirrors the BLE Live tab visual style (top status strip + readout
 * card) so users don't have to context-switch when comparing the
 * box's GPS to the external u-blox.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsbGpsScreen() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        UbloxGpsCore.init(ctx)
        UbloxGpsCore.refreshDevice()
    }
    val state by UbloxGpsCore.state.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("GPS") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                "u-blox GNSS receiver over USB — independent fix to cross-check the box GPS.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))
            StatusStrip(state)
            Spacer(Modifier.height(8.dp))
            ControlsRow(state)
            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            if (!state.isReading) {
                Text(
                    "Waiting — tap Connect once the receiver is plugged in.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            RateCard(state)
            Spacer(Modifier.height(12.dp))
            FixCard(state)
            Spacer(Modifier.height(12.dp))
            LogCard(state)
        }
    }
}

@Composable
private fun StatusStrip(state: ch.ywesee.movementlogger.usb.UbloxUiState) {
    val tint = when {
        state.isReading && state.fixQuality > 0 -> Color(0xFF2E7D32)
        state.isReading -> Color(0xFFB58B00)
        state.deviceFound -> Color(0xFF1565C0)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(state.status, color = tint, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ControlsRow(state: ch.ywesee.movementlogger.usb.UbloxUiState) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.isReading) {
            Button(
                onClick = { UbloxGpsCore.connect() },
                enabled = state.deviceFound,
            ) { Text("Connect") }
        } else {
            Button(onClick = { UbloxGpsCore.disconnect() }) { Text("Disconnect") }
        }
        OutlinedButton(onClick = { UbloxGpsCore.refreshDevice() }) { Text("Refresh") }
    }
}

@Composable
private fun RateCard(state: ch.ywesee.movementlogger.usb.UbloxUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Measurement rate", fontWeight = FontWeight.SemiBold)
            Row {
                Text("RMC ", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(
                    "%.2f Hz".format(state.rmcHz),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.padding(end = 16.dp).height(0.dp))
                Text("GGA ", fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                Text(
                    "%.2f Hz".format(state.ggaHz),
                    fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "${state.sentenceCount} sentences received total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FixCard(state: ch.ywesee.movementlogger.usb.UbloxUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ReadoutRow("UTC", state.utc.orEmpty(), "", "")
            ReadoutRow(
                "Lat / Lon",
                state.latDeg?.let { "%.6f°".format(Locale.US, it) }.orEmpty(),
                state.lonDeg?.let { "%.6f°".format(Locale.US, it) }.orEmpty(),
                "",
            )
            ReadoutRow(
                "Altitude",
                state.altM?.let { "%.1f m".format(Locale.US, it) }.orEmpty(),
                "",
                "",
            )
            ReadoutRow(
                "Speed / Course",
                state.speedKmh?.let { "%.2f km/h".format(Locale.US, it) }.orEmpty(),
                state.courseDeg?.let { "%.1f°".format(Locale.US, it) }.orEmpty(),
                "",
            )
            ReadoutRow(
                "Fix",
                fixQualityLabel(state.fixQuality),
                "${state.numSat} sats",
                state.hdop?.let { "HDOP %.2f".format(Locale.US, it) }.orEmpty(),
            )
        }
    }
}

@Composable
private fun LogCard(state: ch.ywesee.movementlogger.usb.UbloxUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("CSV log", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!state.isLogging) {
                    Button(onClick = { UbloxGpsCore.startLogging() }) { Text("Start recording") }
                } else {
                    Button(onClick = { UbloxGpsCore.stopLogging() }) { Text("Stop recording") }
                }
            }
            if (state.isLogging) {
                Text(
                    "Recording → ${state.logPath ?: "?"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${state.loggedRows} rows written",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (state.logPath != null) {
                Text(
                    "Last log: ${state.logPath} (${state.loggedRows} rows)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Schema matches the box's Gps*.csv — Replay tab will pick it up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun fixQualityLabel(q: Int): String = when (q) {
    0 -> "no fix"
    1 -> "GPS"
    2 -> "DGPS"
    4 -> "RTK fixed"
    5 -> "RTK float"
    6 -> "estimated"
    else -> "q=$q"
}

@Composable
private fun ReadoutRow(label: String, a: String, b: String, c: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(a, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Text(b, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
        Text(c, modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
    }
}
