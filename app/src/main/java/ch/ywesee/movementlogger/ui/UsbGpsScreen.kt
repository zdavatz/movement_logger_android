package ch.ywesee.movementlogger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Context
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.ywesee.movementlogger.usb.GpsRecording
import ch.ywesee.movementlogger.usb.RaceUplink
import ch.ywesee.movementlogger.usb.UbloxGpsCore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
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
        UbloxGpsCore.refreshRecordings()
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

            if (state.isReading) {
                RateCard(state)
                Spacer(Modifier.height(12.dp))
                FixCard(state)
                Spacer(Modifier.height(12.dp))
                LogCard(state)
            } else {
                Text(
                    "Waiting — tap Connect once the receiver is plugged in.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            RaceCard()

            Spacer(Modifier.height(12.dp))
            RecordingsCard(state)
        }
    }
}

/**
 * Race mode — stream this phone's dongle fixes to the desktop app's
 * Race tab (UDP, 2 Hz; see `RaceUplink`). The desktop shows the
 * `ip:port` to enter here when it starts listening.
 */
@Composable
private fun RaceCard() {
    val ctx = LocalContext.current
    val race by RaceUplink.state.collectAsStateWithLifecycle()
    var rider by remember(race.rider) { mutableStateOf(race.rider) }
    var host by remember(race.host) { mutableStateOf(race.host) }
    var port by remember(race.port) { mutableStateOf(race.port.toString()) }
    var source by remember(race.source) { mutableStateOf(race.source) }
    var token by remember(race.race) { mutableStateOf(race.race) }

    fun enableNow() {
        RaceUplink.configure(
            rider.trim(),
            host.trim(),
            port.trim().toIntOrNull() ?: RaceUplink.DEFAULT_PORT,
            source,
            token.trim(),
        )
        RaceUplink.setEnabled(true)
    }
    // Phone-GPS source needs the runtime location grant (the u-blox
    // source reads USB serial and never touches Android location).
    val locPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) enableNow() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Race mode", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                Switch(
                    checked = race.enabled,
                    onCheckedChange = { on ->
                        when {
                            !on -> RaceUplink.setEnabled(false)
                            source == RaceUplink.SOURCE_PHONE &&
                                ctx.checkSelfPermission(
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                ) != android.content.pm.PackageManager.PERMISSION_GRANTED ->
                                locPermLauncher.launch(
                                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                                )
                            else -> enableNow()
                        }
                    },
                    // Sending without a target/name is pointless.
                    enabled = race.enabled || (rider.isNotBlank() && host.isNotBlank()),
                )
            }
            Text(
                "Streams live positions to the desktop Race map (same WiFi).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            // GPS source: the plugged u-blox receiver, or the phone's own
            // GNSS when no receiver is available (iOS-parity picker).
            Row {
                FilterChip(
                    selected = source == RaceUplink.SOURCE_UBLOX,
                    onClick = { if (!race.enabled) source = RaceUplink.SOURCE_UBLOX },
                    label = { Text("u-blox USB") },
                    enabled = !race.enabled,
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = source == RaceUplink.SOURCE_PHONE,
                    onClick = { if (!race.enabled) source = RaceUplink.SOURCE_PHONE },
                    label = { Text("Phone GPS") },
                    enabled = !race.enabled,
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = rider,
                onValueChange = { rider = it },
                label = { Text("Rider name") },
                singleLine = true,
                enabled = !race.enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Desktop IP") },
                    singleLine = true,
                    enabled = !race.enabled,
                    modifier = Modifier.weight(2f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() } },
                    label = { Text("Port") },
                    singleLine = true,
                    enabled = !race.enabled,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Race token (optional)") },
                singleLine = true,
                enabled = !race.enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            if (race.enabled) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Sending — ${race.sent} fixes to ${race.host}:${race.port}" +
                        if (race.sent == 0L) {
                            if (race.source == RaceUplink.SOURCE_PHONE) {
                                " (waiting for a phone GPS fix)"
                            } else {
                                " (waiting for a GPS fix — is the receiver connected?)"
                            }
                        } else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                race.lastError?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
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
            if (!state.isLogging) {
                Button(onClick = { UbloxGpsCore.startLogging() }) { Text("Start recording") }
            } else {
                Button(onClick = { UbloxGpsCore.stopLogging() }) { Text("Stop recording") }
            }
            if (state.isLogging) {
                Text(
                    "● Recording — ${state.loggedRows} rows written",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFC62828),
                )
            } else {
                Text(
                    "Each Start → Stop writes a new file below. Schema matches the " +
                        "box's Gps*.csv, so the Replay tab picks it up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The recordings overview: one swipe-to-delete row per saved
 * `UbloxGps_*.csv`, each showing max speed / distance / duration and a
 * Share button that offers **View** or **Share**. Shown regardless of
 * connection state so past recordings stay reachable when the receiver is
 * unplugged. While logging, a periodic refresh keeps the active row's
 * stats moving.
 */
@Composable
private fun RecordingsCard(state: ch.ywesee.movementlogger.usb.UbloxUiState) {
    val recordings by UbloxGpsCore.recordings.collectAsStateWithLifecycle()
    var viewing by remember { mutableStateOf<GpsRecording?>(null) }
    var mapping by remember { mutableStateOf<GpsRecording?>(null) }

    // Re-scan every 3 s while a recording is in progress so the in-flight
    // row's distance/duration tick up; idle otherwise (no wasted IO).
    LaunchedEffect(state.isLogging) {
        while (state.isLogging) {
            delay(3000)
            UbloxGpsCore.refreshRecordings()
        }
    }

    Card(
        Modifier.fillMaxWidth(),
        // Row foregrounds paint this same colour so the red delete layer
        // only shows through while a row is being swiped.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Recordings (${recordings.size})",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { UbloxGpsCore.refreshRecordings() }) { Text("Refresh") }
            }
            if (recordings.isEmpty()) {
                Text(
                    "No recordings yet — tap Start recording to create one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "Swipe a row left to delete.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                recordings.forEach { rec ->
                    key(rec.name) {
                        RecordingRow(rec = rec, onView = { viewing = rec }, onMap = { mapping = rec })
                    }
                }
            }
        }
    }

    viewing?.let { rec ->
        GpsRecordingViewer(name = rec.name, path = rec.path, onDismiss = { viewing = null })
    }
    mapping?.let { rec ->
        RideMapView(name = rec.name, path = rec.path, onDismiss = { mapping = null })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordingRow(rec: GpsRecording, onView: () -> Unit, onMap: () -> Unit) {
    val context = LocalContext.current
    val dismissState = rememberSwipeToDismissBoxState()

    // Fire the delete once the row has been swiped fully to the end-start
    // (right-to-left) position. `key(rec.name)` in the caller resets this
    // state per file so a delete can't leak onto the row that slides up.
    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            UbloxGpsCore.deleteRecording(rec.name)
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        // The active recording can't be deleted (its writer is open).
        enableDismissFromEndToStart = !rec.isRecording,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFC62828))
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
            }
        },
    ) {
        RecordingRowContent(rec = rec, context = context, onView = onView, onMap = onMap)
    }
}

@Composable
private fun RecordingRowContent(
    rec: GpsRecording,
    context: Context,
    onView: () -> Unit,
    onMap: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            // Tapping the row opens the track on the map — iOS Rides parity.
            .clickable(onClick = onMap)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1.3f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (rec.isRecording) {
                    Text("● ", color = Color(0xFFC62828), fontSize = 12.sp)
                }
                Text(
                    recordingTime(rec.name),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                recordingDate(rec.name),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatCell("Max", "%.1f".format(Locale.US, rec.maxSpeedKmh), "km/h")
        StatCell("Dist", formatDistance(rec.distanceMeters), "")
        StatCell("Dur", formatMmSs(rec.durationSec), "")
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.Share, contentDescription = "Share ${rec.name}")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Map") },
                    onClick = { menuOpen = false; onMap() },
                )
                DropdownMenuItem(
                    text = { Text("View") },
                    onClick = { menuOpen = false; onView() },
                )
                DropdownMenuItem(
                    text = { Text("Share") },
                    onClick = { menuOpen = false; shareGpsCsv(context, rec.path) },
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.StatCell(
    label: String,
    value: String,
    unit: String,
) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (unit.isNotEmpty()) {
            Text(
                unit,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

/**
 * Fires the system share sheet for the active/most-recent GPS CSV. Same
 * MIME + FileProvider pattern as [shareDownloadedFile] in
 * [FileSyncScreen] — CSVs are `text/plain`. Safe to call mid-recording:
 * the file already exists on disk and gets flushed per row.
 */
private fun shareGpsCsv(context: Context, path: String) {
    val file = File(path)
    if (!file.exists()) return
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file,
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "Share ${file.name}")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

// --- recording-row formatting helpers ---

private val STAMP_RE = Regex("""UbloxGps_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})\.csv""")

/** `UbloxGps_20260706_142025.csv` → `14:20:25`; falls back to the name. */
private fun recordingTime(name: String): String {
    val m = STAMP_RE.matchEntire(name) ?: return name
    val (_, _, _, hh, mi, ss) = m.destructured
    return "$hh:$mi:$ss"
}

/** `UbloxGps_20260706_142025.csv` → `2026-07-06`; empty if unparseable. */
private fun recordingDate(name: String): String {
    val m = STAMP_RE.matchEntire(name) ?: return ""
    val (y, mo, d) = m.destructured
    return "$y-$mo-$d"
}

/** Metres → `"842 m"` under 1 km, else `"1.24 km"`. */
private fun formatDistance(meters: Double): String =
    if (meters < 1000.0) "%.0f m".format(Locale.US, meters)
    else "%.2f km".format(Locale.US, meters / 1000.0)

/** Seconds → `mm:ss` (minutes may exceed 59 for long recordings). */
private fun formatMmSs(sec: Long): String =
    "%02d:%02d".format(sec / 60, sec % 60)

/**
 * Full-screen preview of one GPS CSV. Reads up to [PREVIEW_CAP_BYTES] on
 * IO, shows it monospaced, and exposes a Share action — the "View"
 * counterpart to the row's Share menu. Mirrors `DownloadedFileViewer` in
 * [FileSyncScreen].
 */
@Composable
private fun GpsRecordingViewer(name: String, path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var loading by remember(path) { mutableStateOf(true) }
    var preview by remember(path) { mutableStateOf("") }
    var truncated by remember(path) { mutableStateOf(false) }

    LaunchedEffect(path) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(path)
                val size = f.length()
                val toRead = minOf(size, PREVIEW_CAP_BYTES.toLong()).toInt()
                val buf = ByteArray(toRead)
                f.inputStream().use { ins ->
                    var off = 0
                    while (off < toRead) {
                        val n = ins.read(buf, off, toRead - off)
                        if (n <= 0) break
                        off += n
                    }
                }
                buf.toString(Charsets.UTF_8) to (size > PREVIEW_CAP_BYTES)
            }.getOrElse { "" to false }
        }
        preview = result.first
        truncated = result.second
        loading = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        name,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { shareGpsCsv(context, path) }) { Text("Share") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                HorizontalDivider()
                Box(modifier = Modifier.fillMaxSize()) {
                    if (loading) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        ) {
                            if (truncated) {
                                Text(
                                    "Showing first ${PREVIEW_CAP_BYTES / 1024} KB — use Share for the full file.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            Text(
                                preview.ifEmpty { "(empty file)" },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val PREVIEW_CAP_BYTES: Int = 48 * 1024
