package fr.appartfinder.freetube.sync

import fr.appartfinder.freetube.api.PlaylistDto
import fr.appartfinder.freetube.api.SyncPull
import fr.appartfinder.freetube.api.TrackDto
import fr.appartfinder.freetube.library.AudioFormat
import fr.appartfinder.freetube.library.PlaylistEntity
import fr.appartfinder.freetube.library.PlaylistState
import fr.appartfinder.freetube.library.TrackEntity
import fr.appartfinder.freetube.library.UploadState
import org.junit.Assert.assertEquals
import org.junit.Test

class PullMergeTest {
    private val a = "aaaaaaaaaaa"
    private val b = "bbbbbbbbbbb"
    private val p = "0b9a6f3e-1d2c-4e5f-8a7b-9c0d1e2f3a4b"

    private fun server(id: String, title: String = "server", deletedAt: String? = null) =
        TrackDto(id, title, "artist", 60, "m4a", deletedAt, "2026-10-10T12:00:00Z")

    private fun local(id: String, state: UploadState = UploadState.UPLOADED) =
        TrackEntity(id, "local", "local artist", 60, AudioFormat.M4A, "/audio/$id.m4a", "/thumbs/$id.jpg", state, 1)

    private fun playlist(items: List<String>, deletedAt: String? = null) = PlaylistDto(p, "P", null, items, deletedAt)

    private fun merge(
        tracks: List<TrackDto> = emptyList(),
        playlists: List<PlaylistDto> = emptyList(),
        pendingTracks: Set<String> = emptySet(),
        pendingPlaylists: Set<String> = emptySet(),
        local: List<TrackEntity> = emptyList(),
    ) = mergePull(SyncPull(tracks, playlists, 42), pendingTracks, pendingPlaylists, local.associateBy { it.videoId })

    @Test
    fun entitiesWithPendingOpsAreLeftAlone() {
        val plan = merge(
            tracks = listOf(server(a, deletedAt = "2026-10-10T12:00:00Z"), server(b)),
            playlists = listOf(playlist(listOf(a))),
            pendingTracks = setOf(a, b),
            pendingPlaylists = setOf(p),
            local = listOf(local(a)),
        )
        assertEquals(PullPlan(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), plan)
    }

    @Test
    fun trackTombstoneDeletesRowAndLocalFiles() {
        val plan = merge(tracks = listOf(server(a, deletedAt = "2026-10-10T12:00:00Z")), local = listOf(local(a)))
        assertEquals(listOf(a), plan.deleteTrackIds)
        assertEquals(listOf("/audio/$a.m4a", "/thumbs/$a.jpg"), plan.filesToDelete)
        assertEquals(emptyList<TrackEntity>(), plan.upsertTracks)
    }

    @Test
    fun unknownServerTrackBecomesServerOnlyRow() {
        val row = merge(tracks = listOf(server(a))).upsertTracks.single()
        assertEquals(TrackEntity(a, "server", "artist", 60, AudioFormat.M4A, null, null, UploadState.UPLOADED, 1_791_633_600_000), row)
    }

    @Test
    fun knownTrackKeepsLocalFilesAndPendingSelfHeals() {
        val row = merge(tracks = listOf(server(a, title = "renamed on web")), local = listOf(local(a, UploadState.PENDING))).upsertTracks.single()
        assertEquals(local(a).copy(title = "renamed on web", artist = "artist", uploadState = UploadState.UPLOADED), row)
    }

    @Test
    fun playlistUpsertKeepsItemsWithoutLocalTrack() {
        // b is on no phone: it stays stored (hidden at display time), else the next edit would drop it.
        val plan = merge(playlists = listOf(playlist(listOf(a, b, "bad"))), local = listOf(local(a)))
        assertEquals(listOf(PlaylistState(PlaylistEntity(p, "P", null), listOf(a, b))), plan.upsertPlaylists)
    }

    @Test
    fun playlistTombstoneDeletesIt() {
        val plan = merge(playlists = listOf(playlist(emptyList(), deletedAt = "2026-10-10T12:00:00Z")))
        assertEquals(listOf(p), plan.deletePlaylistIds)
        assertEquals(emptyList<PlaylistState>(), plan.upsertPlaylists)
    }

    @Test
    fun invalidIdsAndUnknownFormatsAreDropped() {
        val plan = merge(
            tracks = listOf(server("short"), server(a).copy(audioExt = "flac")),
            playlists = listOf(playlist(emptyList()).copy(id = "not-a-uuid")),
        )
        assertEquals(PullPlan(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()), plan)
    }
}
