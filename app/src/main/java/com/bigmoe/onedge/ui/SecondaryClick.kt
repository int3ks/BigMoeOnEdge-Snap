package com.bigmoe.onedge.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Whether a context menu is open, and where: the last press position inside the element that triggers it. */
class ContextMenuState {
    var expanded by mutableStateOf(false)
    var position by mutableStateOf(Offset.Zero)
    fun open() { expanded = true }
    fun close() { expanded = false }
}

@Composable
fun rememberContextMenuState(): ContextMenuState = remember { ContextMenuState() }

/**
 * Records where the element is pressed, so a long-press menu opens under the finger, and opens [state] right away
 * on a mouse right-click at the pointer. The right-click is consumed in the initial pass so the regular
 * click/long-click handlers below never see it.
 */
fun Modifier.contextMenuTrigger(state: ContextMenuState): Modifier = pointerInput(state) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Press) continue
            val change = event.changes.firstOrNull() ?: continue
            state.position = change.position
            if (event.buttons.isSecondaryPressed) {
                event.changes.forEach { it.consume() }
                state.open()
            }
        }
    }
}

/**
 * The menu of [state], opened at its recorded position. Place it in a Box whose top-left corner is the top-left
 * corner of the element carrying [contextMenuTrigger], so the two coordinate systems agree.
 */
@Composable
fun ContextMenuAt(state: ContextMenuState, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.offset { IntOffset(state.position.x.roundToInt(), state.position.y.roundToInt()) }.size(0.dp)) {
        DropdownMenu(expanded = state.expanded, onDismissRequest = { state.close() }, content = content)
    }
}

/**
 * A text field with a mouse right-click menu (Cut, Copy, Paste, Select all) at the pointer. Compose 1.6 has no
 * such menu on Android: a right-click only moved the cursor and so dropped the selection; with a finger the system
 * toolbar works and is left alone. [field] gets the modifier that catches the right-click and must use [value].
 */
@Composable
fun TextFieldWithMouseMenu(
    value: androidx.compose.ui.text.input.TextFieldValue,
    onValueChange: (androidx.compose.ui.text.input.TextFieldValue) -> Unit,
    modifier: Modifier = Modifier,
    field: @Composable (Modifier) -> Unit
) {
    val menu = rememberContextMenuState()
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Box(modifier) {
        field(Modifier.contextMenuTrigger(menu))
        ContextMenuAt(menu) {
            val sel = value.selection
            val text = value.text
            val selected = if (sel.collapsed) "" else text.substring(sel.min, sel.max)
            fun replaceSelection(with: String) {
                val newText = text.substring(0, sel.min) + with + text.substring(sel.max)
                onValueChange(androidx.compose.ui.text.input.TextFieldValue(newText, androidx.compose.ui.text.TextRange(sel.min + with.length)))
            }
            androidx.compose.material3.DropdownMenuItem(
                text = { androidx.compose.material3.Text("Cut") },
                enabled = selected.isNotEmpty(),
                onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(selected)); replaceSelection(""); menu.close() }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { androidx.compose.material3.Text("Copy") },
                enabled = selected.isNotEmpty(),
                onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(selected)); menu.close() }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { androidx.compose.material3.Text("Paste") },
                enabled = clipboard.hasText(),
                onClick = { clipboard.getText()?.text?.let(::replaceSelection); menu.close() }
            )
            androidx.compose.material3.DropdownMenuItem(
                text = { androidx.compose.material3.Text("Select all") },
                enabled = text.isNotEmpty(),
                onClick = { onValueChange(value.copy(selection = androidx.compose.ui.text.TextRange(0, text.length))); menu.close() }
            )
        }
    }
}
