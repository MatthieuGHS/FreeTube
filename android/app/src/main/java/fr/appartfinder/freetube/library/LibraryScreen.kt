package fr.appartfinder.freetube.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.appartfinder.freetube.playback.PlayerConnection
import fr.appartfinder.freetube.settings.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import javax.inject.Inject

/** The entity is the UI model: a parallel domain class would be a field-by-field copy. null = loading. */
@HiltViewModel
class LibraryViewModel @Inject constructor(
    tracks: TrackDao,
    playlists: PlaylistDao,
    settings: Settings,
    private val writer: LibraryWriter,
    private val player: PlayerConnection,
) : ViewModel() {
    val tracks: StateFlow<List<TrackEntity>?> =
        tracks.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val playlists: StateFlow<List<PlaylistEntity>> =
        playlists.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val server: StateFlow<HttpUrl?> = settings.serverUrl().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The whole library becomes the queue, starting at the tapped track. */
    fun play(index: Int) = tracks.value?.let { player.play(it, index) }

    fun addTo(playlistId: String, track: TrackEntity) = viewModelScope.launch { writer.addToPlaylist(playlistId, track.videoId) }

    fun rename(track: TrackEntity, title: String, artist: String) = viewModelScope.launch { writer.renameTrack(track.videoId, title, artist) }

    fun delete(track: TrackEntity) = viewModelScope.launch { writer.deleteTrack(track.videoId) }
}

/** Base URL for server thumbnails of tracks with no local file. */
internal fun Settings.serverUrl(): Flow<HttpUrl?> = snapshot.map { it.serverUrl.toHttpUrlOrNull() }

private sealed interface TrackDialog {
    val track: TrackEntity

    data class AddTo(override val track: TrackEntity) : TrackDialog

    data class Rename(override val track: TrackEntity) : TrackDialog

    data class Delete(override val track: TrackEntity) : TrackDialog
}

@Composable
fun LibraryScreen(viewModel: LibraryViewModel) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val server by viewModel.server.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<TrackDialog?>(null) }
    when {
        tracks == null -> Unit
        tracks!!.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text("Aucun morceau. Téléchargez-en depuis l'onglet YouTube.")
        }
        else -> LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(tracks!!, key = { _, track -> track.videoId }) { index, track ->
                TrackRow(track, server, onClick = { viewModel.play(index) }) {
                    TrackMenu(onSelect = { dialog = it(track) })
                }
            }
        }
    }
    when (val d = dialog) {
        null -> Unit
        is TrackDialog.AddTo -> PlaylistPicker(viewModel, onPick = { viewModel.addTo(it, d.track) }, onDismiss = { dialog = null })
        is TrackDialog.Rename -> TextFieldsDialog(
            "Renommer le morceau", listOf("Titre", "Artiste"), listOf(d.track.title, d.track.artist),
            onConfirm = { (title, artist) -> viewModel.rename(d.track, title, artist) }, onDismiss = { dialog = null },
        )
        is TrackDialog.Delete -> ConfirmDialog(
            "Supprimer le morceau ?", "« ${d.track.title} » sera supprimé de ce téléphone et du serveur.",
            onConfirm = { viewModel.delete(d.track) }, onDismiss = { dialog = null },
        )
    }
}

@Composable
private fun TrackMenu(onSelect: ((TrackEntity) -> TrackDialog) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "Options du morceau" }) { Text("⋮") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Ajouter à une playlist") }, onClick = { open = false; onSelect(TrackDialog::AddTo) })
            DropdownMenuItem(text = { Text("Renommer") }, onClick = { open = false; onSelect(TrackDialog::Rename) })
            DropdownMenuItem(text = { Text("Supprimer") }, onClick = { open = false; onSelect(TrackDialog::Delete) })
        }
    }
}

@Composable
private fun PlaylistPicker(viewModel: LibraryViewModel, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ajouter à une playlist") },
        text = {
            if (playlists.isEmpty()) {
                Text("Aucune playlist. Créez-en une dans l'onglet Playlists.")
            } else {
                LazyColumn {
                    items(playlists, key = { it.id }) { playlist ->
                        Text(
                            playlist.name,
                            Modifier.fillMaxWidth().clickable { onPick(playlist.id); onDismiss() }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

/** Shared by the library and a playlist's detail; [actions] sit at the end of the row. */
@Composable
internal fun TrackRow(track: TrackEntity, server: HttpUrl?, onClick: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            // The local file wins; a server-only track loads /thumb through the authed client.
            model = track.localThumbPath?.let(::File) ?: server?.resolve("api/tracks/${track.videoId}/thumb")?.toString(),
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
        actions()
    }
}

internal fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
