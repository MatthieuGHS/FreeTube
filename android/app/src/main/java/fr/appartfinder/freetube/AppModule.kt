package fr.appartfinder.freetube

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import fr.appartfinder.freetube.api.AuthInterceptor
import fr.appartfinder.freetube.api.Authed
import fr.appartfinder.freetube.library.AppDatabase
import fr.appartfinder.freetube.library.PlaylistDao
import fr.appartfinder.freetube.library.TrackDao
import fr.appartfinder.freetube.sync.SyncDao
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.time.Duration
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "freetube.db").build()

    @Provides
    fun trackDao(db: AppDatabase): TrackDao = db.tracks()

    @Provides
    fun playlistDao(db: AppDatabase): PlaylistDao = db.playlists()

    @Provides
    fun syncDao(db: AppDatabase): SyncDao = db.sync()

    @Provides
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)

    /** Base client: thumbnails and pre-signed PUTs. ServerClient derives the authenticated one from it. */
    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .readTimeout(Duration.ofSeconds(60))
        .writeTimeout(Duration.ofSeconds(120)) // audio PUTs on a mobile uplink
        .build()

    /** Base client + the token for the server host: Retrofit, /stream and server thumbnails. */
    @Provides
    @Singleton
    @Authed
    fun authedOkHttp(base: OkHttpClient, auth: AuthInterceptor): OkHttpClient =
        base.newBuilder().addInterceptor(auth).build()

    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "status"
    }
}
