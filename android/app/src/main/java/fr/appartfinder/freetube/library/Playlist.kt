package fr.appartfinder.freetube.library

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** id: UUID generated on the client (brief §6). sourceYoutubeId is informative only. */
@Entity(tableName = "playlists")
data class PlaylistEntity(@PrimaryKey val id: String, val name: String, val sourceYoutubeId: String?)

/** Replaced in bulk on every write, like the server. videoId may have no local track: hidden at display time. */
@Entity(tableName = "playlist_items", primaryKeys = ["playlistId", "position"])
data class PlaylistItemEntity(val playlistId: String, val position: Int, val videoId: String)

/** A playlist's full state: what a write stores and what an upsert_playlist op carries. */
data class PlaylistState(val playlist: PlaylistEntity, val items: List<String>)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :id ORDER BY position")
    fun observeItems(id: String): Flow<List<PlaylistItemEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: String): PlaylistEntity?

    @Query("SELECT videoId FROM playlist_items WHERE playlistId = :id ORDER BY position")
    suspend fun items(id: String): List<String>

    @Upsert
    suspend fun upsert(playlist: PlaylistEntity)

    @Insert
    suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_items WHERE playlistId = :id")
    suspend fun deleteItems(id: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: String)

    @Transaction
    suspend fun replace(state: PlaylistState) {
        upsert(state.playlist)
        deleteItems(state.playlist.id)
        insertItems(state.items.mapIndexed { position, videoId -> PlaylistItemEntity(state.playlist.id, position, videoId) })
    }

    @Transaction
    suspend fun delete(id: String) {
        deleteItems(id)
        deletePlaylist(id)
    }
}
