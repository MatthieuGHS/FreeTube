package fr.appartfinder.freetube.download

import androidx.work.WorkInfo.State
import fr.appartfinder.freetube.library.AudioFormat
import fr.appartfinder.freetube.library.TrackEntity
import fr.appartfinder.freetube.library.UploadState
import fr.appartfinder.freetube.youtube.VideoId
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadRowsTest {
    private val id = VideoId.parse("dQw4w9WgXcQ")!!

    private fun job(stage: Stage, state: State, percent: Int? = null, title: String? = null, error: PipelineError? = null) =
        Job(id, stage, state, percent, title, error)

    private fun track(state: UploadState) =
        TrackEntity(id.value, "Room title", "A", 1, AudioFormat.M4A, "/a", "/t", state, 0)

    private fun status(jobs: List<Job>, tracks: List<TrackEntity> = emptyList()) = downloadRows(jobs, tracks).map { it.status }

    @Test
    fun activeChain() {
        assertEquals(listOf(DownloadStatus.Downloading(40)), status(listOf(job(Stage.DOWNLOAD, State.RUNNING, 40), job(Stage.UPLOAD, State.BLOCKED))))
        assertEquals(listOf(DownloadStatus.Queued), status(listOf(job(Stage.DOWNLOAD, State.ENQUEUED), job(Stage.UPLOAD, State.BLOCKED))))
        assertEquals(listOf(DownloadStatus.Queued), status(listOf(job(Stage.DOWNLOAD, State.SUCCEEDED), job(Stage.UPLOAD, State.ENQUEUED))))
        assertEquals(listOf(DownloadStatus.Uploading), status(listOf(job(Stage.DOWNLOAD, State.SUCCEEDED), job(Stage.UPLOAD, State.RUNNING))))
    }

    @Test
    fun failures() {
        assertEquals(listOf(DownloadStatus.Failed(PipelineError.GEO_BLOCKED)),
            status(listOf(job(Stage.DOWNLOAD, State.FAILED, error = PipelineError.GEO_BLOCKED), job(Stage.UPLOAD, State.FAILED))))
        assertEquals(listOf(DownloadStatus.Failed(PipelineError.UNAUTHORIZED)),
            status(listOf(job(Stage.UPLOAD, State.FAILED, error = PipelineError.UNAUTHORIZED)), listOf(track(UploadState.PENDING))))
    }

    @Test
    fun roomFactsWin() {
        assertEquals(emptyList<DownloadStatus>(), status(listOf(job(Stage.UPLOAD, State.SUCCEEDED))))
        assertEquals(listOf(DownloadStatus.NotSent), status(emptyList(), listOf(track(UploadState.PENDING))))
        assertEquals(emptyList<DownloadStatus>(), status(emptyList(), listOf(track(UploadState.UPLOADED))))
        // An old failed chain must not resurface once the track is uploaded.
        assertEquals(emptyList<DownloadStatus>(),
            status(listOf(job(Stage.UPLOAD, State.FAILED, error = PipelineError.NETWORK)), listOf(track(UploadState.UPLOADED))))
        // A retry in progress hides the old failure.
        assertEquals(listOf(DownloadStatus.Uploading),
            status(listOf(job(Stage.UPLOAD, State.FAILED, error = PipelineError.NETWORK), job(Stage.UPLOAD, State.RUNNING)), listOf(track(UploadState.PENDING))))
    }

    @Test
    fun titleAndOrder() {
        assertEquals("Room title", downloadRows(listOf(job(Stage.UPLOAD, State.RUNNING, title = "p")), listOf(track(UploadState.PENDING))).single().title)
        assertEquals("p", downloadRows(listOf(job(Stage.DOWNLOAD, State.RUNNING, title = "p")), emptyList()).single().title)
        val other = VideoId.parse("aaaaaaaaaaa")!!
        val rows = downloadRows(
            listOf(job(Stage.DOWNLOAD, State.FAILED, error = PipelineError.NETWORK), Job(other, Stage.DOWNLOAD, State.RUNNING, 5, null, null)),
            emptyList(),
        )
        assertEquals(listOf(other, id), rows.map { it.videoId })
    }
}
