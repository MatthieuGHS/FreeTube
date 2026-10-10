package fr.appartfinder.freetube.debug

import android.content.ComponentName
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import fr.appartfinder.freetube.playback.PlaybackService

@Composable
fun DebugScreen(viewModel: DebugViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controller = rememberMediaController()

    Column(
        Modifier.safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Debug M0 — vidéo $TEST_VIDEO_ID")
        Text(state.status)
        state.progress?.let { LinearProgressIndicator(progress = { it }, Modifier.fillMaxWidth()) }
        Button(onClick = viewModel::download, enabled = !state.busy) { Text("Télécharger") }
        Button(
            onClick = {
                val file = state.file ?: return@Button
                controller?.run {
                    setMediaItem(
                        MediaItem.Builder()
                            .setUri(Uri.fromFile(file))
                            .setMediaMetadata(MediaMetadata.Builder().setTitle("Test M0").setArtist(TEST_VIDEO_ID).build())
                            .build(),
                    )
                    prepare()
                    play()
                }
            },
            enabled = state.file != null && controller != null,
        ) { Text("Lire") }
        Button(onClick = { controller?.pause() }, enabled = controller != null) { Text("Pause") }
        Button(onClick = viewModel::updateEngine, enabled = !state.busy) { Text("Mettre à jour le moteur") }
        Text("yt-dlp : ${state.engineVersion ?: "non initialisé"}")
    }
}

@Composable
private fun rememberMediaController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ controller = future.get() }, ContextCompat.getMainExecutor(context))
        onDispose {
            controller = null
            MediaController.releaseFuture(future)
        }
    }
    return controller
}
