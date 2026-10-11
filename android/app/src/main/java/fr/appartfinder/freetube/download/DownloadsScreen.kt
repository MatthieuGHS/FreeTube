package fr.appartfinder.freetube.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.appartfinder.freetube.R
import fr.appartfinder.freetube.ui.EmptyState
import fr.appartfinder.freetube.ui.tabular
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(private val pipeline: TrackPipeline) : ViewModel() {
    val rows: StateFlow<List<DownloadRow>?> =
        pipeline.rows.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** "Relancer" is the same idempotent call as the first request. */
    fun retry(subject: JobSubject) = when (subject) {
        is JobSubject.Track -> pipeline.enqueue(subject.videoId)
        is JobSubject.Playlist -> pipeline.importPlaylist(subject.listId)
    }
}

@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    when {
        rows == null -> Unit
        rows!!.isEmpty() -> EmptyState(R.drawable.ic_download, "Aucun téléchargement en cours.", Modifier.fillMaxSize())
        else -> LazyColumn(Modifier.fillMaxSize()) {
            items(rows!!, key = { it.subject.toString() }) { row -> DownloadRowItem(row) { viewModel.retry(row.subject) } }
        }
    }
}

@Composable
private fun DownloadRowItem(row: DownloadRow, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val label = row.title ?: when (val subject = row.subject) {
            is JobSubject.Track -> subject.videoId.value
            is JobSubject.Playlist -> "Playlist ${subject.listId.value}"
        }
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when (val status = row.status) {
            DownloadStatus.Queued -> StatusText("En attente")
            DownloadStatus.Importing -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                StatusText("Lecture de la playlist…")
            }
            is DownloadStatus.Downloading -> {
                val percent = status.percent
                if (percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                StatusText(percent?.let { "Téléchargement $it %" } ?: "Téléchargement…")
            }
            DownloadStatus.Uploading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                StatusText("Envoi…")
            }
            is DownloadStatus.Failed -> RetryLine(status.error.label, onRetry)
            DownloadStatus.NotSent -> RetryLine("Non envoyé", onRetry)
        }
    }
}

@Composable
private fun RetryLine(message: String, onRetry: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onRetry) {
            Icon(painterResource(R.drawable.ic_refresh_cw), null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Relancer")
        }
    }
}

@Composable
private fun StatusText(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
