package com.bigmoe.onedge.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.OnEdgeApplication
import java.text.DateFormat
import java.util.Date

/** Settings → OneDrive sync: client ID of the own Azure registration, device-code sign-in, status, sync now. */
@Composable
fun OneDriveSyncSection() {
    val context = LocalContext.current
    val sync = remember { (context.applicationContext as OnEdgeApplication).container.chatSync }
    val status by sync.status.collectAsState()
    val signedIn by sync.signedIn.collectAsState()

    Section("OneDrive sync")
    Hint("Every chat is a file in the workspace folder chats/ (<title>.json; that file is the chat, the app keeps an index). Signed in, the chats, the files they refer to (attachments, results) and the server models are synced through your OneDrive app folder (Apps\\BigMoe on the PC). The newer copy wins. Sign in once on every device with the same Microsoft account.")
    if (signedIn) {
        Text(
            "Signed in" + sync.auth.account.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty(),
            style = MaterialTheme.typography.bodyMedium
        )
        val last = if (status.lastSyncAt > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.lastSyncAt)) else "never"
        Hint(if (status.running) "Syncing\u2026" else "Last sync: $last. ${status.message}")
        if (status.cloudBytes >= 0) Hint("OneDrive app folder: ${gb(status.cloudBytes)}")
        if (status.pendingUploadBytes > 0) {
            Text(
                "${gb(status.pendingUploadBytes)} of files are waiting to be uploaded (more than 1 GB).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            OutlinedButton(onClick = { sync.confirmLargeUpload() }, enabled = !status.running) { Text("Upload anyway") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { sync.requestSync() }, enabled = !status.running) { Text("Sync now") }
            OutlinedButton(onClick = { sync.signOut() }) { Text("Sign out") }
        }
    } else {
        Button(onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sync.signInUrl())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }) { Text("Sign in to OneDrive") }
        if (status.message.isNotBlank() && status.message != "Not set up") Hint(status.message)
    }
}

private fun gb(bytes: Long): String =
    if (bytes >= 1L shl 30) String.format(java.util.Locale.US, "%.2f GB", bytes / (1L shl 30).toDouble())
    else String.format(java.util.Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
