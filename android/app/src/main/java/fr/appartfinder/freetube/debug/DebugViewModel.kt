package fr.appartfinder.freetube.debug

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

// M0 spike: hardcoded track (> 10 min) to test screen-off playback.
const val TEST_VIDEO_ID = "cWGE9Gi0bB0"

data class DebugState(
    val status: String = "Prêt",
    val busy: Boolean = false,
    val progress: Float? = null,
    val file: File? = null,
    val engineVersion: String? = null,
)

@HiltViewModel
class DebugViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val audioDir = File(context.filesDir, "audio")
    private val _state = MutableStateFlow(DebugState(file = downloadedFile()))
    val state = _state.asStateFlow()

    // Extracts Python + ffmpeg on first run (several seconds): done once, off the main thread.
    private val engine by lazy {
        YoutubeDL.init(context)
        FFmpeg.init(context)
        YoutubeDL
    }

    fun download() = runBusy("Téléchargement…") {
        val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$TEST_VIDEO_ID").apply {
            addOption("-f", "bestaudio[ext=m4a]/bestaudio")
            addOption("--no-playlist")
            addOption("-o", "${audioDir.absolutePath}/%(id)s.%(ext)s")
        }
        engine.execute(request) { progress, _, _ ->
            _state.update { it.copy(progress = progress / 100f) }
        }
        val file = checkNotNull(downloadedFile()) { "yt-dlp a terminé sans produire de fichier" }
        _state.update { it.copy(file = file) }
        "Téléchargé : ${file.name} (${file.length() / 1024} Kio)"
    }

    fun updateEngine() = runBusy("Mise à jour du moteur…") {
        val status = engine.updateYoutubeDL(context)
        "Mise à jour : $status"
    }

    private fun downloadedFile() =
        // Exactly "<id>.<ext>": excludes yt-dlp leftovers such as "<id>.m4a.part" or "<id>.temp.m4a".
        audioDir.listFiles { f -> f.nameWithoutExtension == TEST_VIDEO_ID }?.firstOrNull()

    private fun runBusy(label: String, block: suspend () -> String) {
        if (_state.value.busy) return
        _state.update { it.copy(status = label, busy = true, progress = null) }
        viewModelScope.launch(Dispatchers.IO) {
            val status = runCatching { block() }.getOrElse { "Erreur : ${it.message}" }
            _state.update {
                it.copy(status = status, busy = false, progress = null, engineVersion = YoutubeDL.versionName(context))
            }
        }
    }
}
