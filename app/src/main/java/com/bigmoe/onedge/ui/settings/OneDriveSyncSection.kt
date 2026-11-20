package com.bigmoe.onedge.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.OnEdgeApplication
import com.bigmoe.onedge.sync.OneDriveAuth
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Settings → OneDrive sync: client ID of the own Azure registration, device-code sign-in, status, sync now. */
@Composable
fun OneDriveSyncSection() {
    val context = LocalContext.current
    val sync = remember { (context.applicationContext as OnEdgeApplication).container.chatSync }
    val status by sync.status.collectAsState()
    val scope = rememberCoroutineScope()
    var clientId by remember { mutableStateOf(sync.auth.clientId) }
    var code by remember { mutableStateOf<OneDriveAuth.DeviceCode?>(null) }
    var info by remember { mutableStateOf("") }
    var signedIn by remember { mutableStateOf(sync.auth.signedIn) }

    Section("OneDrive sync")
    Hint("Chats and server models are synced through your OneDrive app folder (Apps\\<registration name> on the PC, one JSON file per chat). The newer copy wins. Needs an own Azure app registration: personal Microsoft accounts, \"Allow public client flows\" = Yes, permission Files.ReadWrite.AppFolder.")
    OutlinedTextField(
        value = clientId,
        onValueChange = { clientId = it.trim() },
        label = { Text("Application (client) ID") },
        singleLine = true,
        enabled = !signedIn,
        modifier = Modifier.fillMaxWidth()
    )
    if (signedIn) {
        Text(
            "Signed in" + sync.auth.account.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty(),
            style = MaterialTheme.typography.bodyMedium
        )
        val last = if (status.lastSyncAt > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.lastSyncAt)) else "never"
        Hint(if (status.running) "Syncing…" else "Last sync: $last. ${status.message}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { sync.requestSync() }, enabled = !status.running) { Text("Sync now") }
            OutlinedButton(onClick = { sync.signOut(); signedIn = false; code = null }) { Text("Sign out") }
        }
    } else {
        val pending = code
        if (pending == null) {
            Button(
                enabled = clientId.length >= 30,
                onClick = {
                    info = ""
                    scope.launch {
                        try {
                            val c = sync.startSignIn(clientId)
                            code = c
                            copy(context, c.userCode)
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.verificationUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            val ok = sync.finishSignIn(c)
                            signedIn = ok
                            info = if (ok) "" else "The code expired. Try again."
                        } catch (t: Throwable) {
                            info = "Sign-in failed: ${t.message ?: t.javaClass.simpleName}"
                        } finally {
                            code = null
                        }
                    }
                }
            ) { Text("Sign in to OneDrive") }
        } else {
            Text("Enter this code on the Microsoft page (it is in the clipboard):", style = MaterialTheme.typography.bodyMedium)
            SelectionContainer { Text(pending.userCode, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary) }
            Hint(pending.verificationUri + " — waiting for the sign-in…")
        }
        if (info.isNotBlank()) Hint(info)
    }
}

private fun copy(context: Context, text: String) {
    runCatching {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", text))
    }
}
