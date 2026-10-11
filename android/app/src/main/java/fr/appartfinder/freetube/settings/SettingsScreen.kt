package fr.appartfinder.freetube.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.appartfinder.freetube.api.ServerClient
import fr.appartfinder.freetube.download.EngineStatus
import fr.appartfinder.freetube.download.TrackPipeline
import fr.appartfinder.freetube.download.YtDlp
import fr.appartfinder.freetube.sync.SyncDao
import fr.appartfinder.freetube.sync.requestSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

enum class TokenStatus(val label: String?) {
    IDLE(null),
    INVALID_FORMAT("Format de token invalide : rien n'a été enregistré"),
    CHECKING("Vérification…"),
    VALID("Token valide"),
    REJECTED("Token refusé par le serveur"),
    UNREACHABLE("Serveur injoignable : token enregistré quand même"),
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settings: Settings,
    private val serverClient: ServerClient,
    private val ytDlp: YtDlp,
    private val pipeline: TrackPipeline,
    sync: SyncDao,
    private val workManager: WorkManager,
) : ViewModel() {
    val snapshot: StateFlow<SettingsSnapshot?> = settings.snapshot.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val lastSyncAt: StateFlow<Long?> = sync.observeState().map { it?.lastSyncAt }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val engine: StateFlow<EngineStatus> = ytDlp.status

    private val _tokenStatus = MutableStateFlow(TokenStatus.IDLE)
    val tokenStatus = _tokenStatus.asStateFlow()

    private val _urlError = MutableStateFlow<String?>(null)
    val urlError = _urlError.asStateFlow()

    fun saveServerUrl(raw: String) = viewModelScope.launch {
        _urlError.value = if (settings.setServerUrl(raw)) null else "L'URL doit commencer par https://"
    }

    /** Saves, then checks with GET /api/auth/me. A valid token re-queues the tracks waiting for upload and syncs. */
    fun saveToken(raw: String) = viewModelScope.launch {
        if (!settings.setToken(raw)) {
            _tokenStatus.value = TokenStatus.INVALID_FORMAT
            return@launch
        }
        _tokenStatus.value = TokenStatus.CHECKING
        _tokenStatus.value = try {
            serverClient.api()?.me()
            pipeline.retryPendingUploads()
            workManager.requestSync()
            TokenStatus.VALID
        } catch (e: HttpException) {
            if (e.code() == 401) TokenStatus.REJECTED else TokenStatus.UNREACHABLE
        } catch (e: IOException) {
            TokenStatus.UNREACHABLE
        }
    }

    /** Errors are shown through [engine] (lastError). */
    fun updateEngine() = viewModelScope.launch { runCatching { ytDlp.update() } }
}

@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val engine by viewModel.engine.collectAsStateWithLifecycle()
    val tokenStatus by viewModel.tokenStatus.collectAsStateWithLifecycle()
    val urlError by viewModel.urlError.collectAsStateWithLifecycle()
    val lastSyncAt by viewModel.lastSyncAt.collectAsStateWithLifecycle()
    val current = snapshot ?: return
    var url by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(current.serverUrl) { if (url.isEmpty()) url = current.serverUrl }
    var token by remember { mutableStateOf("") } // never pre-filled, never saved across recreation

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Serveur", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(url, { url = it }, label = { Text("URL du serveur") }, isError = urlError != null,
            supportingText = urlError?.let { { Text(it) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { viewModel.saveServerUrl(url) }) { Text("Enregistrer l'URL") }

        Text("Token d'appareil", style = MaterialTheme.typography.titleMedium)
        Text(if (current.hasToken) "Un token est enregistré" else "Aucun token enregistré", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(token, { token = it }, label = { Text("Coller le token") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(onClick = { viewModel.saveToken(token); token = "" }, enabled = token.isNotBlank()) { Text("Enregistrer le token") }
        tokenStatus.label?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        Text("Synchronisation", style = MaterialTheme.typography.titleMedium)
        Text("Dernière synchro : ${lastSyncAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "jamais"}", style = MaterialTheme.typography.bodySmall)

        Text("Moteur de téléchargement", style = MaterialTheme.typography.titleMedium)
        Text("Version : ${engine.version ?: "jamais mis à jour"}", style = MaterialTheme.typography.bodySmall)
        engine.checkedAtMs?.let {
            Text("Dernière vérification : ${DateFormat.getDateTimeInstance().format(Date(it))}", style = MaterialTheme.typography.bodySmall)
        }
        engine.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = viewModel::updateEngine, enabled = !engine.updating) {
            Text(if (engine.updating) "Mise à jour…" else "Mettre à jour le moteur")
        }
    }
}
