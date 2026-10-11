package fr.appartfinder.freetube.sync

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Track and playlist ids never collide, but an op only ever supersedes ops on the same kind of entity. */
enum class EntityKind { TRACK, PLAYLIST }

/** [wire] = the `type` discriminator of server OpsIn (ContractTest checks the four names). */
enum class OpType(val wire: String, val kind: EntityKind) {
    UPSERT_PLAYLIST("upsert_playlist", EntityKind.PLAYLIST),
    DELETE_PLAYLIST("delete_playlist", EntityKind.PLAYLIST),
    UPDATE_TRACK("update_track", EntityKind.TRACK),
    DELETE_TRACK("delete_track", EntityKind.TRACK),
}

/** A user edit not yet acknowledged by the server. Pushed in insertion (rowid) order. */
@Entity(tableName = "outbox")
data class OutboxOpEntity(
    @PrimaryKey val opId: String,
    val type: OpType,
    val entityId: String,
    val payloadJson: String,
    val createdAt: Long,
)

/** Single row (id 0), written by each successful pull. */
@Entity(tableName = "sync_state")
data class SyncStateEntity(@PrimaryKey val id: Int = 0, val cursor: Long, val lastSyncAt: Long)

/**
 * Pure (unit-tested): the outbox after [op] is added. Every op already states the entity's full
 * intent (full playlist, title + artist, deletion), so the new one supersedes all pending ops on the same
 * entity. It keeps its own new opId: a push in flight acks the opIds it sent, and this op must survive it.
 */
internal fun mergeOutbox(pending: List<OutboxOpEntity>, op: OutboxOpEntity): List<OutboxOpEntity> =
    pending.filterNot { it.type.kind == op.type.kind && it.entityId == op.entityId } + op

@Dao
interface SyncDao {
    @Query("SELECT * FROM outbox ORDER BY rowid")
    suspend fun outbox(): List<OutboxOpEntity>

    @Query("SELECT * FROM outbox ORDER BY rowid LIMIT :limit")
    suspend fun outbox(limit: Int): List<OutboxOpEntity>

    @Insert
    suspend fun insert(op: OutboxOpEntity)

    @Query("DELETE FROM outbox WHERE opId IN (:opIds)")
    suspend fun delete(opIds: List<String>)

    @Query("SELECT * FROM sync_state WHERE id = 0")
    suspend fun state(): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE id = 0")
    fun observeState(): Flow<SyncStateEntity?>

    @Upsert
    suspend fun saveState(state: SyncStateEntity)

    @Transaction
    suspend fun enqueue(op: OutboxOpEntity) {
        val pending = outbox()
        val kept = mergeOutbox(pending, op).map { it.opId }.toSet()
        delete(pending.map { it.opId }.filterNot(kept::contains))
        insert(op)
    }
}
