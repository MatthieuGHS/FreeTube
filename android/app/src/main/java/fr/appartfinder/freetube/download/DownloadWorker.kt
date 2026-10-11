package fr.appartfinder.freetube.download

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import fr.appartfinder.freetube.library.TrackDao
import fr.appartfinder.freetube.library.TrackEntity
import fr.appartfinder.freetube.library.UploadState
import fr.appartfinder.freetube.youtube.VideoId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

const val DOWNLOADS_CHANNEL = "downloads"

/**
 * Thumbnail -> filesDir/thumbs, yt-dlp -> filesDir/audio, then the Room row LAST: a row always means
 * "both files are final". Foreground (dataSync) with a progress notification (brief §8).
 * Idempotent: row + file present -> nothing to do; otherwise everything is redone from scratch.
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val ytDlp: YtDlp,
    private val tracks: TrackDao,
    private val http: OkHttpClient, // base client: no Bearer
) : CoroutineWorker(context, params) {
    private val notifications = context.getSystemService(NotificationManager::class.java)

    override suspend fun doWork(): Result {
        val videoId = VideoId.parse(inputData.getString(TrackPipeline.KEY_VIDEO_ID)) ?: return outcome(PipelineError.UNKNOWN)
        val existing = tracks.get(videoId.value) // a server-only row (from sync) keeps its title, date and state
        if (existing?.localAudioPath?.let(::File)?.isFile == true) return Result.success()
        try {
            setForeground(foregroundInfo(videoId, title = null, percent = null))
        } catch (e: IllegalStateException) {
            // Android 12+ refuses a foreground service started from the background (backoff retry with the
            // app closed): run as a plain worker, a track takes well under its 10-minute budget.
        }
        return try {
            val thumb = fetchThumbnail(videoId)
            val audio = ytDlp.download(videoId) { report(videoId, it) }
            tracks.upsert(
                TrackEntity(
                    videoId = videoId.value,
                    title = existing?.title ?: audio.meta.title,
                    artist = existing?.artist ?: audio.meta.artist,
                    durationS = audio.meta.durationS,
                    format = audio.meta.format,
                    localAudioPath = audio.file.path,
                    localThumbPath = thumb.path,
                    uploadState = existing?.uploadState ?: UploadState.PENDING,
                    addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                ),
            )
            Result.success()
        } catch (e: PipelineException) {
            outcome(e.error)
        } catch (e: IOException) {
            outcome(PipelineError.NETWORK)
        }
        // CancellationException is not caught: WorkManager reschedules a stopped worker itself.
    }

    /** Needed for expedited work below Android 12, where it runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(VideoId.parse(inputData.getString(TrackPipeline.KEY_VIDEO_ID)), title = null, percent = null)

    private fun report(videoId: VideoId, progress: DownloadProgress) {
        setProgressAsync(workDataOf(TrackPipeline.KEY_PERCENT to (progress.percent ?: -1), TrackPipeline.KEY_TITLE to progress.title))
        notifications.notify(notificationId(videoId), notification(progress.title, progress.percent))
    }

    /** maxresdefault, then hqdefault (always exists). Written to a .tmp file, then renamed. */
    private suspend fun fetchThumbnail(videoId: VideoId): File = withContext(Dispatchers.IO) {
        val dir = File(applicationContext.filesDir, "thumbs").apply { mkdirs() }
        val target = File(dir, "$videoId.jpg")
        val tmp = File(dir, "$videoId.jpg.tmp")
        for (name in listOf("maxresdefault", "hqdefault")) {
            http.newCall(Request.Builder().url("https://i.ytimg.com/vi/$videoId/$name.jpg").build()).execute().use { response ->
                if (response.code == 404) return@use
                if (!response.isSuccessful) throw IOException("thumbnail HTTP ${response.code}")
                tmp.outputStream().use { response.body.byteStream().copyTo(it) }
                if (!tmp.renameTo(target)) throw IOException("thumbnail rename failed")
                return@withContext target
            }
        }
        throw IOException("no thumbnail")
    }

    private fun foregroundInfo(videoId: VideoId?, title: String?, percent: Int?): ForegroundInfo {
        val id = videoId?.let(::notificationId) ?: 0
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification(title, percent), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification(title, percent))
        }
    }

    private fun notification(title: String?, percent: Int?): Notification =
        NotificationCompat.Builder(applicationContext, DOWNLOADS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title ?: "Téléchargement")
            .setContentText(percent?.let { "$it %" } ?: "Préparation…")
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun notificationId(videoId: VideoId) = videoId.value.hashCode()
}
