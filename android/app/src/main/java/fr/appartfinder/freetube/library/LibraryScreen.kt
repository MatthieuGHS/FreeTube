package fr.appartfinder.freetube.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import fr.appartfinder.freetube.playback.PlayerConnection
import java.io.File
import javax.inject.Inject

/** The entity is the UI model: a parallel domain class would be a field-by-field copy. null = loading. */
@HiltViewModel
class LibraryViewModel @Inject constructor(tracks: TrackDao, private val player: PlayerConnection) : ViewModel() {
    val tracks: StateFlow<List<TrackEntity>?> =
        tracks.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The whole library becomes the queue, starting at the tapped track. */
    fun play(index: Int) = tracks.value?.let { player.play(it, index) }
}

@Composable
fun LibraryScreen(viewModel: LibraryViewModel) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    when {
        tracks == null -> Unit
        tracks!!.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Aucun morceau. Téléchargez-en depuis l'onglet YouTube.")
        }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(tracks!!, key = { _, track -> track.videoId }) { index, track -> TrackRow(track) { viewModel.play(index) } }
        }
    }
}

@Composable
private fun TrackRow(track: TrackEntity, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = track.localThumbPath?.let(::File),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(64.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2)
            Text("${track.artist} · ${formatDuration(track.durationS)}", style = MaterialTheme.typography.bodySmall)
            if (track.uploadState == UploadState.PENDING) {
                Text("Non envoyé", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

internal fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
