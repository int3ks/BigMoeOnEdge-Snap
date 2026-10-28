package com.bigmoe.onedge.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Opens a context menu on a mouse right-click right away, the way a long-press does with a finger. The press is
 * consumed in the initial pass so the regular click/long-click handlers below never see it.
 */
fun Modifier.onSecondaryClick(onClick: () -> Unit): Modifier = composed {
    val action by rememberUpdatedState(onClick)
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                    event.changes.forEach { it.consume() }
                    action()
                }
            }
        }
    }
}
