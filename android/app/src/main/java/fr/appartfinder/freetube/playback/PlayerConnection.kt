package fr.appartfinder.freetube.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.appartfinder.freetube.library.TrackEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class QueueEntry(val videoId: String, val title: String, val artist: String)

data class PlayerState(
    val queue: List<QueueEntry> = emptyList(),
    val index: Int = -1,
    val artworkUri: String? = null,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
) {
    val current: QueueEntry? get() = queue.getOrNull(index)
}

/**
 * The app's single MediaController: every screen reads [state] and sends commands here. Queue, shuffle
 * and repeat are ExoPlayer's own (also driven from the notification and the lock screen).
 * shortcut: connected for the whole process lifetime; release it when the UI is hidden if battery shows it.
 */
@Singleton
class PlayerConnection @Inject constructor(@ApplicationContext context: Context) {
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var controller: MediaController? = null

    init {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            controller = future.get().also { player ->
                player.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) = publish(player)
                })
                publish(player)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /** Plays the given tracks (the library, in display order) starting at [startIndex]. */
    fun play(tracks: List<TrackEntity>, startIndex: Int) {
        val player = controller ?: return
        // Only mediaId matters to the service; title/artist let the UI show the queue at once.
        val items = tracks.map {
            MediaItem.Builder().setMediaId(it.videoId)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).setArtist(it.artist).build()).build()
        }
        player.setMediaItems(items, startIndex, 0)
        player.prepare()
        player.play()
    }

    fun togglePlay() = controller?.run { if (isPlaying) pause() else play() }

    fun next() = controller?.seekToNext()

    fun previous() = controller?.seekToPrevious()

    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)

    fun skipTo(index: Int) = controller?.seekToDefaultPosition(index)

    fun toggleShuffle() = controller?.run { shuffleModeEnabled = !shuffleModeEnabled }

    /** Off -> all -> one -> off. */
    fun cycleRepeat() = controller?.run {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** Position is not pushed by Media3: the full-screen player polls it while visible. */
    fun position(): Pair<Long, Long> = controller?.let { it.currentPosition to it.duration.coerceAtLeast(0) } ?: (0L to 0L)

    private fun publish(player: Player) {
        val queue = (0 until player.mediaItemCount).map { i ->
            val item = player.getMediaItemAt(i)
            QueueEntry(item.mediaId, item.mediaMetadata.title?.toString().orEmpty(), item.mediaMetadata.artist?.toString().orEmpty())
        }
        _state.value = PlayerState(
            queue = queue,
            index = if (queue.isEmpty()) -1 else player.currentMediaItemIndex,
            artworkUri = player.mediaMetadata.artworkUri?.toString(),
            isPlaying = player.isPlaying,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
    }
}
