package ch.ywesee.movementlogger.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Sync : Dest("sync", "Sync", Icons.Filled.CloudDownload)
    data object Replay : Dest("replay", "Replay", Icons.Filled.PlayCircle)
}

private val destinations = listOf(Dest.Sync, Dest.Replay)

@Composable
fun MainNav() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { d ->
                    NavigationBarItem(
                        selected = backStack?.destination?.hierarchy?.any { it.route == d.route } == true,
                        onClick = {
                            if (currentRoute != d.route) {
                                nav.navigate(d.route) {
                                    popUpTo(nav.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = { Icon(d.icon, contentDescription = d.label) },
                        label = { Text(d.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Dest.Sync.route,
            modifier = Modifier.padding(padding),
        ) {
            composable(Dest.Sync.route) { FileSyncScreen() }
            composable(Dest.Replay.route) { ReplayScreen() }

        }
    }
}
