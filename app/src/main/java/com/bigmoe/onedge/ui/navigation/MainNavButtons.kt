package com.bigmoe.onedge.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector

/** The main pages of the app. Every one of them shows [MainNavButtons] in its top bar, so each page is one tap away. */
enum class TopTab(val label: String, val icon: ImageVector) {
    CHATS("Chats", Icons.Default.ChatBubble),
    MODELS("Models", Icons.Default.Memory),
    SETTINGS("Settings", Icons.Default.Settings),
    LOG("Log", Icons.Default.Terminal)
}

/** What the buttons do; provided once by AppNavigation so the screens do not need a callback each. */
class MainNav(val open: (TopTab) -> Unit)

val LocalMainNav = compositionLocalOf { MainNav { } }

/**
 * The four page buttons (Chats, Models, Settings, Log) for a top bar's `actions`. The button of [current] is tinted.
 * [modelsButton] replaces the plain Models button, which the chat uses for its quick model picker.
 */
@Composable
fun MainNavButtons(current: TopTab, modelsButton: (@Composable () -> Unit)? = null) {
    val nav = LocalMainNav.current
    TopTab.values().forEach { tab ->
        if (tab == TopTab.MODELS && modelsButton != null) {
            modelsButton()
        } else {
            IconButton(onClick = { nav.open(tab) }) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = tab.label,
                    tint = if (tab == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
