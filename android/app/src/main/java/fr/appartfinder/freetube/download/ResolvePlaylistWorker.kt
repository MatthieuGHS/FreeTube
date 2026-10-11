package fr.appartfinder.freetube.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import fr.appartfinder.freetube.library.LibraryWriter
import fr.appartfinder.freetube.library.MAX_PLAYLIST_ITEMS
import fr.appartfinder.freetube.library.PlaylistEntity
import fr.appartfinder.freetube.library.PlaylistState
import fr.appartfinder.freetube.library.TrackDao
import fr.appartfinder.freetube.library.cleanName
import fr.appartfinder.freetube.youtube.PlaylistId
import java.io.IOException

/**
 * YouTube playlist -> frozen FreeTube copy (brief §8): writes the playlist and its upsert_playlist op,
 * then queues only the tracks not already in the library. Tracks already there are just listed.
 */
@HiltWorker
class ResolvePlaylistWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val ytDlp: YtDlp,
    private val writer: LibraryWriter,
    private val tracks: TrackDao,
    private val pipeline: TrackPipeline,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val listId = PlaylistId.parse(inputData.getString(TrackPipeline.KEY_LIST_ID)) ?: return outcome(PipelineError.UNKNOWN)
        val playlistId = inputData.getString(TrackPipeline.KEY_PLAYLIST_ID) ?: return outcome(PipelineError.UNKNOWN)
        return try {
            val resolved = ytDlp.resolvePlaylist(listId)
            val ids = resolved.videoIds.take(MAX_PLAYLIST_ITEMS)
            val name = cleanName(resolved.title) ?: "Playlist YouTube"
            writer.savePlaylist(PlaylistState(PlaylistEntity(playlistId, name, listId.value), ids.map { it.value }))
            val known = tracks.ids().toSet()
            ids.filterNot { it.value in known }.forEach(pipeline::enqueue)
            Result.success()
        } catch (e: PipelineException) {
            outcome(e.error)
        } catch (e: IOException) {
            outcome(PipelineError.NETWORK)
        }
    }
}
