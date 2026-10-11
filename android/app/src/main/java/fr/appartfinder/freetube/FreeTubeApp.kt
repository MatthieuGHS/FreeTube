package fr.appartfinder.freetube

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import dagger.hilt.android.HiltAndroidApp
import fr.appartfinder.freetube.api.Authed
import okhttp3.OkHttpClient
import fr.appartfinder.freetube.download.DOWNLOADS_CHANNEL
import fr.appartfinder.freetube.download.YtDlp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class FreeTubeApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var ytDlp: YtDlp

    @Inject @Authed
    lateinit var authedHttp: OkHttpClient

    /** Local files load directly; server thumbnails (/thumb) carry the token through the authed client. */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(callFactory = { authedHttp })) }.build()

    /** The manifest removes WorkManager's default initializer: workers get their Hilt dependencies. */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(DOWNLOADS_CHANNEL, "Téléchargements", NotificationManager.IMPORTANCE_LOW),
        )
        // Also runs when WorkManager starts the process: wanted, the 24 h rule makes it cheap.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { ytDlp.updateIfStale() }
    }
}
