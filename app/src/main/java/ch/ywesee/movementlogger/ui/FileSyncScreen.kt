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

    // Firmware `.bin` picker (SAF). Most file managers report a .bin as
    // application/octet-stream; some return application/* or nothing, so we
    // also allow */* and filter by extension is unnecessary — the box
    // verifies the SHA-256 anyway. Picking → uploadFirmware reads the bytes.
    val fwPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) vm.uploadFirmware(uri) }

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
                    onSetGpsPower = vm::setGpsPower,
                    onPickFirmware = {
                        // application/octet-stream is the canonical .bin MIME;
                        // */* is the catch-all for managers that report none.
                        fwPickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    onCancelFirmware = vm::abortFirmwareUpload,
                )

                state.fwUploadResult?.let { res ->
                    Spacer(Modifier.height(8.dp))
                    FwUploadResultBanner(res) { vm.dismissFwUploadResult() }
                }

                // "New box firmware" banner — shown when connected and the box
                // is behind the latest GitHub release (or legacy/unknown).
                // "Update box" downloads the .bin and runs the FOTA flow.
                state.firmwareUpdateAvailable?.let { (version, _) ->
                    if (state.connection == Connection.Connected) {
                        Spacer(Modifier.height(8.dp))
                        FirmwareUpdateBanner(
                            version = version,
                            busy = state.fwUpload != null,
                            onUpdate = vm::applyFirmwareUpdate,
                            onDismiss = vm::dismissFirmwareUpdate,
                        )
                    }
                }

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
    onSetGpsPower: (Boolean) -> Unit,
    onPickFirmware: () -> Unit,
    onCancelFirmware: () -> Unit,
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
                    // Disable while ANY worker op is in flight — a big
                    // keep-synced READ holds the BLE worker busy for
                    // minutes, and tap-while-busy used to collide with
                    // the "another op is in flight" rejection and leave
                    // the UI confused (port of iOS Sync UX fix). A
                    // firmware upload likewise occupies the single op.
                    val uploading = state.fwUpload != null
                    val workerBusy = state.listing || state.syncing ||
                            state.downloads.isNotEmpty() || uploading
                    Button(onClick = onList, enabled = !workerBusy) {
                        Text(if (state.listing) "Listing…" else "List files")
                    }
                    OutlinedButton(onClick = onSyncNow, enabled = !workerBusy) {
                        Text(if (state.syncing) "Syncing…" else "Sync now")
                    }
                    // Stage a new firmware image onto the box's inactive
                    // flash bank, then verify + swap + reset (OTA). Single
                    // op, so disabled while anything else is in flight.
                    OutlinedButton(onClick = onPickFirmware, enabled = !workerBusy) {
                        Text(if (uploading) "Uploading…" else "Firmware update")
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
            // Cumulative byte-progress card so the user can tell that
            // keep-synced IS pulling files even while the file list above
            // is empty (deliberately blanked during sync). Per-file row
            // underneath shows the in-flight READ.
            if (state.syncing) {
                Spacer(Modifier.height(8.dp))
                SyncProgressRow(state)
            }
            // Firmware-upload progress card — mirrors the sync card. Shown
            // whenever a `.bin` is streaming to the box's flash bank.
            state.fwUpload?.let { up ->
                Spacer(Modifier.height(8.dp))
                FwUploadProgressRow(up, onCancelFirmware)
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
            Spacer(Modifier.height(8.dp))
            val gpsBusy = state.listing || state.syncing ||
                state.downloads.isNotEmpty() || state.fwUpload != null
            GpsPowerSelector(state.gpsPowerOn, gpsBusy, onSetGpsPower)
        }
    }
}

/**
 * In-flight sync-pass progress card.
 *
 * Two layers of progress, headline first:
 *   - Overall byte-progress bar (`bytesDone / bytesTotal`) — the
 *     denominator includes every file's full size, the numerator is
 *     completed files + the in-flight file's `bytesDone`. So the bar
 *     tracks data actually pulled, not file-count.
 *   - Current file row underneath: name + per-file byte progress bar.
 *     Disappears between files (brief idle gap between two queued READs).
 */
@Composable
private fun SyncProgressRow(state: FileSyncUiState) {
    val total = state.syncPassTotal
    val inFlight = state.syncInFlight
    // Same formula as the iOS card: total - queueRemaining - (inFlight?1:0)
    // is the number of fully-completed files; +1 while one is mid-READ
    // gives the running "current of N" position. Simplifies to
    // `total - queueRemaining`.
    val completed = (total - state.syncQueueRemaining).coerceIn(0, total)
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (total > 0) "Syncing — $completed of $total files"
                        else "Syncing…",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Text(
                        "${humanBytes(state.syncCumulativeBytes)} / " +
                            humanBytes(state.syncPassTotalBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${(state.syncCumulativeFraction * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { state.syncCumulativeFraction },
                modifier = Modifier.fillMaxWidth(),
            )
            val perFile = inFlight?.let { state.downloads[it] }
            if (inFlight != null && perFile != null) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        inFlight,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${humanBytes(perFile.bytesDone)} / ${humanBytes(perFile.total)}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { perFile.fraction },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * In-flight firmware-upload progress card. Mirrors [SyncProgressRow]: a
 * headline with filename + byte progress + percent, a bar, and a Cancel
 * button (best-effort FW_ABORT). The byte counter advances as FW_DATA ACKs
 * land over BLE (~2 KB/s+).
 */
@Composable
private fun FwUploadProgressRow(state: FwUploadState, onCancel: () -> Unit) {
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Firmware update — ${state.name}",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${humanBytes(state.bytesDone)} / ${humanBytes(state.total)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "${(state.fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { state.fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Result banner for the most recent firmware upload. Green on success
 * ("box rebooting into new firmware — reconnect…"), red on a mapped
 * failure. Dismissable.
 */
@Composable
private fun FwUploadResultBanner(result: FwUploadResult, onDismiss: () -> Unit) {
    val (bg, border, fg) = if (result.success)
        Triple(Color(0xFFE6F4EA), Color(0xFF4CAF50), Color(0xFF1B5E20))
    else
        Triple(Color(0xFFFFE5E5), Color(0xFFC75050), Color(0xFFAA1E1E))
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bg),
        border = BorderStroke(1.dp, border),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (if (result.success) "✓ " else "⚠ ") + result.message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = fg,
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
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

/**
 * GPS on/off for the box (firmware v0.0.35+). OFF puts the u-blox receiver
 * into backup mode (~tens of µA vs ~25 mA) to save battery when GPS is
 * faulty/unused — logging keeps running (IMU + baro) and Replay still
 * time-aligns via the phone-clock `# SYNC` anchor. Persisted on the box.
 * `on == null` means not yet known (querying, or legacy firmware that ignores
 * 0x12); neither chip is selected until the box answers.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GpsPowerSelector(on: Boolean?, busy: Boolean, onSet: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("GPS", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(8.dp))
        FilterChip(
            selected = on == true,
            enabled = !busy,
            onClick = { onSet(true) },
            label = { Text("On") },
        )
        Spacer(Modifier.width(6.dp))
        FilterChip(
            selected = on == false,
            enabled = !busy,
            onClick = { onSet(false) },
            label = { Text("Off") },
        )
    }
    Text(
        when (on) {
            true -> "GPS receiver on. Turn off to save battery if GPS is faulty — IMU + baro keep logging."
            false -> "GPS off (backup mode) — saving battery. Replay still time-aligns; speed + track need GPS on."
            null -> "Querying box… (firmware older than v0.0.35 can't switch GPS)."
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
    val queued = f.name in state.queuedDownloads
    val savedPath = state.savedPaths[f.name]
    val deleteReason = deleteUnsupported(f.name)
    // Fully downloaded = local mirror has at least the box's reported
    // size. Then the Download button is replaced by a "View" button
    // that opens the file in an inline preview (iOS parity, a86fd6d).
    val downloaded = f.size > 0L && (state.localBytes[f.name] ?: 0L) >= f.size
    var viewing by remember { mutableStateOf(false) }
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
                    // Opens an inline preview sheet with a Share button —
                    // same pattern as the global Log viewer, now per-file
                    // (iOS parity, a86fd6d).
                    OutlinedButton(onClick = { viewing = true }) { Text("View") }
                } else {
                    OutlinedButton(
                        onClick = { onDownload(f) },
                        enabled = progress == null && !queued,
                    ) {
                        Text(
                            when {
                                progress != null -> "…"
                                queued -> "Queued"
                                else -> "Download"
                            }
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(
                    onClick = { onDelete(f) },
                    enabled = progress == null && !queued && deleteReason == null,
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
    if (viewing) {
        DownloadedFileViewer(
            name = f.name,
            path = savedPath,
            onDismiss = { viewing = false },
        )
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

/**
 * "New box firmware available" banner. Shown when connected and the box's
 * firmware is behind the latest GitHub release (or the box is legacy / didn't
 * report a version). "Update box" downloads the `.bin` and runs the existing
 * FOTA upload; ✕ dismisses. Info-blue to read as an offer, not an error.
 */
@Composable
private fun FirmwareUpdateBanner(
    version: String,
    busy: Boolean,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F0FB)),
        border = BorderStroke(1.dp, Color(0xFF3B82C4)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "🔄 New box firmware v$version available",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF14486E),
            )
            Button(onClick = onUpdate, enabled = !busy) {
                Text(if (busy) "Updating…" else "Update box")
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onDismiss) { Text("✕") }
        }
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

/**
 * Per-file viewer for a downloaded recording. Reads up to
 * [PREVIEW_CAP_BYTES] of the file on `Dispatchers.IO`, decodes as UTF-8
 * if there are no embedded NULs (so CSV/log files preview inline) and
 * falls back to a size summary for binary files (WAV/MOV). A Share
 * button always exports the *full* file via the FileProvider — the
 * 48 KB cap is only for the inline `Text` (Compose lays out the whole
 * string at once and stutters past ~50 KB of monospaced content, so a
 * 2 MB CSV would freeze the dialog for seconds). iOS parity (a86fd6d).
 */
@Composable
private fun DownloadedFileViewer(
    name: String,
    path: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var loading by remember(path) { mutableStateOf(true) }
    var fileSize by remember(path) { mutableStateOf(0L) }
    var isText by remember(path) { mutableStateOf(false) }
    var truncated by remember(path) { mutableStateOf(false) }
    var preview by remember(path) { mutableStateOf("") }

    LaunchedEffect(path) {
        if (path == null) { loading = false; return@LaunchedEffect }
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val f = java.io.File(path)
                val size = f.length()
                val cap = PREVIEW_CAP_BYTES
                val toRead = minOf(size, cap.toLong()).toInt()
                val buf = ByteArray(toRead)
                f.inputStream().use { ins ->
                    var off = 0
                    while (off < toRead) {
                        val n = ins.read(buf, off, toRead - off)
                        if (n <= 0) break
                        off += n
                    }
                }
                val hasNul = buf.any { it == 0.toByte() }
                val text = if (hasNul) null else runCatching {
                    buf.toString(Charsets.UTF_8)
                }.getOrNull()
                Quad(size, text != null, size > cap, text ?: "")
            }.getOrElse { Quad(0L, false, false, "") }
        }
        fileSize = result.a
        isText = result.b
        truncated = result.c
        preview = result.d
        loading = false
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
                        name,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { shareDownloadedFile(context, name, path) },
                        enabled = path != null,
                    ) { Text("Share") }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                HorizontalDivider()
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        loading -> Row(
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                        path == null -> Text(
                            "(file not on disk)",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                        !isText -> Text(
                            "(binary file — ${humanBytes(fileSize)}. " +
                                "Use Share to export the full file.)",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp),
                        )
                        else -> Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp),
                        ) {
                            if (truncated) {
                                Text(
                                    "Showing first ${humanBytes(PREVIEW_CAP_BYTES.toLong())} " +
                                        "of ${humanBytes(fileSize)} — use Share for the " +
                                        "full file.",
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

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

/** SwiftUI/Compose `Text` lays out the whole string at once and gets
 *  noticeably laggy past ~50 KB of monospaced content; a 2 MB CSV
 *  would stutter for seconds. The Share button handles "I want the
 *  whole thing" cleanly. */
private const val PREVIEW_CAP_BYTES: Int = 48 * 1024

private fun shareDownloadedFile(
    context: android.content.Context,
    name: String,
    path: String?,
) {
    if (path == null) return
    val file = java.io.File(path)
    if (!file.exists()) return
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, "${context.packageName}.fileprovider", file,
    )
    val mime = when (name.substringAfterLast('.', "").lowercase()) {
        "csv", "log", "txt" -> "text/plain"
        "wav" -> "audio/wav"
        "mp4", "mov", "m4v" -> "video/mp4"
        else -> "application/octet-stream"
    }
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(send, "Share $name")
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
