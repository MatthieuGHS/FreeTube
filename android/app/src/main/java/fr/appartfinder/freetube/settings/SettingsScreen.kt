package fr.appartfinder.freetube.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.appartfinder.freetube.R
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
    private val sync: SyncDao,
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

    fun resyncAll() = viewModelScope.launch {
        sync.resetCursor()
        workManager.requestSync()
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
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Section("Serveur") {
            OutlinedTextField(url, { url = it }, label = { Text("URL du serveur") }, isError = urlError != null,
                supportingText = urlError?.let { { Text(it) } }, singleLine = true, modifier = Modifier.fillMaxWidth())
            FilledTonalButton(onClick = { viewModel.saveServerUrl(url) }, shape = MaterialTheme.shapes.small) { Text("Enregistrer l'URL") }
        }

        Section("Token d'appareil") {
            Helper(if (current.hasToken) "Un token est enregistré" else "Aucun token enregistré")
            OutlinedTextField(token, { token = it }, label = { Text("Coller le token") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            FilledTonalButton(onClick = { viewModel.saveToken(token); token = "" }, enabled = token.isNotBlank(), shape = MaterialTheme.shapes.small) {
                Text("Enregistrer le token")
            }
            tokenStatus.label?.let { Helper(it) }
        }

        Section("Synchronisation") {
            Helper("Dernière synchro : ${lastSyncAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "jamais"}")
            // After a server restore (docs/deploy.md): the next pull re-reads everything, pending edits still win.
            OutlinedButton(onClick = viewModel::resyncAll, shape = MaterialTheme.shapes.small) { ButtonIcon(); Text("Tout resynchroniser") }
        }

        Section("Moteur de téléchargement") {
            Helper("Version : ${engine.version ?: "jamais mis à jour"}")
            engine.checkedAtMs?.let { Helper("Dernière vérification : ${DateFormat.getDateTimeInstance().format(Date(it))}") }
            engine.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = viewModel::updateEngine, enabled = !engine.updating, shape = MaterialTheme.shapes.small) {
                ButtonIcon()
                Text(if (engine.updating) "Mise à jour…" else "Mettre à jour le moteur")
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun Helper(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun ButtonIcon() {
    Icon(painterResource(R.drawable.ic_refresh_cw), contentDescription = null, modifier = Modifier.size(18.dp))
    Spacer(Modifier.width(8.dp))
}
