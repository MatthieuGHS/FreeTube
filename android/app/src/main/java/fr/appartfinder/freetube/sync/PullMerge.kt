package fr.appartfinder.freetube.sync

import fr.appartfinder.freetube.api.SyncPull
import fr.appartfinder.freetube.api.TrackDto
import fr.appartfinder.freetube.library.AudioFormat
import fr.appartfinder.freetube.library.PlaylistEntity
import fr.appartfinder.freetube.library.PlaylistState
import fr.appartfinder.freetube.library.TrackEntity
import fr.appartfinder.freetube.library.UploadState
import fr.appartfinder.freetube.youtube.VideoId
import java.time.OffsetDateTime
import java.util.UUID

/** What a pull writes to Room. [filesToDelete] are removed only after the transaction commits. */
data class PullPlan(
    val upsertTracks: List<TrackEntity>,
    val deleteTrackIds: List<String>,
    val filesToDelete: List<String>,
    val upsertPlaylists: List<PlaylistState>,
    val deletePlaylistIds: List<String>,
)

/**
 * Pure (unit-tested). The phone wins: anything with a pending outbox op is left alone, the push will
 * overwrite the server. Ids are re-validated here: an invalid one stored locally would come back in an
 * upsert_playlist, get a 422 and block the outbox. Server tracks are always ready (SyncPull lists no
 * pending upload), so a local PENDING row seen here self-heals to UPLOADED.
 */
internal fun mergePull(
    pull: SyncPull,
    pendingTracks: Set<String>,
    pendingPlaylists: Set<String>,
    local: Map<String, TrackEntity>,
): PullPlan {
    val (goneTracks, liveTracks) = pull.tracks
        .filter { VideoId.parse(it.videoId) != null && it.videoId !in pendingTracks }
        .partition { it.deletedAt != null }
    val (gonePlaylists, livePlaylists) = pull.playlists
        .filter { runCatching { UUID.fromString(it.id) }.isSuccess && it.id !in pendingPlaylists }
        .partition { it.deletedAt != null }
    return PullPlan(
        upsertTracks = liveTracks.mapNotNull { t ->
            local[t.videoId]?.copy(title = t.title, artist = t.artist, uploadState = UploadState.UPLOADED) ?: newTrack(t)
        },
        deleteTrackIds = goneTracks.map { it.videoId },
        filesToDelete = goneTracks.flatMap { local[it.videoId]?.localFiles.orEmpty() },
        upsertPlaylists = livePlaylists.map { p ->
            PlaylistState(PlaylistEntity(p.id, p.name, p.sourceYoutubeId), p.items.filter { VideoId.parse(it) != null })
        },
        deletePlaylistIds = gonePlaylists.map { it.id },
    )
}

/** Server-only track: streamed, no local files. Unknown audio format = not playable, skipped. */
private fun newTrack(t: TrackDto): TrackEntity? {
    val format = AudioFormat.fromExt(t.audioExt) ?: return null
    val addedAt = runCatching { OffsetDateTime.parse(t.createdAt).toInstant().toEpochMilli() }.getOrDefault(0L)
    return TrackEntity(t.videoId, t.title, t.artist, t.durationS, format, null, null, UploadState.UPLOADED, addedAt)
}
