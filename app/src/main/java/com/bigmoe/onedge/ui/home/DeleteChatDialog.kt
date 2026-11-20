package com.bigmoe.onedge.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.data.repository.ChatSessionDomainModel
import com.bigmoe.onedge.ui.history.HistoryViewModel

/**
 * "Delete this chat?" When the chat has files of its own (attachments, results inside the BigMoe folder that no
 * other chat refers to), they are listed and the user chooses: chat and files, or the chat only.
 */
@Composable
internal fun DeleteChatDialog(target: ChatSessionDomainModel, viewModel: HistoryViewModel, onDone: () -> Unit) {
    var files by remember(target.id) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(target.id) { files = viewModel.filesOf(target.id) }
    val list = files
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Delete this chat?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("\"${target.title}\" and its messages will be removed.")
                when {
                    list == null -> Text("Looking for its files…", style = MaterialTheme.typography.labelSmall)
                    list.isNotEmpty() -> {
                        Text("Files of this chat in the BigMoe folder (${list.size}):", style = MaterialTheme.typography.bodyMedium)
                        Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                            list.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                        }
                        Text(
                            "Deleted files go to the OneDrive recycle bin too (30 days).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!list.isNullOrEmpty()) {
                TextButton(onClick = { viewModel.deleteSession(target.id, list); onDone() }) { Text("Chat and files") }
            }
            TextButton(onClick = { viewModel.deleteSession(target.id); onDone() }, enabled = list != null) {
                Text(if (list.isNullOrEmpty()) "Delete" else "Chat only")
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } }
    )
}
