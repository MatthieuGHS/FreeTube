package fr.appartfinder.freetube.api

import fr.appartfinder.freetube.settings.Settings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import retrofit2.create
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import javax.inject.Inject
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

interface FreeTubeApi {
    @POST("api/tracks/{videoId}/upload")
    suspend fun upload(@Path("videoId") videoId: String, @Body body: UploadRequest): UploadResponse

    /** The returned Track is not needed by the phone in M3. 409 = upload_incomplete. */
    @POST("api/tracks/{videoId}/complete")
    suspend fun complete(@Path("videoId") videoId: String)

    @GET("api/auth/me")
    suspend fun me(): Me
}

/**
 * Builds the API for the CURRENT settings on each call: a URL or token change applies to the next
 * request without any global mutable client. The Bearer exists only on this derived client, so it can
 * never reach S3 (presigned PUTs use the base client). No HTTP logging interceptor: it would print it.
 */
@Singleton
class ServerClient @Inject constructor(
    private val settings: Settings,
    private val http: OkHttpClient,
    private val json: Json,
) {
    /** null = no token saved yet. */
    suspend fun api(): FreeTubeApi? {
        val server = settings.server() ?: return null
        val client = http.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("Authorization", "Bearer ${server.token.value}").build())
        }.build()
        return Retrofit.Builder()
            .baseUrl(server.baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create()
    }
}
