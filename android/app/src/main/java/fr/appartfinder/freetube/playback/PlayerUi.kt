package fr.appartfinder.freetube.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import fr.appartfinder.freetube.R
import fr.appartfinder.freetube.library.formatDuration
import fr.appartfinder.freetube.ui.Artwork
import fr.appartfinder.freetube.ui.IconAction
import fr.appartfinder.freetube.ui.tabular
import kotlinx.coroutines.delay

/** (position, duration) in ms, polled: Media3 does not push the position. */
@Composable
private fun rememberPosition(connection: PlayerConnection, state: PlayerState): Pair<Long, Long> {
    var position by remember { mutableStateOf(0L to 0L) }
    LaunchedEffect(state.isPlaying, state.index) {
        while (true) {
            position = connection.position()
            delay(500)
        }
    }
    return position
}

/** Persistent bar above the tabs, shown as soon as something is loaded. */
@Composable
fun MiniPlayer(connection: PlayerConnection, state: PlayerState, onOpen: () -> Unit) {
    val current = state.current ?: return
    val (pos, duration) = rememberPosition(connection, state)
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClickLabel = "Ouvrir le lecteur", onClick = onOpen),
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
            Box(
                Modifier.fillMaxWidth(if (duration > 0) (pos.toFloat() / duration).coerceIn(0f, 1f) else 0f)
                    .fillMaxHeight().background(MaterialTheme.colorScheme.primary),
            )
        }
        Row(
            Modifier.fillMaxWidth().height(60.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Artwork(state.artworkUri, Modifier.size(width = 64.dp, height = 36.dp))
            Column(Modifier.weight(1f)) {
                Text(current.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    current.artist, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconAction(
                if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (state.isPlaying) "Pause" else "Lecture",
                onClick = connection::togglePlay,
            )
        }
    }
}

@Composable
fun FullPlayer(connection: PlayerConnection, state: PlayerState) {
    val current = state.current
    val (pos, duration) = rememberPosition(connection, state)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp)) {
        item {
            Artwork(state.artworkUri, Modifier.fillMaxWidth().aspectRatio(1f), shape = MaterialTheme.shapes.large, iconSize = 64.dp)
        }
        item {
            Column(Modifier.padding(top = 24.dp)) {
                Text(current?.title ?: "Rien en lecture", style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(current?.artist.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item {
            Slider(
                value = dragging ?: if (duration > 0) pos.toFloat() / duration else 0f,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { connection.seekTo((it * duration).toLong()) }
                    dragging = null
                },
                enabled = duration > 0,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val shown = dragging?.let { (it * duration).toLong() } ?: pos
                Text(formatDuration((shown / 1000).toInt()), style = MaterialTheme.typography.bodySmall.tabular, color = muted)
                Text(formatDuration((duration / 1000).toInt()), style = MaterialTheme.typography.bodySmall.tabular, color = muted)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModeToggle(state.shuffle, R.drawable.ic_shuffle, "Aléatoire", connection::toggleShuffle)
                IconAction(R.drawable.ic_skip_back, "Précédent", onClick = connection::previous)
                FilledIconButton(onClick = connection::togglePlay, modifier = Modifier.size(64.dp), shape = CircleShape) {
                    Icon(
                        painterResource(if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                        contentDescription = if (state.isPlaying) "Pause" else "Lecture",
                        modifier = Modifier.size(28.dp),
                    )
                }
                IconAction(R.drawable.ic_skip_forward, "Suivant", onClick = connection::next)
                ModeToggle(
                    on = state.repeatMode != Player.REPEAT_MODE_OFF,
                    icon = if (state.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_1 else R.drawable.ic_repeat,
                    description = when (state.repeatMode) {
                        Player.REPEAT_MODE_ALL -> "Répétition : tout"
                        Player.REPEAT_MODE_ONE -> "Répétition : ce morceau"
                        else -> "Répétition : non"
                    },
                    onClick = connection::cycleRepeat,
                )
            }
        }
        if (state.queue.isNotEmpty()) {
            item { Text("File d'attente", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)) }
        }
        itemsIndexed(state.queue) { index, entry ->
            val playing = index == state.index
            Column(
                Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { connection.skipTo(index) }.padding(vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (playing) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(entry.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

/** Shuffle / repeat: accent when on, and TalkBack announces the on/off state. */
@Composable
private fun ModeToggle(on: Boolean, icon: Int, description: String, onClick: () -> Unit) {
    IconToggleButton(
        checked = on,
        onCheckedChange = { onClick() },
        colors = IconButtonDefaults.iconToggleButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            checkedContentColor = MaterialTheme.colorScheme.primary,
        ),
    ) { Icon(painterResource(icon), contentDescription = description) }
}
