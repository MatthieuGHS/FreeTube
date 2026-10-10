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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import fr.appartfinder.freetube.download.DownloadsScreen
import fr.appartfinder.freetube.download.TrackPipeline
import fr.appartfinder.freetube.library.LibraryScreen
import fr.appartfinder.freetube.settings.SettingsScreen
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

@Serializable data object DownloadsRoute

@Serializable data object SettingsRoute

/** The one list of tabs: the bottom bar and the NavHost both derive from it. */
enum class Tab(val route: Any, val label: String, val icon: Int) {
    YOUTUBE(YoutubeRoute, "YouTube", android.R.drawable.ic_menu_view),
    LIBRARY(LibraryRoute, "Bibliothèque", android.R.drawable.ic_menu_agenda),
    DOWNLOADS(DownloadsRoute, "Téléchargements", android.R.drawable.stat_sys_download_done),
    SETTINGS(SettingsRoute, "Réglages", android.R.drawable.ic_menu_preferences),
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var pipeline: TrackPipeline

    /** Text shared from another app, consumed once by the UI. */
    private val sharedText = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) sharedText.value = intent.sharedText()
        val webView = YoutubeWebView(this) // created once: survives tab switches (configChanges in manifest)
        setContent { MaterialTheme { App(webView) } }
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
        is YoutubeTarget.Playlist -> "Import de playlist : pas encore disponible"
        null -> "Lien YouTube non reconnu"
    }

    private fun download(videoId: fr.appartfinder.freetube.youtube.VideoId): String {
        pipeline.enqueue(videoId)
        return "Ajouté aux téléchargements"
    }

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
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        @Suppress("UNCHECKED_CAST")
                        val selected = current?.destination?.hasRoute(tab.route::class as KClass<Any>) == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            NavHost(nav, startDestination = YoutubeRoute, modifier = Modifier.padding(padding)) {
                composable<YoutubeRoute> {
                    YoutubeScreen(webView) { videoId ->
                        scope.launch { snackbar.showSnackbar(download(videoId)) }
                    }
                }
                composable<LibraryRoute> { LibraryScreen(hiltViewModel()) }
                composable<DownloadsRoute> { DownloadsScreen(hiltViewModel()) }
                composable<SettingsRoute> { SettingsScreen(hiltViewModel()) }
            }
        }
    }
}
