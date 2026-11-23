package com.bigmoe.onedge.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.vector.ImageVector

/** The main pages of the app. Every one of them shows [MainNavButtons] in its top bar, so each page is one tap away. */
enum class TopTab(val label: String, val icon: ImageVector) {
    CHATS("Chats", Icons.Default.ChatBubble),
    MODELS("Models", Icons.Default.Memory),
    SETTINGS("Settings", Icons.Default.Settings),
    HUB("Hugging Face", Icons.Default.CloudDownload),
    LOG("Log", Icons.Default.Terminal)
}

/** What the buttons do; provided once by AppNavigation so the screens do not need a callback each. */
class MainNav(val open: (TopTab) -> Unit)

val LocalMainNav = compositionLocalOf { MainNav { } }

/** A page's own entry at the top of the ⋮ menu (the chat's "Forget saved KV"). [label] is shown as given (translated). */
class NavMenuItem(val label: String, val icon: ImageVector, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * The four page buttons (Chats, Models, Settings, Log) for a top bar's `actions`. The button of [current] is tinted.
 * [modelsButton] replaces the plain Models button, which the chat uses for its quick model picker. [menuItems] go into
 * the ⋮ menu above the pages folded there; with any, the menu shows on wide screens too.
 */
@Composable
fun MainNavButtons(current: TopTab, modelsButton: (@Composable () -> Unit)? = null, menuItems: List<NavMenuItem> = emptyList()) {
    val nav = LocalMainNav.current
    // Narrow screens (a phone upright): Settings, Hugging Face and Log go into a ⋮ menu, so the title keeps its room.
    val compact = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp < 600
    val inMenu = if (compact) setOf(TopTab.SETTINGS, TopTab.HUB, TopTab.LOG) else emptySet()
    TopTab.values().filter { it !in inMenu }.forEach { tab ->
        if (tab == TopTab.MODELS && modelsButton != null) {
            modelsButton()
        } else {
            IconButton(onClick = { nav.open(tab) }) {
                Icon(
                    imageVector = tab.icon,
                    contentDescription = com.bigmoe.onedge.core.t(tab.label),
                    tint = if (tab == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (inMenu.isNotEmpty() || menuItems.isNotEmpty()) {
        var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.foundation.layout.Box {
            IconButton(onClick = { open = true }) {
                Icon(
                    androidx.compose.material.icons.Icons.Default.MoreVert,
                    contentDescription = "More",
                    tint = if (current in inMenu) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                menuItems.forEach { item ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { androidx.compose.material3.Text(item.label) },
                        leadingIcon = { Icon(item.icon, contentDescription = null) },
                        enabled = item.enabled,
                        onClick = { open = false; item.onClick() }
                    )
                }
                if (menuItems.isNotEmpty() && inMenu.isNotEmpty()) androidx.compose.material3.HorizontalDivider()
                TopTab.values().filter { it in inMenu }.forEach { tab ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = { androidx.compose.material3.Text(com.bigmoe.onedge.core.t(tab.label)) },
                        leadingIcon = {
                            Icon(
                                tab.icon, contentDescription = null,
                                tint = if (tab == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = { open = false; nav.open(tab) }
                    )
                }
            }
        }
    }
}
