package fr.appartfinder.freetube.download

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.appartfinder.freetube.library.AudioFormat
import fr.appartfinder.freetube.youtube.VideoId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

data class EngineStatus(
    val version: String?, // null until the first successful update
    val checkedAtMs: Long?,
    val updating: Boolean = false,
    val lastError: String? = null,
)

/** Metadata validated against the server's UploadIn bounds (title/artist 1..300, duration 0..86400). */
data class TrackMeta(val title: String, val artist: String, val durationS: Int, val format: AudioFormat)

data class DownloadedAudio(val meta: TrackMeta, val file: File)

data class DownloadProgress(val title: String?, val percent: Int?)

private val Context.engineStore by preferencesDataStore("engine")
private val CHECKED_AT = longPreferencesKey("checked_at")

/**
 * The only code that touches youtubedl-android. One Mutex guards init, update and execute: init never runs
 * twice, an update never replaces yt-dlp under a running download, downloads are serialized.
 * Invariant: yt-dlp never runs before it has been updated at least once (M0: the bundled build gets 403).
 */
@Singleton
class YtDlp @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    private var initialized = false // guarded by mutex
    private val audioDir = File(context.filesDir, "audio")
    private val tempDir = File(context.noBackupFilesDir, "ytdlp-tmp") // .part/.ytdl/.temp.* live only here

    private val _status = MutableStateFlow(EngineStatus(YoutubeDL.versionName(context), checkedAtMs = null))
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    /** Best audio stream, no conversion. Returns once the final file is in filesDir/audio. */
    suspend fun download(videoId: VideoId, onProgress: (DownloadProgress) -> Unit): DownloadedAudio =
        mutex.withLock {
            ensureInit()
            if (YoutubeDL.versionName(context) == null) updateLocked()
            try {
                run(videoId, onProgress)
            } catch (e: PipelineException) {
                if (e.error != PipelineError.ENGINE_OUTDATED || checkedWithin(1.hours)) throw e
                updateLocked() // one internal retry after a fresh update
                run(videoId, onProgress)
            }
        }

    /** Settings button: always checks GitHub. Throws [PipelineException] (error also kept in [status]). */
    suspend fun update() = mutex.withLock {
        ensureInit()
        updateLocked()
    }

    /** App launch: at most once per 24 h. Never throws: a failure is shown in [status]. */
    suspend fun updateIfStale() {
        _status.update { it.copy(checkedAtMs = checkedAt()) }
        if (!checkedWithin(24.hours)) runCatching { update() }
    }

    private suspend fun ensureInit() {
        if (initialized) return
        withContext(Dispatchers.IO) {
            YoutubeDL.init(context) // extracts Python on first run: several seconds
            FFmpeg.init(context)
        }
        initialized = true
    }

    private suspend fun updateLocked() {
        _status.update { it.copy(updating = true, lastError = null) }
        try {
            withContext(Dispatchers.IO) { YoutubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE) }
        } catch (e: YoutubeDLException) {
            _status.update { it.copy(updating = false, lastError = "Mise à jour impossible : vérifiez la connexion") }
            throw PipelineException(PipelineError.NETWORK, e)
        }
        val now = System.currentTimeMillis()
        context.engineStore.edit { it[CHECKED_AT] = now }
        _status.update { EngineStatus(YoutubeDL.versionName(context), now) }
    }

    private suspend fun run(videoId: VideoId, onProgress: (DownloadProgress) -> Unit): DownloadedAudio {
        tempDir.mkdirs()
        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
            addOption("-f", "bestaudio[ext=m4a]/bestaudio")
            addOption("--no-playlist")
            // A resumed worker re-downloads instead of trusting a file left by a killed process.
            addOption("--force-overwrites")
            addOption("-P", "home:${audioDir.path}")
            addOption("-P", "temp:${tempDir.path}")
            addOption("-o", "%(id)s.%(ext)s")
            addOption("--print", "before_dl:$TITLE_PREFIX%(title)s")
            addOption("--print", "after_move:$META_PREFIX%(.{title,channel,uploader,duration,ext,filepath})j")
            addOption("--progress") // --print implies --quiet: keep the progress lines
            addOption("--newline")
        }
        val processId = "track-$videoId"
        var title: String? = null
        var lastPercent = -1
        val response = coroutineScope {
            // Cancellation (WorkManager stop) must kill the yt-dlp process, not just abandon it.
            val killer = launch { try { awaitCancellation() } finally { YoutubeDL.destroyProcessById(processId) } }
            try {
                runInterruptible(Dispatchers.IO) {
                    YoutubeDL.execute(request, processId) { progress, _, line ->
                        if (line.startsWith(TITLE_PREFIX)) title = line.removePrefix(TITLE_PREFIX)
                        val percent = progress.toInt().coerceIn(0, 100)
                        if (percent != lastPercent) { // each report is a WorkManager DB write
                            lastPercent = percent
                            onProgress(DownloadProgress(title, percent))
                        }
                    }
                }
            } catch (e: YoutubeDLException) {
                throw PipelineException(classifyYtDlpError(e.message.orEmpty()), e)
            } finally {
                killer.cancel()
            }
        }
        tempDir.listFiles { f -> f.name.startsWith(videoId.value) }?.forEach(File::delete)
        return parseDownloadOutput(response.out, audioDir) ?: throw PipelineException(PipelineError.UNSUPPORTED)
    }

    private suspend fun checkedAt(): Long? = context.engineStore.data.map { it[CHECKED_AT] }.first()

    private suspend fun checkedWithin(d: Duration): Boolean =
        checkedAt()?.let { (System.currentTimeMillis() - it).milliseconds < d } ?: false
}

internal const val TITLE_PREFIX = "FT_TITLE "
internal const val META_PREFIX = "FT_META "

private val json = Json { ignoreUnknownKeys = true }

/**
 * Pure (unit-tested). Reads the META_PREFIX line that yt-dlp prints after moving the final file, and
 * validates it: the path must be an existing direct child of [audioDir] (never a .part), the extension
 * must be on the server whitelist, title/artist/duration within the server bounds. Null = unusable.
 */
internal fun parseDownloadOutput(stdout: String, audioDir: File): DownloadedAudio? {
    val meta = stdout.lineSequence().lastOrNull { it.startsWith(META_PREFIX) } ?: return null
    val obj = runCatching { json.parseToJsonElement(meta.removePrefix(META_PREFIX)).jsonObject }.getOrNull() ?: return null
    fun text(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()

    val file = text("filepath")?.let(::File) ?: return null
    if (file.parentFile?.canonicalPath != audioDir.canonicalPath || !file.isFile) return null
    val format = AudioFormat.fromExt(text("ext")) ?: return null
    val duration = obj.durationSeconds() ?: return null
    val title = text("title").orEmpty().ifBlank { "Sans titre" }.take(300)
    val artist = (text("channel")?.takeIf(String::isNotBlank) ?: text("uploader")).orEmpty()
        .removeSuffix(" - Topic").trim().ifBlank { "Artiste inconnu" }.take(300)
    return DownloadedAudio(TrackMeta(title, artist, duration, format), file)
}

private fun JsonObject.durationSeconds(): Int? =
    (get("duration") as? JsonPrimitive)?.doubleOrNull
        ?.roundToInt()?.takeIf { it in 0..86_400 }
