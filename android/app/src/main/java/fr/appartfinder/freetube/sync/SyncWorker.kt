package fr.appartfinder.freetube.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import fr.appartfinder.freetube.api.FreeTubeApi
import fr.appartfinder.freetube.api.OpIn
import fr.appartfinder.freetube.api.OpsIn
import fr.appartfinder.freetube.api.ServerClient
import fr.appartfinder.freetube.download.MAX_ATTEMPTS
import fr.appartfinder.freetube.library.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val MAX_OPS_PER_PUSH = 500 // server OpsIn maxItems

/**
 * The periodic and the one-time work can run at the same time: two pulls committing out of order would
 * write older server data over newer. One process-wide lock makes syncs strictly sequential.
 */
private val syncLock = Mutex()

/** Push the outbox, then pull. No token configured: nothing to do, success. */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val db: AppDatabase,
    private val serverClient: ServerClient,
    private val json: Json,
) : CoroutineWorker(context, params) {
    private val sync = db.sync()

    override suspend fun doWork(): Result {
        val api = serverClient.api() ?: return Result.success()
        return try {
            syncLock.withLock {
                push(api)
                pull(api)
            }
            Result.success()
        } catch (e: HttpException) {
            if (e.code() == 429 || e.code() >= 500) retryOrFail() else Result.failure()
        } catch (e: SerializationException) {
            Result.failure() // DTO out of sync with openapi.json: a bug, not worth retrying
        } catch (e: IOException) {
            retryOrFail()
        }
    }

    private fun retryOrFail() = if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()

    /** On 200, deletes exactly the opIds sent: an op superseded meanwhile has a new opId and survives. */
    private suspend fun push(api: FreeTubeApi) {
        while (true) {
            val ops = sync.outbox(MAX_OPS_PER_PUSH).takeIf { it.isNotEmpty() } ?: return
            val wire = ops.map { OpIn(it.opId, it.type.wire, json.parseToJsonElement(it.payloadJson)) }
            api.push(OpsIn(wire, sync.state()?.cursor))
            sync.delete(ops.map { it.opId })
        }
    }

    /** The pending set is read in the transaction that applies the pull: an edit can't slip in between. */
    private suspend fun pull(api: FreeTubeApi) {
        val changes = api.pull(since = sync.state()?.cursor ?: 0)
        val tracks = db.tracks()
        val playlists = db.playlists()
        val files = db.withTransaction {
            val pending = sync.outbox()
            val plan = mergePull(
                changes,
                pendingTracks = pending.idsOf(EntityKind.TRACK),
                pendingPlaylists = pending.idsOf(EntityKind.PLAYLIST),
                local = tracks.all().associateBy { it.videoId },
            )
            tracks.upsertAll(plan.upsertTracks)
            plan.deleteTrackIds.forEach { tracks.delete(it) }
            plan.upsertPlaylists.forEach { playlists.replace(it) }
            plan.deletePlaylistIds.forEach { playlists.delete(it) }
            sync.saveState(SyncStateEntity(cursor = changes.cursor, lastSyncAt = System.currentTimeMillis()))
            plan.filesToDelete
        }
        withContext(Dispatchers.IO) { files.forEach { File(it).delete() } }
    }

    private fun List<OutboxOpEntity>.idsOf(kind: EntityKind) = filter { it.type.kind == kind }.map { it.entityId }.toSet()
}

private val networkConstraint = Constraints(requiredNetworkType = NetworkType.CONNECTED)

/** App launch, each edit, each finished upload. APPEND_OR_REPLACE: KEEP would drop a trigger fired during a running sync. */
fun WorkManager.requestSync() {
    enqueueUniqueWork(
        "sync",
        ExistingWorkPolicy.APPEND_OR_REPLACE,
        OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(networkConstraint)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build(),
    )
}

fun WorkManager.schedulePeriodicSync() {
    enqueueUniquePeriodicWork(
        "sync-periodic",
        ExistingPeriodicWorkPolicy.KEEP,
        PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(networkConstraint).build(),
    )
}
