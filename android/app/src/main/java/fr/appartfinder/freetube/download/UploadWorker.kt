package fr.appartfinder.freetube.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import fr.appartfinder.freetube.api.ServerClient
import fr.appartfinder.freetube.api.UploadRequest
import fr.appartfinder.freetube.api.UploadResponse
import fr.appartfinder.freetube.library.TrackDao
import fr.appartfinder.freetube.library.UploadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.IOException

/**
 * upload -> PUT audio -> PUT thumb -> complete -> markUploaded. Every server step is idempotent, so a
 * retry restarts from `upload` and gets fresh pre-signed URLs.
 */
@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val tracks: TrackDao,
    private val serverClient: ServerClient,
    private val http: OkHttpClient, // base client: the Bearer can never reach S3
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val id = inputData.getString(TrackPipeline.KEY_VIDEO_ID) ?: return outcome(PipelineError.UNKNOWN)
        // No row = nothing to upload (ADR 0003: a track gone from the phone is never uploaded afterwards).
        val track = tracks.get(id) ?: return Result.success()
        if (track.uploadState == UploadState.UPLOADED) return Result.success()
        val api = serverClient.api() ?: return outcome(PipelineError.NOT_CONFIGURED)
        val audio = track.localAudioPath?.let(::File)?.takeIf(File::isFile) ?: return outcome(PipelineError.UNKNOWN)
        val thumb = track.localThumbPath?.let(::File)?.takeIf(File::isFile) ?: return outcome(PipelineError.UNKNOWN)
        return try {
            val request = UploadRequest(track.title, track.artist, track.durationS, track.format.ext, track.format.mime)
            when (val response = api.upload(id, request)) {
                UploadResponse.Ready -> Unit
                is UploadResponse.Pending -> {
                    putPresigned(http, response.audioPutUrl, audio, track.format.mime)
                    putPresigned(http, response.thumbPutUrl, thumb, "image/jpeg")
                    api.complete(id)
                }
            }
            tracks.markUploaded(id)
            Result.success()
        } catch (e: HttpException) {
            outcome(httpError(e.code()))
        } catch (e: PipelineException) {
            outcome(e.error)
        } catch (e: SerializationException) {
            outcome(PipelineError.UNKNOWN) // DTO out of sync with openapi.json: a bug, not worth retrying
        } catch (e: IOException) {
            outcome(PipelineError.NETWORK)
        }
    }
}

/**
 * PUT to a pre-signed S3 URL. Content-Type must equal the signed one exactly; asRequestBody adds no
 * charset. The URL is a credential: it is never logged, and the exception carries only the error kind.
 */
internal suspend fun putPresigned(http: OkHttpClient, url: String, file: File, contentType: String) =
    withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).put(file.asRequestBody(contentType.toMediaType())).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw PipelineException(PipelineError.STORAGE)
        }
    }
