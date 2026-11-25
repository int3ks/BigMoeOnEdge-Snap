package com.bigmoe.onedge.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.t
import com.bigmoe.onedge.tools.UserQuestion

/**
 * The model's ask_user question, pinned above the input bar: the question with a close button (= dismiss), the options
 * as compact buttons that wrap, and a field for an own answer. Kept small and height-capped (scrolls inside), so it stays
 * fully visible above the on-screen keyboard on a phone. The answer goes back to the model as the tool result.
 */
@Composable
internal fun AskUserCard(
    question: UserQuestion,
    onAnswer: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var own by rememberSaveable(question.question) { mutableStateOf("") }
    fun sendOwn() { own.trim().takeIf { it.isNotEmpty() }?.let(onAnswer) }
    val onCard = MaterialTheme.colorScheme.onSecondaryContainer
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(question.question, style = MaterialTheme.typography.bodyMedium, color = onCard, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = t("Dismiss"), tint = onCard, modifier = Modifier.size(18.dp))
                }
            }
            FlowRow(
                modifier = Modifier.padding(end = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                question.options.forEach { option ->
                    OutlinedButton(
                        onClick = { onAnswer(option) },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        modifier = Modifier.heightIn(min = 34.dp)
                    ) { Text(option, style = MaterialTheme.typography.labelLarge) }
                }
            }
            Row(
                modifier = Modifier.padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedTextField(
                    value = own,
                    onValueChange = { own = it },
                    placeholder = { Text(t("Your own answer"), style = MaterialTheme.typography.bodySmall) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendOwn() }),
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                )
                FilledIconButton(onClick = { sendOwn() }, enabled = own.isNotBlank(), modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = t("Send answer"))
                }
            }
        }
    }
}
