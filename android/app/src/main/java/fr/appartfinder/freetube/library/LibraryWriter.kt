package fr.appartfinder.freetube.library

import androidx.room.withTransaction
import fr.appartfinder.freetube.api.DeletePlaylistPayload
import fr.appartfinder.freetube.api.DeleteTrackPayload
import fr.appartfinder.freetube.api.UpdateTrackPayload
import fr.appartfinder.freetube.api.UpsertPlaylistPayload
import fr.appartfinder.freetube.download.TrackPipeline
import fr.appartfinder.freetube.sync.OpType
import fr.appartfinder.freetube.sync.OutboxOpEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Server bounds (PlaylistIn, TrackPatch): an op outside them would get a 422 and block the outbox. */
private const val MAX_NAME = 200
private const val MAX_TEXT = 300
const val MAX_PLAYLIST_ITEMS = 5000

/**
 * The only writer of user edits. Each edit writes Room AND enqueues its outbox op in ONE transaction,
 * so the server learns about exactly what the phone shows. Playlist edits always rewrite the FULL stored
 * item list (hidden items included): upsert_playlist is full state, a partial list would drop items.
 */
@Singleton
class LibraryWriter @Inject constructor(
    private val db: AppDatabase,
    private val json: Json,
    private val pipeline: TrackPipeline,
) {
    private val tracks = db.tracks()
    private val playlists = db.playlists()
    private val sync = db.sync()

    /** Returns false (nothing written) when the name is blank. */
    suspend fun createPlaylist(name: String): Boolean {
        val clean = cleanName(name) ?: return false
        savePlaylist(PlaylistState(PlaylistEntity(UUID.randomUUID().toString(), clean, null), emptyList()))
        return true
    }

    /** Full state, created or replaced (playlist import). */
    suspend fun savePlaylist(state: PlaylistState) {
        db.withTransaction { writePlaylist(state) }
    }

    suspend fun renamePlaylist(id: String, name: String) {
        val clean = cleanName(name) ?: return
        editPlaylist(id) { it.copy(playlist = it.playlist.copy(name = clean)) }
    }

    /** Already in the playlist, or full: no-op. */
    suspend fun addToPlaylist(id: String, videoId: String) = editPlaylist(id) {
        if (videoId in it.items || it.items.size >= MAX_PLAYLIST_ITEMS) null else it.copy(items = it.items + videoId)
    }

    /** Positions are stored positions: the screen passes the neighbouring VISIBLE item, hidden ones stay put. */
    suspend fun swapItems(id: String, a: Int, b: Int) = editPlaylist(id) {
        if (a !in it.items.indices || b !in it.items.indices) return@editPlaylist null
        val items = it.items.toMutableList()
        items[a] = items[b].also { items[b] = items[a] }
        it.copy(items = items)
    }

    suspend fun removeItem(id: String, position: Int) = editPlaylist(id) {
        if (position !in it.items.indices) null else it.copy(items = it.items.filterIndexed { i, _ -> i != position })
    }

    suspend fun deletePlaylist(id: String) {
        db.withTransaction {
            playlists.delete(id)
            enqueue(OpType.DELETE_PLAYLIST, id, json.encodeToString(DeletePlaylistPayload.serializer(), DeletePlaylistPayload(id)))
        }
    }

    suspend fun renameTrack(videoId: String, title: String, artist: String) {
        val cleanTitle = title.trim().take(MAX_TEXT).ifBlank { return }
        val cleanArtist = artist.trim().take(MAX_TEXT).ifBlank { return }
        db.withTransaction {
            if (tracks.get(videoId) == null) return@withTransaction
            tracks.rename(videoId, cleanTitle, cleanArtist)
            val payload = UpdateTrackPayload(videoId, cleanTitle, cleanArtist)
            enqueue(OpType.UPDATE_TRACK, videoId, json.encodeToString(UpdateTrackPayload.serializer(), payload))
        }
    }

    /** Row and op first (one transaction), then the files: a crash in between leaves orphan files, never a row without them. */
    suspend fun deleteTrack(videoId: String) {
        pipeline.cancel(videoId) // a running upload would otherwise make the track ready again on the server
        val track = db.withTransaction {
            tracks.get(videoId)?.also {
                tracks.delete(videoId)
                enqueue(OpType.DELETE_TRACK, videoId, json.encodeToString(DeleteTrackPayload.serializer(), DeleteTrackPayload(videoId)))
            }
        } ?: return
        withContext(Dispatchers.IO) { track.localFiles.forEach { File(it).delete() } }
    }

    /** [edit] gets the current full state inside the transaction; null = nothing to change. */
    private suspend fun editPlaylist(id: String, edit: (PlaylistState) -> PlaylistState?) {
        db.withTransaction {
            val playlist = playlists.get(id) ?: return@withTransaction
            edit(PlaylistState(playlist, playlists.items(id)))?.let { writePlaylist(it) }
        }
    }

    private suspend fun writePlaylist(state: PlaylistState) {
        playlists.replace(state)
        val p = state.playlist
        val payload = UpsertPlaylistPayload(p.id, p.name, p.sourceYoutubeId, state.items)
        enqueue(OpType.UPSERT_PLAYLIST, p.id, json.encodeToString(UpsertPlaylistPayload.serializer(), payload))
    }

    private suspend fun enqueue(type: OpType, entityId: String, payloadJson: String) =
        sync.enqueue(OutboxOpEntity(UUID.randomUUID().toString(), type, entityId, payloadJson, System.currentTimeMillis()))
}

internal fun cleanName(raw: String): String? = raw.trim().take(MAX_NAME).ifBlank { null }
