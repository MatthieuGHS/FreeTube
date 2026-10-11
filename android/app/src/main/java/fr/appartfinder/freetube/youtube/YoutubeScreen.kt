package fr.appartfinder.freetube.youtube

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import fr.appartfinder.freetube.R
import fr.appartfinder.freetube.ui.MenuAction

const val YOUTUBE_HOME = "https://m.youtube.com/"

private val ALLOWED_HOST = Regex("""(^|\.)youtube\.com$|^youtu\.be$""")

/**
 * Created once by MainActivity and only attached by [YoutubeScreen], so switching tabs does not reload
 * YouTube. Process death reloads the home page: acceptable.
 */
@SuppressLint("SetJavaScriptEnabled")
class YoutubeWebView(context: Context) : WebView(context) {
    var currentUrl: String? by mutableStateOf(null)
        private set
    var canGoBackState: Boolean by mutableStateOf(false)
        private set

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webViewClient = object : WebViewClient() {
            // m.youtube.com is a SPA: history updates, not page loads, carry the URL (brief §8).
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                currentUrl = url
                canGoBackState = view.canGoBack()
            }

            /** Stay on YouTube (consent.youtube.com included): any other navigation is blocked. */
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host?.lowercase()?.let(ALLOWED_HOST::containsMatchIn) != true
        }
        loadUrl(YOUTUBE_HOME)
    }
}

@Composable
fun YoutubeScreen(webView: YoutubeWebView, onDownload: (VideoId) -> Unit, onImport: (PlaylistId) -> Unit) {
    BackHandler(enabled = webView.canGoBackState) { webView.goBack() }
    val target = remember(webView.currentUrl) { webView.currentUrl?.let(::classifyYoutubeUrl) }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { webView.also { (it.parent as? ViewGroup)?.removeView(it) } },
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            when (target) {
                is YoutubeTarget.Track -> DownloadFab("Télécharger ce morceau") { onDownload(target.videoId) }
                is YoutubeTarget.Playlist -> DownloadFab("Télécharger la playlist") { onImport(target.listId) }
                is YoutubeTarget.TrackInPlaylist -> {
                    var open by remember(target) { mutableStateOf(false) }
                    DownloadFab("Télécharger…") { open = true }
                    DropdownMenu(open, onDismissRequest = { open = false }, shape = MaterialTheme.shapes.medium) {
                        MenuAction("Ce morceau", R.drawable.ic_music) { open = false; onDownload(target.videoId) }
                        MenuAction("Toute la playlist", R.drawable.ic_list_music) { open = false; onImport(target.listId) }
                    }
                }
                null -> Unit
            }
        }
    }
}

/** The one primary action of the tab, in the accent. */
@Composable
private fun DownloadFab(label: String, onClick: () -> Unit) = ExtendedFloatingActionButton(
    text = { Text(label) },
    icon = { Icon(painterResource(R.drawable.ic_download), contentDescription = null) },
    onClick = onClick,
    containerColor = MaterialTheme.colorScheme.primary,
    contentColor = MaterialTheme.colorScheme.onPrimary,
)
