package fr.appartfinder.freetube.library

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** The server's audio whitelist (schemas.AudioExt) as a type: the PUT Content-Type comes from here only. */
enum class AudioFormat(val ext: String, val mime: String) {
    M4A("m4a", "audio/mp4"),
    WEBM("webm", "audio/webm"),
    OPUS("opus", "audio/ogg"),
    ;

    companion object {
        fun fromExt(ext: String?): AudioFormat? = entries.firstOrNull { it.ext == ext }
    }
}

/** "In progress" is not here on purpose: it is job state, owned by WorkManager. */
enum class UploadState { PENDING, UPLOADED }

/**
 * A track that exists on this phone. A row is inserted only once the audio AND thumbnail files are final
 * (last step of DownloadWorker), so a row never points at a partial file.
 * M5 adds `deleted` and `serverRev` by AutoMigration.
 */
@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val durationS: Int,
    val format: AudioFormat,
    val localAudioPath: String?, // nullable: M5's pull creates server-only tracks
    val localThumbPath: String?,
    val uploadState: UploadState,
    val addedAt: Long,
)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE videoId = :videoId")
    suspend fun get(videoId: String): TrackEntity?

    @Query("SELECT videoId FROM tracks WHERE uploadState = 'PENDING'")
    suspend fun pendingUploadIds(): List<String>

    @Upsert
    suspend fun upsert(track: TrackEntity)

    @Query("UPDATE tracks SET uploadState = 'UPLOADED' WHERE videoId = :videoId")
    suspend fun markUploaded(videoId: String)
}

/** Never fallbackToDestructiveMigration: from M3 on, this is the phone's real library (D5). */
@Database(entities = [TrackEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
}
