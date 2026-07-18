package ch.ywesee.movementlogger.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.ywesee.movementlogger.data.formatLocalTime
import java.io.File

/**
 * Merge screen — pick multiple videos, they get sorted chronologically by
 * capture time, then merged into one film: per clip a 2.5 s black title
 * card (recording date + start time), the complete clip (never trimmed —
 * hard product rule), and a 3 s fade-to-black. Optionally load Sens + Gps
 * CSVs to composite the same sensor panels as the Replay export under
 * every clip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeScreen(vm: MergeViewModel = viewModel()) {
    val state by vm.state.collectAsState()

    val videosLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> -> if (uris.isNotEmpty()) vm.pickVideos(uris) }

    Scaffold(
        // Outer MainNav Scaffold already insets for the status bar.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text("Merge") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                "Pick several videos — they are ordered by recording time. " +
                    "Each clip plays complete (never trimmed) with a title card " +
                    "before it and a 3 s fade-out at its end.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    videosLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                }) { Text(if (state.clips.isEmpty()) "Pick videos" else "Replace videos") }

                if (state.clips.isNotEmpty()) {
                    Spacer(Modifier.width(12.dp))
                    OutlinedButton(
                        onClick = { vm.mergeVideos() },
                        enabled = state.exportState !is ExportState.Running,
                    ) { Text("Merge videos") }
                }
            }

            MergeExportBanner(state = state.exportState, onDismiss = vm::clearExportState)

            if (state.loading) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("loading…")
                }
            }

            state.error?.let { msg ->
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            msg,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        OutlinedButton(onClick = vm::clearError) { Text("Dismiss") }
                    }
                }
            }

            if (state.clips.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("Clips (chronological)", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                state.clips.forEachIndexed { i, clip ->
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Text(
                                "${i + 1} · ${clip.displayName}",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val whenStr = clip.creationTimeMillis?.let { formatLocalTime(it) }
                                ?: "no creation time"
                            Text(
                                "$whenStr · ${formatClipDuration(clip.durationMillis)} · ${clip.width}×${clip.height}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            SensorDataPicker(vm = vm, state = state)

            if (state.computing) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("running fusion + baro + nose-angle…")
                }
            }
        }
    }
}

/** Optional Sens/Gps CSV pickers, same local-storage source as Replay. */
@Composable
private fun SensorDataPicker(vm: MergeViewModel, state: MergeUiState) {
    val recordings = remember(state.sensorFile, state.gpsFile) { vm.listLocalRecordings() }
    val sensorCandidates = recordings.filter { isSensCsv(it.name) }
    val gpsCandidates = recordings.filter { isGpsCsv(it.name) }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Sensor data (optional)", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        if (state.sensorFile != null || state.gpsFile != null) {
            OutlinedButton(onClick = vm::clearSensorData) { Text("Clear") }
        }
    }
    Text(
        if (state.sensorsReady) {
            "Sensor panels will be composited under every clip."
        } else {
            "Load Sens + Gps CSVs to composite the sensor panels; " +
                "otherwise the plain videos are merged."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (recordings.isEmpty()) {
        Spacer(Modifier.height(4.dp))
        Text(
            "No CSVs in this app's storage yet. Use the Sync tab to download some first.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        return
    }
    Spacer(Modifier.height(8.dp))
    Text("Sensor CSV", fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    MergeFileChooserList(
        files = sensorCandidates,
        selected = state.sensorFile,
        empty = "no Sens*.csv downloaded",
        onPick = vm::pickSensorCsv,
    )
    Spacer(Modifier.height(8.dp))
    Text("GPS CSV", fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    MergeFileChooserList(
        files = gpsCandidates,
        selected = state.gpsFile,
        empty = "no Gps*.csv downloaded",
        onPick = vm::pickGpsCsv,
    )
}

@Composable
private fun MergeFileChooserList(
    files: List<File>,
    selected: File?,
    empty: String,
    onPick: (File) -> Unit,
) {
    if (files.isEmpty()) {
        Text(empty, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().height(96.dp)) {
        items(files, key = { it.absolutePath }) { f ->
            val isSelected = f == selected
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(f.name, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                        Text(
                            mergeHumanBytesShort(f.length()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = { onPick(f) }) {
                        Text(if (isSelected) "Reload" else "Load")
                    }
                }
            }
        }
    }
}

@Composable
private fun MergeExportBanner(state: ExportState, onDismiss: () -> Unit) {
    when (state) {
        is ExportState.Idle -> {}
        is ExportState.Running -> {
            Spacer(Modifier.height(12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "Merging videos… %d%%".format((state.progress * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        is ExportState.Done -> {
            val context = LocalContext.current
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Saved to Photos · Movies/MovementLogger",
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { openMergedVideo(context, state.uri) }) { Text("Open video") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = onDismiss) { Text("OK") }
                }
            }
        }
        is ExportState.Failed -> {
            Spacer(Modifier.height(12.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Merge failed: ${state.message}", modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }
    }
}

private fun openMergedVideo(context: android.content.Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "video/mp4")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, "Open video"))
}

private fun formatClipDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

private fun isSensCsv(name: String): Boolean {
    val n = name.lowercase()
    if (n.startsWith("._")) return false
    return n.startsWith("sens") && n.endsWith(".csv")
}

private fun isGpsCsv(name: String): Boolean {
    val n = name.lowercase()
    if (n.startsWith("._")) return false
    return n.startsWith("gps") && n.endsWith(".csv")
}

private fun mergeHumanBytesShort(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024L * 1024 -> "%.1f KB".format(b / 1024.0)
    else -> "%.2f MB".format(b / (1024.0 * 1024.0))
}
