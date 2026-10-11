package fr.appartfinder.freetube

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import fr.appartfinder.freetube.download.DownloadsScreen
import fr.appartfinder.freetube.download.TrackPipeline
import fr.appartfinder.freetube.library.LibraryScreen
import fr.appartfinder.freetube.library.PlaylistRoute
import fr.appartfinder.freetube.library.PlaylistScreen
import fr.appartfinder.freetube.library.PlaylistsScreen
import fr.appartfinder.freetube.playback.FullPlayer
import fr.appartfinder.freetube.playback.MiniPlayer
import fr.appartfinder.freetube.playback.PlayerConnection
import fr.appartfinder.freetube.settings.SettingsScreen
import fr.appartfinder.freetube.sync.requestSync
import fr.appartfinder.freetube.ui.FreeTubeTheme
import fr.appartfinder.freetube.ui.IconAction
import fr.appartfinder.freetube.sync.schedulePeriodicSync
import fr.appartfinder.freetube.youtube.PlaylistId
import fr.appartfinder.freetube.youtube.VideoId
import fr.appartfinder.freetube.youtube.YoutubeScreen
import fr.appartfinder.freetube.youtube.YoutubeTarget
import fr.appartfinder.freetube.youtube.YoutubeWebView
import fr.appartfinder.freetube.youtube.classifySharedText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import javax.inject.Inject
import kotlin.reflect.KClass

@Serializable data object YoutubeRoute

@Serializable data object LibraryRoute

@Serializable data object PlaylistsRoute

@Serializable data object DownloadsRoute

@Serializable data object SettingsRoute

@Serializable data object PlayerRoute

/** The one list of tabs: the bottom bar and the NavHost both derive from it. */
enum class Tab(val route: Any, val label: String, val icon: Int) {
    YOUTUBE(YoutubeRoute, "YouTube", R.drawable.ic_youtube),
    LIBRARY(LibraryRoute, "Bibliothèque", R.drawable.ic_library),
    PLAYLISTS(PlaylistsRoute, "Playlists", R.drawable.ic_list_music),
    DOWNLOADS(DownloadsRoute, "Téléchargements", R.drawable.ic_download),
    SETTINGS(SettingsRoute, "Réglages", R.drawable.ic_settings),
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var pipeline: TrackPipeline

    @Inject lateinit var player: PlayerConnection

    @Inject lateinit var workManager: WorkManager

    /** Text shared from another app, consumed once by the UI. */
    private val sharedText = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            sharedText.value = intent.sharedText()
            workManager.schedulePeriodicSync()
            workManager.requestSync()
        }
        val webView = YoutubeWebView(this) // created once: survives tab switches (configChanges in manifest)
        setContent { FreeTubeTheme { App(webView) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.sharedText()?.let { sharedText.value = it }
    }

    private fun Intent.sharedText() = takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT)

    /** The SAME classifier as the WebView button, then the SAME enqueue. Returns the snackbar message. */
    private fun handleShare(text: String): String = when (val target = classifySharedText(text)) {
        is YoutubeTarget.Track -> download(target.videoId)
        is YoutubeTarget.TrackInPlaylist -> download(target.videoId)
        is YoutubeTarget.Playlist -> importPlaylist(target.listId)
        null -> "Lien YouTube non reconnu"
    }

    private fun download(videoId: VideoId): String {
        pipeline.enqueue(videoId)
        return "Ajouté aux téléchargements"
    }

    private fun importPlaylist(listId: PlaylistId): String {
        pipeline.importPlaylist(listId)
        return "Import de la playlist lancé"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun App(webView: YoutubeWebView) {
        val nav = rememberNavController()
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val shared by sharedText.collectAsState()
        val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        LaunchedEffect(shared) {
            val text = shared ?: return@LaunchedEffect
            sharedText.value = null
            snackbar.showSnackbar(handleShare(text))
        }
        val current by nav.currentBackStackEntryAsState()
        val playerState by player.state.collectAsState()
        val destination = current?.destination
        val tab = Tab.entries.firstOrNull { destination.isAt(it.route) }
        val inPlayer = destination.isAt(PlayerRoute)
        val selectedTab = tab ?: Tab.PLAYLISTS.takeIf { destination?.hasRoute(PlaylistRoute::class) == true }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(tab?.label ?: if (inPlayer) "En lecture" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        when {
                            // A page opened inside YouTube gets the same visible way back as a pushed screen.
                            tab == Tab.YOUTUBE && webView.canGoBackState -> IconAction(R.drawable.ic_arrow_left, "Retour") { webView.goBack() }
                            inPlayer -> IconAction(R.drawable.ic_chevron_down, "Fermer le lecteur") { nav.popBackStack() }
                            tab == null && destination != null -> IconAction(R.drawable.ic_arrow_left, "Retour") { nav.popBackStack() }
                        }
                    },
                )
            },
            bottomBar = {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    if (!inPlayer) {
                        MiniPlayer(player, playerState, onOpen = { nav.navigate(PlayerRoute) { launchSingleTop = true } })
                    }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                        Tab.entries.forEach { entry ->
                            NavigationBarItem(
                                selected = entry == selectedTab,
                                onClick = { nav.openTab(entry) },
                                // Icons only: the top bar already names the page, and French labels truncate with
                                // a large system font even one at a time. The label stays for screen readers.
                                icon = { Icon(painterResource(entry.icon), contentDescription = entry.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                ),
                            )
                        }
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            NavHost(
                nav,
                startDestination = YoutubeRoute,
                // Consumed so a screen's imePadding() only adds what the bars do not already cover.
                modifier = Modifier.padding(padding).consumeWindowInsets(padding),
                enterTransition = { fadeIn(tween(150)) },
                exitTransition = { fadeOut(tween(150)) },
            ) {
                composable<YoutubeRoute> {
                    YoutubeScreen(
                        webView,
                        onDownload = { scope.launch { snackbar.showSnackbar(download(it)) } },
                        onImport = { scope.launch { snackbar.showSnackbar(importPlaylist(it)) } },
                    )
                }
                composable<LibraryRoute> { LibraryScreen(hiltViewModel(), onBrowse = { nav.openTab(Tab.YOUTUBE) }) }
                composable<PlaylistsRoute> { PlaylistsScreen(hiltViewModel()) { nav.navigate(PlaylistRoute(it)) } }
                composable<PlaylistRoute> { PlaylistScreen(hiltViewModel(), onDeleted = { nav.popBackStack() }) }
                composable<DownloadsRoute> { DownloadsScreen(hiltViewModel()) }
                composable<SettingsRoute> { SettingsScreen(hiltViewModel()) }
                composable<PlayerRoute> { FullPlayer(player, playerState) }
            }
        }
    }
}

@Suppress("UNCHECKED_CAST")
private fun NavDestination?.isAt(route: Any) = this?.hasRoute(route::class as KClass<Any>) == true

private fun NavController.openTab(tab: Tab) = navigate(tab.route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
