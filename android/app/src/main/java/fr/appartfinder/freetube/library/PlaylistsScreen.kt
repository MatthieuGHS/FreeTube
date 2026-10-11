package fr.appartfinder.freetube.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.appartfinder.freetube.R
import fr.appartfinder.freetube.playback.PlayerConnection
import fr.appartfinder.freetube.settings.Settings
import fr.appartfinder.freetube.ui.EmptyState
import fr.appartfinder.freetube.ui.IconAction
import fr.appartfinder.freetube.ui.MenuAction
import fr.appartfinder.freetube.ui.OverflowMenu
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
            playlists!!.isEmpty() -> EmptyState(
                R.drawable.ic_list_music, "Aucune playlist. Créez-en une, ou importez-en une depuis YouTube.", Modifier.fillMaxSize(),
            )
            // Bottom padding: the last row must scroll clear of the FAB.
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(playlists!!, key = { it.id }) { playlist ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable { onOpen(playlist.id) }.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(40.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) { Icon(painterResource(R.drawable.ic_list_music), null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(
                            playlist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            text = { Text("Nouvelle playlist") },
            icon = { Icon(painterResource(R.drawable.ic_plus), null) },
            onClick = { creating = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
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
    val server: StateFlow<HttpUrl?> = settings.serverUrl.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
                Text(current.playlist.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 12.dp))
                Text(trackCount(current.rows.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (current.hidden > 0) {
                    Text("${current.hidden} morceau(x) pas encore disponible(s) sur ce téléphone",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { viewModel.play(0) }, enabled = current.rows.isNotEmpty(), shape = MaterialTheme.shapes.small) {
                        Icon(painterResource(R.drawable.ic_play), null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Lire")
                    }
                    Spacer(Modifier.weight(1f))
                    IconAction(R.drawable.ic_pencil, "Renommer la playlist") { renaming = true }
                    IconAction(R.drawable.ic_trash_2, "Supprimer la playlist") { deleting = true }
                }
            }
        }
        if (current.rows.isEmpty()) {
            item { EmptyState(R.drawable.ic_list_music, "Cette playlist est vide. Ajoutez des morceaux depuis la Bibliothèque.") }
        }
        itemsIndexed(current.rows, key = { _, row -> row.position }) { index, row ->
            TrackRow(row.track, server, onClick = { viewModel.play(index) }) {
                OverflowMenu("Options du morceau") { close ->
                    MenuAction("Monter", R.drawable.ic_arrow_up, enabled = index > 0) { close(); viewModel.move(index, -1) }
                    MenuAction("Descendre", R.drawable.ic_arrow_down, enabled = index < current.rows.lastIndex) { close(); viewModel.move(index, +1) }
                    MenuAction("Retirer de la playlist", R.drawable.ic_x, destructive = true) { close(); viewModel.remove(row) }
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

private fun trackCount(n: Int) = if (n <= 1) "$n morceau" else "$n morceaux"
