package fr.appartfinder.freetube.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil3.compose.AsyncImage
import fr.appartfinder.freetube.library.formatDuration
import kotlinx.coroutines.delay

/** Persistent bar above the tabs, shown as soon as something is loaded. */
@Composable
fun MiniPlayer(state: PlayerState, onOpen: () -> Unit, onToggle: () -> Unit) {
    val current = state.current ?: return
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onOpen).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AsyncImage(state.artworkUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp))
        Column(Modifier.weight(1f)) {
            Text(current.title, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
            Text(current.artist, maxLines = 1, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onToggle) { Text(if (state.isPlaying) "Pause" else "Lecture") }
    }
}

@Composable
fun FullPlayer(connection: PlayerConnection, state: PlayerState) {
    val current = state.current
    var position by remember { mutableStateOf(0L to 0L) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(state.isPlaying, state.index) {
        while (true) { // Media3 does not push the position
            position = connection.position()
            delay(500)
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            AsyncImage(state.artworkUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f))
        }
        item {
            Text(current?.title ?: "Rien en lecture", style = MaterialTheme.typography.titleLarge)
            Text(current?.artist.orEmpty(), style = MaterialTheme.typography.bodyMedium)
        }
        item {
            val (pos, duration) = position
            Slider(
                value = dragging ?: if (duration > 0) pos.toFloat() / duration else 0f,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { connection.seekTo((it * duration).toLong()) }
                    dragging = null
                },
                enabled = duration > 0,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDuration((pos / 1000).toInt()), style = MaterialTheme.typography.bodySmall)
                Text(formatDuration((duration / 1000).toInt()), style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = connection::toggleShuffle) { Text(if (state.shuffle) "Aléatoire : oui" else "Aléatoire : non") }
                TextButton(onClick = connection::previous) { Text("Précédent") }
                TextButton(onClick = connection::togglePlay) { Text(if (state.isPlaying) "Pause" else "Lecture") }
                TextButton(onClick = connection::next) { Text("Suivant") }
            }
            TextButton(onClick = connection::cycleRepeat, modifier = Modifier.fillMaxWidth()) {
                Text(
                    when (state.repeatMode) {
                        Player.REPEAT_MODE_ALL -> "Répétition : tout"
                        Player.REPEAT_MODE_ONE -> "Répétition : ce morceau"
                        else -> "Répétition : non"
                    },
                )
            }
        }
        item { Text("File d'attente", style = MaterialTheme.typography.titleMedium) }
        itemsIndexed(state.queue) { index, entry ->
            Text(
                "${entry.title} · ${entry.artist}",
                fontWeight = if (index == state.index) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.fillMaxWidth().clickable { connection.skipTo(index) }.padding(vertical = 6.dp),
                maxLines = 1,
            )
        }
    }
}
