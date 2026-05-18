package ch.ywesee.movementlogger.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import ch.ywesee.movementlogger.BuildConfig
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSyncScreen(vm: FileSyncViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val requiredPerms = remember {
        // Hard requirements: BLE scan/connect. The app can't function without them.
        val ble = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        // Soft requirement: POST_NOTIFICATIONS gates the foreground-service
        // notification BleSyncService shows while syncing in the background.
        // Denial doesn't block BLE — the user just won't see the persistent
        // banner — so we ask for it but don't gate the UI on it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ble + Manifest.permission.POST_NOTIFICATIONS
        } else ble
    }
    val blePerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            setOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    var permsGranted by remember {
        mutableStateOf(blePerms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Only the BLE perms gate the UI; notification perm is best-effort.
        permsGranted = blePerms.all { grants[it] == true || // user granted now
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* user came back from settings — let next Scan retry */ }

    LaunchedEffect(Unit) {
        val missing = requiredPerms.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permLauncher.launch(missing.toTypedArray())
    }

    Scaffold(
        // The outer MainNav Scaffold already insets for the status bar;
        // zero insets here so we don't double-count and leave a blank
        // band above the title.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Text(
                        "Movement Logger ${BuildConfig.VERSION_NAME}" +
                            if (BuildConfig.DEBUG) " (debug)" else ""
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { padding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(padding),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                state.sessionRunning?.let {
                    SessionBanner(it, onCleared = vm::clearSession)
                    Spacer(Modifier.height(12.dp))
                }

                ConnectionBar(
                    state = state,
                    permsGranted = permsGranted,
                    onRequestPerms = { permLauncher.launch(requiredPerms) },
                    onEnableBt = {
                        enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                    },
                    onScan = vm::scan,
                    onDisconnect = vm::disconnect,
                    onList = vm::listFiles,
                    onSyncNow = vm::syncNow,
                    onSetKeepSynced = vm::setKeepSynced,
                    onStopLog = vm::stopLog,
                    onSetDuration = vm::setSessionDuration,
                    onStartSession = vm::startSession,
                    onSetLogMode = vm::setLogMode,
                )

                if (state.transferInterrupted && state.connection != Connection.Connected) {
                    Spacer(Modifier.height(8.dp))
                    TransferInterruptedBanner()
                }

                Spacer(Modifier.height(12.dp))

                when (state.connection) {
                    Connection.Disconnected -> DiscoveredList(state, vm::connect)
                    Connection.Connecting -> CenteredSpinner(label = "connecting…")
                    Connection.Connected -> {
                        state.deleteError?.let { err ->
                            DeleteErrorBanner(err) { vm.dismissDeleteError() }
                            Spacer(Modifier.height(8.dp))
                        }
                        FilesPanel(state, vm::download, vm::delete)
                    }
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                LogSection(vm.logFilePath())
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionBar(
    state: FileSyncUiState,
    permsGranted: Boolean,
    onRequestPerms: () -> Unit,
    onEnableBt: () -> Unit,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    onList: () -> Unit,
    onSyncNow: () -> Unit,
    onSetKeepSynced: (Boolean) -> Unit,
    onStopLog: () -> Unit,
    onSetDuration: (Int) -> Unit,
    onStartSession: () -> Unit,
    onSetLogMode: (Boolean) -> Unit,
) {
    Column {
        // FlowRow so the action buttons wrap onto the next line on narrow
        // screens instead of the last one (Disconnect) being squeezed into
        // an unusable tall sliver.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state.connection) {
                Connection.Disconnected -> {
                    if (!permsGranted) {
                        Button(onClick = onRequestPerms) { Text("Grant BLE permissions") }
                    } else {
                        Button(onClick = onScan, enabled = !state.scanning) {
                            Text(if (state.scanning) "Scanning…" else "Scan for PumpTsueri")
                        }
                        OutlinedButton(onClick = onEnableBt) { Text("Bluetooth…") }
                    }
                }
                Connection.Connecting -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("connecting…")
                    }
                }
                Connection.Connected -> {
                    Button(onClick = onList, enabled = !state.listing) {
                        Text(if (state.listing) "Listing…" else "List files")
                    }
                    OutlinedButton(onClick = onSyncNow, enabled = !state.listing && !state.syncing) {
                        Text(if (state.syncing) "Syncing…" else "Sync now")
                    }
                    // Disconnect is back so one person can sync, drop the
                    // link, and hand the box to the next person to sync.
                    // STOP_LOG stays removed: with the always-on firmware
                    // it would silently kill recording until a power-cycle.
                    OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
                }
            }
        }
        if (state.connection == Connection.Connected) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = state.keepSynced,
                    onCheckedChange = onSetKeepSynced,
                )
                Spacer(Modifier.width(8.dp))
                Text("Keep synced", style = MaterialTheme.typography.bodySmall)
            }
            state.syncStatus?.let { status ->
                Spacer(Modifier.height(8.dp))
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.syncing) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            LogModeSelector(state.logModeManual, onSetLogMode)
            if (state.logModeManual == true) {
                Spacer(Modifier.height(8.dp))
                SessionStarter(
                    durationSeconds = state.sessionDurationSeconds,
                    onDurationChange = onSetDuration,
                    onStart = onStartSession,
                )
            }
        }
    }
}

/**
 * Auto / Manual box log-mode. AUTO = the box opens a session on every
 * cold boot (data-safe default). MANUAL = it boots idle and only
 * records after Start session, for the chosen duration — the box can
 * then be powered yet not recording, so it's opt-in. `manual == null`
 * means not yet known (legacy firmware that ignores GET_MODE, or the
 * reply hasn't arrived); neither chip is selected until the box answers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogModeSelector(manual: Boolean?, onSet: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Log mode", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = manual == false,
            onClick = { onSet(false) },
            label = { Text("Auto") },
        )
        Spacer(Modifier.width(6.dp))
        FilterChip(
            selected = manual == true,
            onClick = { onSet(true) },
            label = { Text("Manual") },
        )
    }
    Text(
        when (manual) {
            false -> "Box records automatically on power-on."
            true -> "Box stays idle on power-on — start a session below."
            null -> "Querying box… (legacy firmware can't report this)"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SessionStarter(
    durationSeconds: Int,
    onDurationChange: (Int) -> Unit,
    onStart: () -> Unit,
) {
    // Local text state so partial edits ("18", "180", "1800") aren't clamped
    // back as the user types. Reseeds when the model value changes.
    var text by remember(durationSeconds) { androidx.compose.runtime.mutableStateOf(durationSeconds.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { raw ->
                text = raw.filter { it.isDigit() }.take(5)
                text.toIntOrNull()?.let(onDurationChange)
            },
            label = { Text("Duration") },
            suffix = { Text("s · ${humanDuration(durationSeconds)}") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(200.dp),
        )
        Spacer(Modifier.width(8.dp))
        Button(
            onClick = onStart,
            enabled = durationSeconds in 1..86_400,
        ) { Text("Start session") }
    }
}

@Composable
private fun SessionBanner(running: SessionRunning, onCleared: () -> Unit) {
    var nowMs by androidx.compose.runtime.remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        while (true) {
            nowMs = System.currentTimeMillis()
            if (running.remainingMillis(nowMs) <= 0L) break
            delay(1000)
        }
        onCleared()
    }
    val remainingMs = running.remainingMillis(nowMs)
    val remainingSecs = (remainingMs / 1000).toInt()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                "LOG session running",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                "${formatRemaining(remainingSecs)} remaining of ${humanDuration(running.durationSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Box is in LOG mode and invisible to Scan. Short-press the button on the box to abort early.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }
}

private fun humanDuration(secs: Int): String {
    val h = secs / 3600
    val m = (secs / 60) % 60
    val s = secs % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        m > 0 && s > 0 -> "${m}m ${s}s"
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}

private fun formatRemaining(secs: Int): String {
    val h = secs / 3600
    val m = (secs / 60) % 60
    val s = secs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun DiscoveredList(state: FileSyncUiState, onConnect: (String) -> Unit) {
    if (state.discovered.isEmpty()) {
        Text(
            if (state.scanning) "Scanning for PumpTsueri…"
            else "Tap Scan to look for the box.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn {
        items(state.discovered, key = { it.address }) { d ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(d.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${d.address}  ·  ${d.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = { onConnect(d.address) }) { Text("Connect") }
                }
            }
        }
    }
}

@Composable
private fun FilesPanel(
    state: FileSyncUiState,
    onDownload: (RemoteFile) -> Unit,
    onDelete: (RemoteFile) -> Unit,
) {
    if (state.files.isEmpty() && !state.listing) {
        Text(
            "Connected. Tap List files to see SD-card contents.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (state.listing && state.files.isEmpty()) {
        CenteredSpinner(label = "listing files…"); return
    }
    // Match the desktop GUI: sensor-data files (per-session recordings) on top,
    // everything else under a "Debug" section the user usually ignores.
    // Newest first: the per-session counter in the name (SensNNN.csv) is the
    // recency proxy — higher NNN = later session. No-digit names sort last.
    val sensor = state.files.filter { isSensorData(it.name) }
        .sortedByDescending { recencyKey(it.name) }
    val debug = state.files.filterNot { isSensorData(it.name) }
        .sortedByDescending { recencyKey(it.name) }
    LazyColumn {
        if (sensor.isNotEmpty()) {
            item(key = "header-sensor") { GroupHeader("Sensor", sensor.size) }
            items(sensor, key = { "s-${it.name}" }) { f -> FileRow(f, state, onDownload, onDelete) }
        }
        if (debug.isNotEmpty()) {
            item(key = "header-debug") { GroupHeader("Debug", debug.size) }
            items(debug, key = { "d-${it.name}" }) { f -> FileRow(f, state, onDownload, onDelete) }
        }
    }
}

@Composable
private fun GroupHeader(title: String, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "($count)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FileRow(
    f: RemoteFile,
    state: FileSyncUiState,
    onDownload: (RemoteFile) -> Unit,
    onDelete: (RemoteFile) -> Unit,
) {
    val progress = state.downloads[f.name]
    val savedPath = state.savedPaths[f.name]
    val deleteReason = deleteUnsupported(f.name)
    // Fully downloaded = local mirror has at least the box's reported
    // size. Then the Download button is replaced by a "✓ Downloaded"
    // marker instead of inviting a redundant re-download.
    val downloaded = f.size > 0L && (state.localBytes[f.name] ?: 0L) >= f.size
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(f.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        humanBytes(f.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (savedPath != null) {
                        Text(
                            "saved → $savedPath",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (downloaded && progress == null) {
                    Text(
                        "✓ Downloaded",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    OutlinedButton(
                        onClick = { onDownload(f) },
                        enabled = progress == null,
                    ) { Text(if (progress == null) "Download" else "…") }
                }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    onClick = { onDelete(f) },
                    enabled = progress == null && deleteReason == null,
                ) { Text("Delete") }
            }
            if (deleteReason != null) {
                Text(
                    "Can't delete: $deleteReason",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (progress != null) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "${humanBytes(progress.bytesDone)} / ${humanBytes(progress.total)} " +
                        "(${(progress.fraction * 100).toInt()}%)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Recency rank for the file list: the trailing per-session counter in
 * `SensNNN.csv` / `GpsNNN.csv` / … — higher = later session = shown
 * first. Names without a number sort last (key -1).
 */
private fun recencyKey(name: String): Int =
    Regex("(\\d+)").findAll(name).lastOrNull()?.value?.toIntOrNull() ?: -1

/**
 * Per-session sensor-data files the firmware writes: Sens*.csv, Gps*.csv,
 * Bat*.csv, Mic*.wav. Everything else (notably DebugX.csv) lands in the
 * Debug group. Matches `is_sensor_data_name` in stbox-viz-gui/src/main.rs.
 *
 * macOS AppleDouble sidecars (`._<name>`) match the inner pattern by accident
 * — guard the prefix explicitly.
 */
private fun isSensorData(name: String): Boolean {
    val n = name.lowercase()
    if (n.startsWith("._")) return false
    return (n.startsWith("sens") && n.endsWith(".csv")) ||
        (n.startsWith("gps")  && n.endsWith(".csv")) ||
        (n.startsWith("bat")  && n.endsWith(".csv")) ||
        (n.startsWith("mic")  && n.endsWith(".wav"))
}

/**
 * Rows the box firmware can *never* delete — return the reason so the
 * trash button can be disabled with an explanation instead of looking
 * like a silent no-op. Port of the desktop's `delete_unsupported`
 * (movement_logger_desktop v0.0.10 / issue #7): `ble.c` caps DELETE
 * names at 15 bytes (longer ⇒ BAD_REQUEST) and `SDFat_Delete` only
 * matches a real FAT 8.3 short name, so `._*` AppleDouble sidecars and
 * the virtual `PUMPTSUE.RI` placeholder always come back NOT_FOUND.
 */
private fun deleteUnsupported(name: String): String? = when {
    name.startsWith("._") ->
        "macOS metadata sidecar — not a real file on the box's SD card"
    name.equals("PUMPTSUE.RI", ignoreCase = true) ->
        "virtual placeholder entry — nothing to delete"
    name.toByteArray().size > 15 ->
        "filename too long for the box's delete command (15-char firmware cap)"
    else -> null
}

/**
 * Prominent dismissable banner for a DELETE the box rejected (BUSY /
 * NOT_FOUND / IO_ERROR / BAD_REQUEST). Port of the desktop's
 * `ble_delete_err` frame (v0.0.10) — without it a rejected delete only
 * shows in the log and looks like the tap did nothing.
 */
/**
 * Amber banner shown while disconnected after a transfer was cut by a
 * link drop / stall. Port of the desktop v0.0.9 resume banner — the
 * partial is already safe in the mirror, so reconnecting resumes
 * automatically and skips every file already complete.
 */
@Composable
private fun TransferInterruptedBanner() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF2CC)),
        border = BorderStroke(1.dp, Color(0xFFD9A832)),
    ) {
        Text(
            "⚠ Transfer interrupted (BLE link lost). Scan and reconnect to " +
                "the same box — the sync resumes automatically and skips " +
                "files already saved.",
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF8C5A00),
        )
    }
}

@Composable
private fun DeleteErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE5E5)),
        border = BorderStroke(1.dp, Color(0xFFC75050)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "⚠ $message",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFAA1E1E),
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/**
 * Replaces the always-on 180 dp panel with a single "Log" button. The
 * full transcript is written to `<externalFilesDir>/movement_logger.log`
 * regardless; tapping the button opens that file in a viewer dialog
 * (with Share to export it). Mirrors the iOS LogSection / LogFileViewer.
 */
@Composable
private fun LogSection(filePath: String?) {
    var showing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { showing = true }) { Text("Log") }
        Spacer(Modifier.width(8.dp))
        Text(
            "→ movement_logger.log",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (showing) {
        LogFileViewer(filePath = filePath, onDismiss = { showing = false })
    }
}

/**
 * Full-screen viewer: reads `movement_logger.log` from disk, shows it
 * scrollable + monospaced (scrolled to the end), with a Share action
 * that hands the file to any app via the FileProvider.
 */
@Composable
private fun LogFileViewer(filePath: String?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember(filePath) {
        if (filePath == null) ""
        else runCatching { java.io.File(filePath).readText() }.getOrDefault("")
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "movement_logger.log",
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { shareLogFile(context, filePath) },
                        enabled = filePath != null && text.isNotEmpty(),
                    ) { Text("Share") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                HorizontalDivider()
                val scroll = rememberScrollState()
                LaunchedEffect(text, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(12.dp),
                ) {
                    Text(
                        text.ifEmpty {
                            "(log file is empty — connect to a box to generate entries)"
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

private fun shareLogFile(context: android.content.Context, path: String?) {
    if (path == null) return
    val file = java.io.File(path)
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
        Intent.createChooser(send, "Share log")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
private fun CenteredSpinner(label: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

private fun humanBytes(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> "%.1f KB".format(b / 1024.0)
    else -> "%.2f MB".format(b / (1024.0 * 1024.0))
}
