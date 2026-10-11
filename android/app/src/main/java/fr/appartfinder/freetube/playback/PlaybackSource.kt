package fr.appartfinder.freetube.playback

import fr.appartfinder.freetube.library.TrackEntity
import okhttp3.HttpUrl
import java.io.File

/** Where a track is read from and its artwork, as plain strings (pure, unit-tested). */
data class PlaybackSource(val uri: String, val artworkUri: String?)

/**
 * The local file wins (offline playback); otherwise the server's /stream, which answers 302 to a
 * pre-signed S3 URL. null = not playable (no local file and no server configured).
 */
fun playbackSource(track: TrackEntity, server: HttpUrl?, exists: (String) -> Boolean = { File(it).isFile }): PlaybackSource? {
    val thumb = track.localThumbPath?.takeIf(exists)?.let { "file://$it" }
        ?: server?.resolve("api/tracks/${track.videoId}/thumb")?.toString()
    val local = track.localAudioPath?.takeIf(exists)?.let { "file://$it" }
    val audio = local ?: server?.resolve("api/tracks/${track.videoId}/stream")?.toString() ?: return null
    return PlaybackSource(audio, thumb)
}
