package fr.appartfinder.freetube.playback

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.annotation.OptIn
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import fr.appartfinder.freetube.MainActivity
import fr.appartfinder.freetube.api.Authed
import fr.appartfinder.freetube.library.TrackDao
import fr.appartfinder.freetube.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Owns the player. Media3 derives the notification and lock-screen controls from the session.
 * Controllers send only a mediaId (= videoId): URIs do not cross the controller/session boundary, so
 * this service is the one place that turns a track into a source (local file or server stream).
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {
    @Inject lateinit var tracks: TrackDao

    @Inject lateinit var settings: Settings

    @Inject @Authed
    lateinit var http: OkHttpClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null

    // OkHttpDataSource and DataSourceBitmapLoader are Media3 "unstable" APIs: the documented way to plug
    // OkHttp in, accepted knowing a Media3 upgrade may require small changes here.
    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // file:// through DefaultDataSource, http(s) through OkHttp with the token for the server host only.
        val dataSources = DefaultDataSource.Factory(this, OkHttpDataSource.Factory(http))
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK) // keeps CPU and Wi-Fi awake: streaming works screen off
            .build()
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(ResolvingCallback())
            .setBitmapLoader(DataSourceBitmapLoader.Builder(this).setDataSourceFactory(dataSources).build())
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private inner class ResolvingCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val result = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                result.set(runCatching { mediaItems.mapNotNull { resolve(it.mediaId) }.toMutableList() }.getOrElse { mutableListOf() })
            }
            return result
        }

        /** Unknown or unplayable tracks are dropped from the queue rather than failing it. */
        private suspend fun resolve(videoId: String): MediaItem? {
            val track = tracks.get(videoId) ?: return null
            val source = playbackSource(track, settings.server()?.baseUrl) ?: return null
            return MediaItem.Builder()
                .setMediaId(videoId)
                .setUri(source.uri)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(track.title)
                        .setArtist(track.artist)
                        .setArtworkUri(source.artworkUri?.let(android.net.Uri::parse))
                        .build(),
                )
                .build()
        }
    }
}
