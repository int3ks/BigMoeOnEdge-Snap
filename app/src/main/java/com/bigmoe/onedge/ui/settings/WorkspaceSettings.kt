package com.bigmoe.onedge.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Settings > Workspace: where the AI's files and chat attachments live. Off = app-private storage. On = the folder
 * "Internal storage/BigMoe/workspace", which Termux (after termux-setup-storage) and other apps with storage access
 * can read and write, e.g. through a link such as ~/bigmoe -> ~/storage/shared/BigMoe/workspace.
 */
@Composable
internal fun WorkspaceLocationSection(viewModel: SettingsViewModel, onOpenWorkspace: () -> Unit) {
    val ws by viewModel.workspaceState.collectAsState()
    val context = LocalContext.current

    // All-files access is a special grant on its own Settings page, not a runtime prompt. Coming back from it
    // continues the move the switch asked for, or explains why nothing happened.
    val accessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshWorkspace()
        if (viewModel.workspaceState.value.accessGranted) {
            viewModel.setSharedWorkspace(true)
        } else {
            viewModel.showWorkspaceMessage("All-files access was not granted, so the workspace stays in private storage.")
        }
    }

    LaunchedEffect(Unit) { viewModel.refreshWorkspace() }

    Section("Workspace")
    Text(
        if (ws.shared) {
            "Files attached to chats and files created by the AI live in ${ws.path}. Termux and other apps with " +
                "storage access can read and write them."
        } else {
            "Files attached to chats and files created by the AI live in the app's private storage."
        },
        style = MaterialTheme.typography.bodySmall
    )
    Spacer(Modifier.height(4.dp))
    SwitchRow(
        "Shared workspace folder",
        "Moves the workspace to ${ws.sharedPath}, so Termux can work on the same files. Needs all-files access. " +
            "A .nomedia file keeps gallery and music apps out of it. Turning it off moves the files back.",
        ws.shared,
        enabled = ws.supported && !ws.busy
    ) { on ->
        if (on && !ws.accessGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val appPage = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            try {
                accessLauncher.launch(appPage)
            } catch (_: Exception) {
                accessLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            viewModel.setSharedWorkspace(on)
        }
    }
    if (!ws.supported) Hint("The shared folder needs Android 11 or newer.")
    if (ws.shared && !ws.accessGranted) {
        Hint("All-files access is currently off. The app falls back to private storage at its next start.")
    }
    if (ws.busy) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text(ws.progress ?: "Moving files…", style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
    }
    ws.message?.let {
        Spacer(Modifier.height(4.dp))
        Hint(it)
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onOpenWorkspace, enabled = !ws.busy) { Text("Workspace files") }
}
