package com.bigmoe.onedge.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.bigmoe.onedge.core.t
import com.bigmoe.onedge.tools.WikiIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The wiki folder: access, what it holds, import (.md/.txt/.zip) and reading it again. */
@Composable
internal fun WikiFolderInfo() {
    val context = LocalContext.current
    val index = remember { WikiIndex.shared(context) }
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val stats by produceState<WikiIndex.Stats?>(null, reload) {
        value = withContext(Dispatchers.IO) { runCatching { index.stats() }.getOrNull() }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { index.import(context.contentResolver, uris) }
            result = t("Imported {0} files, skipped {1}.", r.files, r.skipped) +
                (if (r.errors.isNotEmpty()) " " + t("Failed: {0}", r.errors.joinToString("; ")) else "")
            importing = false
            reload++
        }
    }
    val s = stats
    when {
        s == null -> Hint(t("Reading the wiki folder…"))
        !s.access -> {
            Hint(t("The wiki lives in {0} and needs access to all files. Allow it, then tap Read the folder again.", s.dir))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text(t("Allow access to all files")) }
            }
        }
        else -> Hint(
            t("Folder: {0}. {1} files, {2} passages.", s.dir, s.files, s.chunks) + " " +
                t("Import takes .md and .txt files; a .zip (a Notion export) goes into a subfolder named after it, emptied first, with only its text files.")
        )
    }
    result?.let { Hint(it) }
    Row {
        TextButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = s?.access == true && !importing) {
            Text(if (importing) t("Importing…") else t("Import files"))
        }
        TextButton(onClick = { index.release(); reload++ }) { Text(t("Read the folder again")) }
    }
}
