package fr.appartfinder.freetube.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.appartfinder.freetube.R
import fr.appartfinder.freetube.playback.PlayerConnection
import fr.appartfinder.freetube.settings.Settings
import fr.appartfinder.freetube.ui.Artwork
import fr.appartfinder.freetube.ui.EmptyState
import fr.appartfinder.freetube.ui.MenuAction
import fr.appartfinder.freetube.ui.OverflowMenu
import fr.appartfinder.freetube.ui.tabular
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
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
    val server: StateFlow<HttpUrl?> = settings.serverUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The whole library becomes the queue, starting at the tapped track. */
    fun play(index: Int) = tracks.value?.let { player.play(it, index) }

    fun addTo(playlistId: String, track: TrackEntity) = viewModelScope.launch { writer.addToPlaylist(playlistId, track.videoId) }

    fun rename(track: TrackEntity, title: String, artist: String) = viewModelScope.launch { writer.renameTrack(track.videoId, title, artist) }

    fun delete(track: TrackEntity) = viewModelScope.launch { writer.deleteTrack(track.videoId) }
}

private sealed interface TrackDialog {
    val track: TrackEntity

    data class AddTo(override val track: TrackEntity) : TrackDialog

    data class Rename(override val track: TrackEntity) : TrackDialog

    data class Delete(override val track: TrackEntity) : TrackDialog
}

@Composable
fun LibraryScreen(viewModel: LibraryViewModel, onBrowse: () -> Unit) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val server by viewModel.server.collectAsStateWithLifecycle()
    var dialog by remember { mutableStateOf<TrackDialog?>(null) }
    when {
        tracks == null -> Unit
        tracks!!.isEmpty() -> EmptyState(
            R.drawable.ic_library, "Aucun morceau pour l'instant. Téléchargez-en depuis YouTube.",
            Modifier.fillMaxSize(),
            action = "Ouvrir YouTube" to onBrowse,
        )
        else -> LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(tracks!!, key = { _, track -> track.videoId }) { index, track ->
                TrackRow(track, server, onClick = { viewModel.play(index) }) {
                    OverflowMenu("Options du morceau") { close ->
                        MenuAction("Ajouter à une playlist", R.drawable.ic_list_plus) { close(); dialog = TrackDialog.AddTo(track) }
                        MenuAction("Renommer", R.drawable.ic_pencil) { close(); dialog = TrackDialog.Rename(track) }
                        MenuAction("Supprimer", R.drawable.ic_trash_2, destructive = true) { close(); dialog = TrackDialog.Delete(track) }
                    }
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
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                                .clickable { onPick(playlist.id); onDismiss() }.padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(painterResource(R.drawable.ic_list_music), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { CancelButton(onDismiss) },
    )
}

/** Shared by the library and a playlist's detail; [actions] sit at the end of the row. Tapping the row plays. */
@Composable
internal fun TrackRow(track: TrackEntity, server: HttpUrl?, onClick: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The local file wins; a server-only track loads /thumb through the authed client.
        Artwork(
            track.localThumbPath?.let(::File) ?: server?.resolve("api/tracks/${track.videoId}/thumb")?.toString(),
            Modifier.size(width = 64.dp, height = 36.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (track.uploadState == UploadState.PENDING) "${track.artist} · Non envoyé" else track.artist,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(formatDuration(track.durationS), style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
        actions()
    }
}

internal fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
