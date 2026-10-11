package fr.appartfinder.freetube.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.appartfinder.freetube.playback.PlayerConnection
import fr.appartfinder.freetube.settings.Settings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import javax.inject.Inject

@Serializable data class PlaylistRoute(val id: String)

@HiltViewModel
class PlaylistsViewModel @Inject constructor(playlists: PlaylistDao, private val writer: LibraryWriter) : ViewModel() {
    val playlists: StateFlow<List<PlaylistEntity>?> =
        playlists.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun create(name: String) = viewModelScope.launch { writer.createPlaylist(name) }
}

@Composable
fun PlaylistsScreen(viewModel: PlaylistsViewModel, onOpen: (String) -> Unit) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    var creating by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        when {
            playlists == null -> Unit
            playlists!!.isEmpty() -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Aucune playlist. Créez-en une, ou importez-en une depuis l'onglet YouTube.")
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(playlists!!, key = { it.id }) { playlist ->
                    Text(
                        playlist.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(playlist.id) }.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { creating = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Text("Nouvelle playlist")
        }
    }
    if (creating) {
        TextFieldsDialog("Nouvelle playlist", listOf("Nom"), listOf(""), onConfirm = { viewModel.create(it.single()) }, onDismiss = { creating = false })
    }
}

/** A visible item: [position] is its index in the FULL stored list, which is what the writer edits. */
data class PlaylistRow(val position: Int, val track: TrackEntity)

/** [hidden] = items with no track row on this phone (e.g. not uploaded yet from elsewhere). */
data class PlaylistDetail(val playlist: PlaylistEntity, val rows: List<PlaylistRow>, val hidden: Int)

@HiltViewModel
class PlaylistViewModel @Inject constructor(
    savedState: SavedStateHandle,
    playlists: PlaylistDao,
    tracks: TrackDao,
    settings: Settings,
    private val writer: LibraryWriter,
    private val player: PlayerConnection,
) : ViewModel() {
    private val id = savedState.toRoute<PlaylistRoute>().id

    /** null while loading, and once the playlist is gone. */
    val detail: StateFlow<PlaylistDetail?> =
        combine(playlists.observe(id), playlists.observeItems(id), tracks.observeAll()) { playlist, items, all ->
            val byId = all.associateBy { it.videoId }
            val rows = items.mapNotNull { item -> byId[item.videoId]?.let { PlaylistRow(item.position, it) } }
            playlist?.let { PlaylistDetail(it, rows, items.size - rows.size) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val server: StateFlow<HttpUrl?> = settings.serverUrl().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Only the visible (playable) tracks become the queue. */
    fun play(index: Int) = detail.value?.let { d -> player.play(d.rows.map { it.track }, index) }

    /** Swaps with the neighbouring VISIBLE row: a hidden item in between must not swallow the move. */
    fun move(index: Int, by: Int) = viewModelScope.launch {
        val rows = detail.value?.rows ?: return@launch
        val other = rows.getOrNull(index + by) ?: return@launch
        writer.swapItems(id, rows[index].position, other.position)
    }

    fun remove(row: PlaylistRow) = viewModelScope.launch { writer.removeItem(id, row.position) }

    fun rename(name: String) = viewModelScope.launch { writer.renamePlaylist(id, name) }

    /** [onDone] leaves the screen only after the commit: leaving first would clear this scope mid-write. */
    fun delete(onDone: () -> Unit) = viewModelScope.launch {
        writer.deletePlaylist(id)
        onDone()
    }
}

@Composable
fun PlaylistScreen(viewModel: PlaylistViewModel, onDeleted: () -> Unit) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val server by viewModel.server.collectAsStateWithLifecycle()
    var renaming by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    val current = detail ?: return
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(current.playlist.name, style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { viewModel.play(0) }, enabled = current.rows.isNotEmpty()) { Text("Lire") }
                TextButton(onClick = { renaming = true }) { Text("Renommer") }
                TextButton(onClick = { deleting = true }) { Text("Supprimer") }
            }
            if (current.hidden > 0) {
                Text("${current.hidden} morceau(x) pas encore disponible(s) sur ce téléphone", style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(current.rows, key = { _, row -> row.position }) { index, row ->
                TrackRow(row.track, server, onClick = { viewModel.play(index) }) {
                    GlyphButton("↑", "Monter", enabled = index > 0) { viewModel.move(index, -1) }
                    GlyphButton("↓", "Descendre", enabled = index < current.rows.lastIndex) { viewModel.move(index, +1) }
                    GlyphButton("✕", "Retirer de la playlist") { viewModel.remove(row) }
                }
            }
        }
    }
    if (renaming) {
        TextFieldsDialog("Renommer la playlist", listOf("Nom"), listOf(current.playlist.name),
            onConfirm = { viewModel.rename(it.single()) }, onDismiss = { renaming = false })
    }
    if (deleting) {
        ConfirmDialog("Supprimer la playlist ?", "« ${current.playlist.name} » sera supprimée. Les morceaux restent dans la bibliothèque.",
            onConfirm = { viewModel.delete(onDeleted) }, onDismiss = { deleting = false })
    }
}

@Composable
private fun GlyphButton(glyph: String, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.semantics { contentDescription = description }) { Text(glyph) }
}
