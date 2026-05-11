package ch.ywesee.movementlogger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Replay screen: load a saved sensor CSV + (optional) GPS CSV + a video,
 * play them back time-synced with overlaid panels (speed, pitch, height,
 * GPS track). For now this is a placeholder while the parser + math
 * layers land in subsequent commits.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReplayScreen() {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Replay") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Coming soon",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "Pick a video and a Sens*.csv / Gps*.csv pair to play them back time-synced. " +
                    "Speed, pitch and height overlays will land in the next commits.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
