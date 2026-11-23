package com.bigmoe.onedge.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bigmoe.onedge.core.t
import com.bigmoe.onedge.tools.UserQuestion

/**
 * The model's ask_user question in the chat: the question, one button per option and a field for an own answer.
 * The answer goes back to the model as the tool result; Dismiss tells the model the user did not answer.
 */
@Composable
internal fun AskUserCard(
    question: UserQuestion,
    onAnswer: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var own by rememberSaveable(question.question) { mutableStateOf("") }
    fun sendOwn() { own.trim().takeIf { it.isNotEmpty() }?.let(onAnswer) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                t("The model asks you"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(question.question, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            question.options.forEach { option ->
                OutlinedButton(onClick = { onAnswer(option) }, modifier = Modifier.fillMaxWidth()) {
                    Text(option, textAlign = TextAlign.Start, modifier = Modifier.fillMaxWidth())
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = own,
                    onValueChange = { own = it },
                    placeholder = { Text(t("Your own answer")) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendOwn() }),
                    modifier = Modifier.weight(1f)
                )
                FilledIconButton(onClick = { sendOwn() }, enabled = own.isNotBlank()) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = t("Send answer"))
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(t("Dismiss")) }
        }
    }
}
