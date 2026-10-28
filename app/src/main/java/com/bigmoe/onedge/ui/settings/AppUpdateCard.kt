package com.bigmoe.onedge.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.AppUpdater
import kotlinx.coroutines.launch
import java.io.File

private sealed interface UpdateState {
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: AppUpdater.Release) : UpdateState
    data class Downloading(val release: AppUpdater.Release, val copied: Long, val total: Long) : UpdateState
    data class Downloaded(val release: AppUpdater.Release, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/** "Update" card: checks the fork's latest GitHub release and installs it over this app (same signing key). */
@Composable
fun AppUpdateCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { AppUpdater.installedVersion(context) }
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Checking) }

    fun check() {
        state = UpdateState.Checking
        scope.launch {
            state = try {
                val latest = AppUpdater.fetchLatest()
                if (AppUpdater.isNewer(latest.version, installed)) UpdateState.Available(latest) else UpdateState.UpToDate
            } catch (e: Exception) {
                UpdateState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun install(apk: File) {
        // Android asks once whether this app may install packages; after that the system installer opens directly.
        if (!AppUpdater.canInstall(context)) context.startActivity(AppUpdater.installPermissionIntent(context))
        else context.startActivity(AppUpdater.installIntent(context, apk))
    }

    fun download(release: AppUpdater.Release) {
        state = UpdateState.Downloading(release, 0, release.sizeBytes)
        scope.launch {
            state = try {
                var lastShown = 0L
                val apk = AppUpdater.download(context, release) { copied, total ->
                    if (copied - lastShown >= 256 * 1024 || copied == total) {
                        lastShown = copied
                        state = UpdateState.Downloading(release, copied, total)
                    }
                }
                install(apk)
                UpdateState.Downloaded(release, apk)
            } catch (e: Exception) {
                UpdateState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    LaunchedEffect(Unit) { check() }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "App update", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    val line = when (val s = state) {
                        UpdateState.Checking -> "Installed $installed • checking GitHub…"
                        UpdateState.UpToDate -> "Installed $installed • up to date"
                        is UpdateState.Available -> "Installed $installed • ${s.release.version} is available"
                        is UpdateState.Downloading -> "Downloading ${s.release.version}…"
                        is UpdateState.Downloaded -> "${s.release.version} downloaded • confirm the install"
                        is UpdateState.Failed -> "Installed $installed • ${s.message}"
                    }
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                when (val s = state) {
                    is UpdateState.Available -> Button(onClick = { download(s.release) }) { Text("Update") }
                    is UpdateState.Downloaded -> Button(onClick = { install(s.apk) }) { Text("Install") }
                    UpdateState.UpToDate, is UpdateState.Failed -> OutlinedButton(onClick = { check() }) { Text("Check") }
                    else -> Unit
                }
            }
            val s = state
            if (s is UpdateState.Downloading) {
                if (s.total > 0) {
                    LinearProgressIndicator(progress = { (s.copied.toFloat() / s.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "%.1f / %.1f MB".format(s.copied / 1_048_576.0, s.total / 1_048_576.0),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
