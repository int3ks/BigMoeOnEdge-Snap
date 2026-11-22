package com.bigmoe.onedge.ui.settings

import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.bigmoe.onedge.core.t
import com.bigmoe.onedge.tools.WikiIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where the wiki folder is, how much it holds, and a button to read it again. */
@Composable
internal fun WikiFolderInfo() {
    val context = LocalContext.current
    val index = remember { WikiIndex.shared(context) }
    var reload by remember { mutableIntStateOf(0) }
    val stats by produceState<WikiIndex.Stats?>(null, reload) {
        value = withContext(Dispatchers.IO) { runCatching { index.stats() }.getOrNull() }
    }
    val s = stats
    Hint(
        if (s == null) t("Reading the wiki folder…")
        else t("Folder: {0}. {1} files, {2} passages.", s.dir, s.files, s.chunks)
    )
    TextButton(onClick = { index.release(); reload++ }) { Text(t("Read the folder again")) }
}
