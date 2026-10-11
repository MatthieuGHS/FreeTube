package fr.appartfinder.freetube.api

import fr.appartfinder.freetube.settings.ServerConfig
import fr.appartfinder.freetube.settings.Settings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

// DTOs written by hand from server/openapi.json (brief §7); ContractTest fails when they drift.

@Serializable
data class UploadRequest(
    val title: String,
    val artist: String,
    @SerialName("duration_s") val durationS: Int,
    @SerialName("audio_ext") val audioExt: String,
    @SerialName("audio_mime") val audioMime: String,
)

/** Discriminated by "status" (Json { classDiscriminator = "status" }, see AppModule). */
@Serializable
sealed interface UploadResponse {
    @Serializable
    @SerialName("pending_upload")
    data class Pending(
        @SerialName("audio_put_url") val audioPutUrl: String,
        @SerialName("thumb_put_url") val thumbPutUrl: String,
    ) : UploadResponse

    @Serializable
    @SerialName("ready")
    data object Ready : UploadResponse
}

@Serializable
data class Me(val kind: String)

// Outbox op payloads (server *Payload schemas), stored as JSON in OutboxOpEntity.payloadJson.

@Serializable
data class UpsertPlaylistPayload(
    val id: String,
    val name: String,
    @SerialName("source_youtube_id") val sourceYoutubeId: String?,
    val items: List<String>,
)

@Serializable
data class DeletePlaylistPayload(val id: String)

@Serializable
data class UpdateTrackPayload(@SerialName("video_id") val videoId: String, val title: String, val artist: String)

@Serializable
data class DeleteTrackPayload(@SerialName("video_id") val videoId: String)

/** One op on the wire: payload is the stored payloadJson, re-parsed so it is sent as an object, not a string. */
@Serializable
data class OpIn(@SerialName("op_id") val opId: String, val type: String, val payload: JsonElement)

@Serializable
data class OpsIn(val ops: List<OpIn>, val cursor: Long?)

/** 200 = the whole batch is committed. */
@Serializable
data class OpsOut(val acked: Int)

// Pull DTOs: only the fields the phone reads (ignoreUnknownKeys).

@Serializable
data class TrackDto(
    @SerialName("video_id") val videoId: String,
    val title: String,
    val artist: String,
    @SerialName("duration_s") val durationS: Int,
    @SerialName("audio_ext") val audioExt: String,
    @SerialName("deleted_at") val deletedAt: String?,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class PlaylistDto(
    val id: String,
    val name: String,
    @SerialName("source_youtube_id") val sourceYoutubeId: String?,
    val items: List<String>,
    @SerialName("deleted_at") val deletedAt: String?,
)

@Serializable
data class SyncPull(val tracks: List<TrackDto>, val playlists: List<PlaylistDto>, val cursor: Long)

interface FreeTubeApi {
    @POST("api/tracks/{videoId}/upload")
    suspend fun upload(@Path("videoId") videoId: String, @Body body: UploadRequest): UploadResponse

    /** The returned Track is not needed by the phone in M3. 409 = upload_incomplete. */
    @POST("api/tracks/{videoId}/complete")
    suspend fun complete(@Path("videoId") videoId: String)

    @GET("api/auth/me")
    suspend fun me(): Me

    @POST("api/sync/ops")
    suspend fun push(@Body body: OpsIn): OpsOut

    @GET("api/sync")
    suspend fun pull(@Query("since") since: Long): SyncPull
}

/** Marks the OkHttpClient that carries the device token (to the server host only). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Authed

/** Pure: the Bearer goes to the configured server host and nowhere else (S3, i.ytimg.com…). */
internal fun authorize(request: Request, server: ServerConfig?): Request =
    if (server != null && request.url.host == server.baseUrl.host) {
        request.newBuilder().header("Authorization", "Bearer ${server.token.value}").build()
    } else {
        request
    }

/**
 * The one place that adds the token, for Retrofit, streaming and thumbnails alike. An application
 * interceptor runs once per call: on the /stream 302 to S3, OkHttp builds the follow-up itself and drops
 * the Authorization header when the host changes. No HTTP logging interceptor anywhere: it would print it.
 */
class AuthInterceptor @Inject constructor(private val settings: Settings) : Interceptor {
    // shortcut: runBlocking on an OkHttp thread; settings.server() is an in-memory read after the first call.
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(authorize(chain.request(), runBlocking { settings.server() }))
}

/** Retrofit for the CURRENT base URL: a URL change in Settings applies to the next call. */
@Singleton
class ServerClient @Inject constructor(
    private val settings: Settings,
    @Authed private val http: OkHttpClient,
    private val json: Json,
) {
    /** null = no token saved yet. */
    suspend fun api(): FreeTubeApi? {
        val server = settings.server() ?: return null
        return Retrofit.Builder()
            .baseUrl(server.baseUrl)
            .client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create()
    }
}
