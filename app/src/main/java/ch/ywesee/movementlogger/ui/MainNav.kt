package ch.ywesee.movementlogger.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Live : Dest("live", "Live", Icons.Filled.Sensors)
    data object Gps : Dest("gps", "GPS", Icons.Filled.LocationOn)
    data object Sync : Dest("sync", "Sync", Icons.Filled.CloudDownload)
    data object Replay : Dest("replay", "Replay", Icons.Filled.PlayCircle)
    data object Merge : Dest("merge", "Merge", Icons.Filled.VideoLibrary)
    data object Race : Dest("race", "Race", Icons.Filled.Map)
    data object Analyze : Dest("analyze", "Analyze", Icons.Filled.ShowChart)
    data object GpsDebug : Dest("gpsdebug", "GPS Debug", Icons.Filled.Settings)
}

// Cap the bottom bar at four icons + a "More" overflow (mirrors iOS's tab bar):
// the four realtime/primary tabs stay one tap away; the rest live in the sheet.
private val primary = listOf(Dest.Live, Dest.Gps, Dest.Sync, Dest.Replay)
private val overflow = listOf(Dest.Merge, Dest.Race, Dest.Analyze, Dest.GpsDebug)

private fun NavController.go(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.startDestinationId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    var showMore by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState()

    Scaffold(
        bottomBar = {
            NavigationBar {
                primary.forEach { d ->
                    NavigationBarItem(
                        selected = backStack?.destination?.hierarchy?.any { it.route == d.route } == true,
                        onClick = { nav.go(d.route) },
                        icon = { Icon(d.icon, contentDescription = d.label) },
                        label = { Text(d.label) },
                    )
                }
                NavigationBarItem(
                    selected = overflow.any { it.route == currentRoute },
                    onClick = { showMore = true },
                    icon = { Icon(Icons.Filled.MoreHoriz, contentDescription = "More") },
                    label = { Text("More") },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Dest.Live.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Dest.Live.route) {
                LiveScreen(onGoToSync = { nav.go(Dest.Sync.route) })
            }
            composable(Dest.Gps.route) { UsbGpsScreen() }
            composable(Dest.Sync.route) { FileSyncScreen() }
            composable(Dest.Replay.route) { ReplayScreen() }
            composable(Dest.Merge.route) { MergeScreen() }
            composable(Dest.Race.route) { RaceMapScreen() }
            composable(Dest.Analyze.route) { ImuAnalysisScreen() }
            composable(Dest.GpsDebug.route) { BleGpsDebugScreen() }
        }
    }

    if (showMore) {
        ModalBottomSheet(onDismissRequest = { showMore = false }, sheetState = sheetState) {
            Column(Modifier.navigationBarsPadding()) {
                Text(
                    "More",
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 8.dp),
                )
                overflow.forEach { d ->
                    val selected = currentRoute == d.route
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { nav.go(d.route); showMore = false }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(d.icon, contentDescription = d.label)
                        Spacer(Modifier.width(16.dp))
                        Text(d.label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}
