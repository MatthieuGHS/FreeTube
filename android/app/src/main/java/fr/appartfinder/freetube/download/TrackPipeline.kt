package fr.appartfinder.freetube.download

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.workDataOf
import fr.appartfinder.freetube.library.TrackDao
import fr.appartfinder.freetube.library.TrackEntity
import fr.appartfinder.freetube.library.UploadState
import fr.appartfinder.freetube.youtube.PlaylistId
import fr.appartfinder.freetube.youtube.VideoId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** What a Downloads row is about: one track's chain, or a YouTube playlist being read. */
sealed interface JobSubject {
    data class Track(val videoId: VideoId) : JobSubject

    data class Playlist(val listId: PlaylistId) : JobSubject
}

/** One line of the Downloads screen. Uploaded tracks have no row: they live in the Library. */
data class DownloadRow(val subject: JobSubject, val title: String?, val status: DownloadStatus)

sealed interface DownloadStatus {
    data object Queued : DownloadStatus // waiting for network, for the engine, or for the previous step
    data object Importing : DownloadStatus // reading a playlist's entries
    data class Downloading(val percent: Int?) : DownloadStatus
    data object Uploading : DownloadStatus
    data class Failed(val error: PipelineError) : DownloadStatus
    data object NotSent : DownloadStatus // downloaded, not uploaded, no job left (e.g. no token at the time)
}

/**
 * The only writer AND reader of track jobs: unique name, tags, constraints and backoff live here only.
 * Callers: YouTube button, share intent, Downloads "Relancer", Settings (token saved), playlist import.
 */
@Singleton
class TrackPipeline @Inject constructor(
    private val workManager: WorkManager,
    private val tracks: TrackDao,
) {
    /**
     * Idempotent. KEEP: a pending chain wins; a finished (failed) chain is replaced by a fresh one.
     * If the track is already on the phone, DownloadWorker returns at once and only the upload runs.
     */
    fun enqueue(videoId: VideoId) {
        workManager
            .beginUniqueWork("track-$videoId", ExistingWorkPolicy.KEEP, trackRequest<DownloadWorker>(videoId, Stage.DOWNLOAD))
            .then(trackRequest<UploadWorker>(videoId, Stage.UPLOAD))
            .enqueue()
    }

    /**
     * KEEP: a second tap while the import runs is ignored. The FreeTube playlist id is drawn here, once per
     * import: a retried worker rewrites the same playlist instead of creating a second one.
     */
    fun importPlaylist(listId: PlaylistId) {
        val input = workDataOf(KEY_LIST_ID to listId.value, KEY_PLAYLIST_ID to UUID.randomUUID().toString())
        workManager.enqueueUniqueWork("playlist-${listId.value}", ExistingWorkPolicy.KEEP, request<ResolvePlaylistWorker>(input, LIST_TAG + listId.value, Stage.IMPORT))
    }

    /** Track deleted by the user: its chain must not upload it afterwards. */
    fun cancel(videoId: String) {
        workManager.cancelUniqueWork("track-$videoId")
    }

    /** After a token is saved: downloaded-but-not-uploaded tracks get (re)queued. */
    suspend fun retryPendingUploads() {
        tracks.pendingUploadIds().mapNotNull(VideoId::parse).forEach(::enqueue)
    }

    val rows: Flow<List<DownloadRow>> =
        combine(workManager.getWorkInfosFlow(WorkQuery.fromTags(TAG)), tracks.observeAll()) { infos, all ->
            downloadRows(infos.mapNotNull(::toJob), all)
        }

    private inline fun <reified W : ListenableWorker> trackRequest(videoId: VideoId, stage: Stage): OneTimeWorkRequest =
        request<W>(workDataOf(KEY_VIDEO_ID to videoId.value), VIDEO_TAG + videoId, stage)

    private inline fun <reified W : ListenableWorker> request(input: Data, subjectTag: String, stage: Stage): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<W>()
            .setInputData(input)
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .keepResultsForAtLeast(7, TimeUnit.DAYS)
            .addTag(TAG)
            .addTag(subjectTag)
            .addTag(stage.tag)
            .apply { if (stage == Stage.DOWNLOAD) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
            .build()

    private fun toJob(info: WorkInfo): Job? {
        val subject = info.tags.firstNotNullOfOrNull(::subjectOf)
        val stage = Stage.entries.firstOrNull { it.tag in info.tags }
        if (subject == null || stage == null) return null
        return Job(
            subject = subject,
            stage = stage,
            state = info.state,
            percent = info.progress.getInt(KEY_PERCENT, -1).takeIf { it >= 0 },
            title = info.progress.getString(KEY_TITLE),
            error = info.outputData.pipelineError(),
        )
    }

    private fun subjectOf(tag: String): JobSubject? = when {
        tag.startsWith(VIDEO_TAG) -> VideoId.parse(tag.removePrefix(VIDEO_TAG))?.let(JobSubject::Track)
        tag.startsWith(LIST_TAG) -> PlaylistId.parse(tag.removePrefix(LIST_TAG))?.let(JobSubject::Playlist)
        else -> null
    }

    companion object {
        const val KEY_VIDEO_ID = "videoId"
        const val KEY_LIST_ID = "listId"
        const val KEY_PLAYLIST_ID = "playlistId"
        const val KEY_PERCENT = "percent"
        const val KEY_TITLE = "title"
        private const val TAG = "track"
        private const val VIDEO_TAG = "video:"
        private const val LIST_TAG = "list:"
    }
}

/** WorkInfo projected to what the screen needs: WorkInfo itself stays in this file. */
internal data class Job(
    val subject: JobSubject,
    val stage: Stage,
    val state: WorkInfo.State,
    val percent: Int?,
    val title: String?,
    val error: PipelineError?,
)

internal enum class Stage(val tag: String) { IMPORT("stage:import"), DOWNLOAD("stage:download"), UPLOAD("stage:upload") }

/**
 * Pure (unit-tested): the Downloads screen, merged at read time from the two owners.
 * - Room says UPLOADED: no row, whatever WorkManager still remembers (a terminal fact beats old jobs).
 * - An active job (enqueued, blocked, running) describes the current chain.
 * - Otherwise a failed job shows its error; a PENDING track without any job is "not sent".
 * Old finished chains of the same video (kept 7 days) never override a newer fact.
 */
internal fun downloadRows(jobs: List<Job>, tracks: List<TrackEntity>): List<DownloadRow> {
    val tracksById = tracks.associateBy { it.videoId }
    val pending = tracks.filter { it.uploadState == UploadState.PENDING }.mapNotNull { VideoId.parse(it.videoId)?.let(JobSubject::Track) }
    val subjects = (jobs.map { it.subject } + pending).distinct()
    val rows = subjects.mapNotNull { subject ->
        val track = (subject as? JobSubject.Track)?.let { tracksById[it.videoId.value] }
        if (track?.uploadState == UploadState.UPLOADED) return@mapNotNull null
        val videoJobs = jobs.filter { it.subject == subject }
        val active = videoJobs.filterNot { it.state.isFinished }
        val status = when {
            active.isNotEmpty() -> activeStatus(active)
            else -> videoJobs.filter { it.state == WorkInfo.State.FAILED }
                .maxByOrNull { it.error != null } // the step that failed carries the error, its dependent doesn't
                ?.let { DownloadStatus.Failed(it.error ?: PipelineError.UNKNOWN) }
                ?: DownloadStatus.NotSent.takeIf { track != null }
        } ?: return@mapNotNull null
        val title = track?.title ?: videoJobs.firstNotNullOfOrNull { it.title }
        DownloadRow(subject, title, status)
    }
    return rows.sortedBy { if (it.status is DownloadStatus.Failed || it.status is DownloadStatus.NotSent) 1 else 0 }
}

private fun activeStatus(active: List<Job>): DownloadStatus {
    val download = active.firstOrNull { it.stage == Stage.DOWNLOAD }
    val upload = active.firstOrNull { it.stage == Stage.UPLOAD }
    return when {
        active.any { it.stage == Stage.IMPORT && it.state == WorkInfo.State.RUNNING } -> DownloadStatus.Importing
        download?.state == WorkInfo.State.RUNNING -> DownloadStatus.Downloading(download.percent)
        upload?.state == WorkInfo.State.RUNNING -> DownloadStatus.Uploading
        else -> DownloadStatus.Queued
    }
}
