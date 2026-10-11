package fr.appartfinder.freetube.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp

/** One or more text fields (playlist name; track title + artist). Confirm needs every field filled. */
@Composable
internal fun TextFieldsDialog(
    title: String,
    labels: List<String>,
    initial: List<String>,
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val values = remember { mutableStateListOf(*initial.toTypedArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                labels.forEachIndexed { i, label ->
                    OutlinedTextField(values[i], { values[i] = it }, label = { Text(label) }, singleLine = true)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(values.toList()); onDismiss() }, enabled = values.all(String::isNotBlank)) { Text("Enregistrer") }
        },
        dismissButton = { CancelButton(onDismiss) },
    )
}

@Composable
internal fun ConfirmDialog(title: String, message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Supprimer") } },
        dismissButton = { CancelButton(onDismiss) },
    )
}

/** Cancel stays neutral so the accent marks only the confirming action. */
@Composable
internal fun CancelButton(onDismiss: () -> Unit) {
    TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text("Annuler") }
}
