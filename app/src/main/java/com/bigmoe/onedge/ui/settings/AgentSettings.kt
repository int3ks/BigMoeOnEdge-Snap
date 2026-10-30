package com.bigmoe.onedge.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.data.local.datastore.AgentApproval
import com.bigmoe.onedge.data.local.datastore.EngineSettings
import com.bigmoe.onedge.termux.TermuxApi

private fun copyText(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Termux command", text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

/**
 * Settings > Tools: how far the agent may go on its own (steps per answer, approvals) and the Termux connection
 * behind run_shell, with a status check, the permission grant, the one-time Termux setup lines and a test run.
 */
@Composable
internal fun AgentAndTermuxSection(settings: EngineSettings, viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val ws by viewModel.workspaceState.collectAsState()
    val testResult by viewModel.termuxTestResult.collectAsState()
    var status by remember { mutableStateOf(viewModel.termuxInstalledAndPermitted()) }
    val (installed, permitted) = status

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        status = viewModel.termuxInstalledAndPermitted()
    }
    LaunchedEffect(Unit) { status = viewModel.termuxInstalledAndPermitted() }

    // ------------------------------------------------------------ agent
    Section("Agent")
    ChoiceSetting(
        "Agent steps per answer",
        listOf(6 to "6 tool rounds", 12 to "12 tool rounds", 20 to "20 tool rounds (default)", 30 to "30 tool rounds", 50 to "50 tool rounds"),
        selected = settings.agentMaxSteps
    ) { viewModel.updateAgentMaxSteps(it) }
    Hint("How many tool rounds the AI may chain in one answer (install, write, run, check, fix, ...). When it hits the limit it stops; say \"continue\" to let it go on.")
    ChoiceSetting(
        "Approval",
        listOf(
            AgentApproval.ASK to "Ask before changes and commands (default)",
            AgentApproval.ALLOW_ALL to "Allow everything without asking"
        ),
        selected = settings.agentApproval
    ) { viewModel.updateAgentApproval(it) }
    if (settings.agentApproval == AgentApproval.ALLOW_ALL) {
        Hint(
            "Edits, deletes and Termux commands now run without a dialog. A Termux command can do anything Termux may do, " +
                "and text the AI reads (web pages, files) can contain instructions it follows. Use this for tasks you watch."
        )
    }
    SwitchRow(
        "Grammar-checked tool calls",
        "On (default): once the AI starts a tool call, the engine only lets it write a valid call to an enabled tool, " +
            "so calls no longer fail on broken JSON. Not applied on turns where the model reasons first (Thinking on, " +
            "or a model that always reasons), and while it is active Guess ahead checks drafts against the grammar too. " +
            "Turn it off if a model behaves oddly with it.",
        settings.toolCallGrammar
    ) { viewModel.updateToolCallGrammar(it) }
    Spacer(Modifier.height(12.dp))

    // ------------------------------------------------------------ Termux
    Section("Termux")
    SwitchRow(
        "Termux commands (run_shell)",
        "The AI runs bash commands in Termux inside the workspace: Python scripts, pip/pkg installs, ffmpeg and so on. " +
            "Needs the separately installed Termux app (F-Droid or GitHub) and the shared workspace folder.",
        settings.toolsTermuxEnabled
    ) { viewModel.updateToolsTermuxEnabled(it) }

    StatusLine("Termux installed", installed)
    StatusLine("Permission to run commands", permitted)
    StatusLine("Workspace in the shared folder", ws.shared)
    if (installed && !permitted) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { permissionLauncher.launch(TermuxApi.PERMISSION_RUN_COMMAND) }) { Text("Grant permission") }
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("App settings") }
        }
        Hint(
            "If no dialog appears: App settings → Permissions → Additional permissions → \"Run commands in Termux environment\". " +
                "If it is missing there, Termux was installed after this app; reinstalling this app's update makes Android offer it."
        )
    }
    if (!ws.shared) Hint("Turn on \"Shared workspace folder\" under Diagnostics & workspace, otherwise Termux cannot see the files.")

    Spacer(Modifier.height(8.dp))
    Text("One-time setup in Termux (tap to copy, paste in Termux):", style = MaterialTheme.typography.bodySmall)
    CommandLine(context, "termux-setup-storage", "lets Termux use shared storage, and so the workspace")
    CommandLine(context, TermuxApi.ALLOW_EXTERNAL_APPS_COMMAND, "allows other apps (this one) to send commands")
    CommandLine(context, "ln -s ~/storage/shared/BigMoe/workspace ~/bigmoe", "optional: the workspace as ~/bigmoe")

    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { viewModel.testTermux() }, enabled = installed && permitted && ws.shared && testResult != "Running…") {
        Text("Test Termux")
    }
    testResult?.let {
        Text(
            it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun StatusLine(label: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            if (ok) "✓" else "✗",
            color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CommandLine(context: Context, command: String, what: String) {
    TextButton(onClick = { copyText(context, command) }, modifier = Modifier.fillMaxWidth()) {
        Text(
            "$command\n— $what",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth()
        )
    }
}
