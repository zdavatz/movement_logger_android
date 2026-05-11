package ch.ywesee.movementlogger.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileSyncScreen(vm: FileSyncViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val requiredPerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }
    var permsGranted by remember {
        mutableStateOf(requiredPerms.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> permsGranted = grants.values.all { it } }

    val enableBtLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* user came back from settings — let next Scan retry */ }

    LaunchedEffect(Unit) {
        if (!permsGranted) permLauncher.launch(requiredPerms)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Movement Logger") },
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
                    onStopLog = vm::stopLog,
                )

                Spacer(Modifier.height(12.dp))

                when (state.connection) {
                    Connection.Disconnected -> DiscoveredList(state, vm::connect)
                    Connection.Connecting -> CenteredSpinner(label = "connecting…")
                    Connection.Connected -> FilesPanel(state, vm::download, vm::delete)
                }

                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                LogPanel(state.log)
            }
        }
    }
}

@Composable
private fun ConnectionBar(
    state: FileSyncUiState,
    permsGranted: Boolean,
    onRequestPerms: () -> Unit,
    onEnableBt: () -> Unit,
    onScan: () -> Unit,
    onDisconnect: () -> Unit,
    onList: () -> Unit,
    onStopLog: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (state.connection) {
            Connection.Disconnected -> {
                if (!permsGranted) {
                    Button(onClick = onRequestPerms) { Text("Grant BLE permissions") }
                } else {
                    Button(onClick = onScan, enabled = !state.scanning) {
                        Text(if (state.scanning) "Scanning…" else "Scan for PumpTsueri")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onEnableBt) { Text("Bluetooth…") }
                }
            }
            Connection.Connecting -> {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("connecting…")
            }
            Connection.Connected -> {
                Button(onClick = onList, enabled = !state.listing) {
                    Text(if (state.listing) "Listing…" else "List files")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onStopLog) { Text("STOP_LOG") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            }
        }
    }
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
    val sensor = state.files.filter { isSensorData(it.name) }
    val debug = state.files.filterNot { isSensorData(it.name) }
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
                OutlinedButton(
                    onClick = { onDownload(f) },
                    enabled = progress == null,
                ) { Text(if (progress == null) "Download" else "…") }
                Spacer(Modifier.width(4.dp))
                OutlinedButton(onClick = { onDelete(f) }) { Text("Delete") }
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

@Composable
private fun LogPanel(lines: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(
                MaterialTheme.colorScheme.surfaceContainerLow,
                RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
    ) {
        if (lines.isEmpty()) {
            Text("log", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(state = listState) {
                items(lines) { line ->
                    Text(
                        line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
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
