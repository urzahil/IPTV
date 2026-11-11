package rocks.urzahil.iptv.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import rocks.urzahil.iptv.player.PlayerActivity
import rocks.urzahil.iptv.ui.channels.ChannelsScreen
import rocks.urzahil.iptv.ui.favourites.FavouritesScreen
import rocks.urzahil.iptv.ui.guide.GuideScreen
import rocks.urzahil.iptv.ui.search.SearchScreen
import rocks.urzahil.iptv.ui.settings.SettingsScreen
import rocks.urzahil.iptv.ui.sources.AddM3uScreen
import rocks.urzahil.iptv.ui.sources.AddXtreamScreen
import rocks.urzahil.iptv.ui.sources.SourceEditScreen
import rocks.urzahil.iptv.ui.sources.SourcesScreen
import kotlinx.coroutines.launch

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab(Routes.CHANNELS, "Channels", Icons.Filled.Home),
    Tab(Routes.GUIDE, "Guide", Icons.Filled.DateRange),
    Tab(Routes.FAVOURITES, "Favourites", Icons.Filled.Star),
    Tab(Routes.SEARCH, "Search", Icons.Filled.Search),
    Tab(Routes.SOURCES, "Sources", Icons.Filled.Storage),
)

@Composable
fun AppNavHost() {
    val nav = rememberNavController()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    /** Navigation hook for tapping a channel: records it as recent and opens the player. */
    val onPlayChannel: (Long, Long?) -> Unit = { channelId, categoryId ->
        scope.launch { context.appContainer.channelRepository.markWatched(channelId) }
        context.startActivity(PlayerActivity.intent(context, channelId, categoryId))
    }
    val openSettings = { nav.navigate(Routes.SETTINGS) { launchSingleTop = true } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (TABS.any { it.route == route }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    TABS.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.CHANNELS, modifier = Modifier.padding(padding)) {
            composable(Routes.CHANNELS) {
                ChannelsScreen(
                    onPlayChannel = onPlayChannel,
                    onOpenSettings = { openSettings() },
                    onAddSource = { nav.navigate(Routes.SOURCES) },
                )
            }
            composable(Routes.GUIDE) { GuideScreen(onOpenSettings = { openSettings() }) }
            composable(Routes.FAVOURITES) { FavouritesScreen(onPlayChannel, { openSettings() }) }
            composable(Routes.SEARCH) { SearchScreen(onPlayChannel, { openSettings() }) }
            composable(Routes.SOURCES) {
                SourcesScreen(
                    onAddXtream = { nav.navigate(Routes.ADD_XTREAM) },
                    onAddM3u = { nav.navigate(Routes.ADD_M3U) },
                    onEdit = { nav.navigate(Routes.editSource(it)) },
                    onOpenSettings = { openSettings() },
                )
            }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }) }
            composable(Routes.ADD_XTREAM) { AddXtreamScreen(onDone = { nav.popBackStack() }) }
            composable(Routes.ADD_M3U) { AddM3uScreen(onDone = { nav.popBackStack() }) }
            composable(
                Routes.EDIT_SOURCE,
                arguments = listOf(navArgument(Routes.EDIT_ARG) { type = NavType.LongType }),
            ) { entry ->
                SourceEditScreen(entry.arguments?.getLong(Routes.EDIT_ARG) ?: -1L, onDone = { nav.popBackStack() })
            }
        }
    }
}
