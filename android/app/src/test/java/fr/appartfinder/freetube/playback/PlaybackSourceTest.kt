package fr.appartfinder.freetube.playback

import fr.appartfinder.freetube.library.AudioFormat
import fr.appartfinder.freetube.library.TrackEntity
import fr.appartfinder.freetube.library.UploadState
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackSourceTest {
    private val server = "https://appartfinder.fr/".toHttpUrl()
    private val track = TrackEntity("dQw4w9WgXcQ", "T", "A", 1, AudioFormat.M4A, "/f/audio/x.m4a", "/f/thumbs/x.jpg", UploadState.UPLOADED, 0)

    @Test
    fun localFileWinsForOfflinePlayback() =
        assertEquals(PlaybackSource("file:///f/audio/x.m4a", "file:///f/thumbs/x.jpg"), playbackSource(track, server) { true })

    @Test
    fun missingLocalFileStreamsFromTheServer() = assertEquals(
        PlaybackSource("https://appartfinder.fr/api/tracks/dQw4w9WgXcQ/stream", "https://appartfinder.fr/api/tracks/dQw4w9WgXcQ/thumb"),
        playbackSource(track, server) { false },
    )

    @Test
    fun serverOnlyTrackOfM5StreamsToo() = assertEquals(
        "https://appartfinder.fr/api/tracks/dQw4w9WgXcQ/stream",
        playbackSource(track.copy(localAudioPath = null, localThumbPath = null), server) { true }?.uri,
    )

    @Test
    fun noFileAndNoServerIsNotPlayable() = assertNull(playbackSource(track, null) { false })
}
