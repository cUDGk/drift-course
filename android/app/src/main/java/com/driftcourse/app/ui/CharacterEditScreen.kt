package com.driftcourse.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.driftcourse.app.net.Conversation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private val PRETTY_JSON = Json { prettyPrint = true }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CharacterEditScreen(
    characterId: String?,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    vm: CharacterEditVM = viewModel(),
) {
    val character by vm.character.collectAsStateWithLifecycle()
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var cardText by remember { mutableStateOf("{}") }
    var cardValid by remember { mutableStateOf(true) }
    var hydrated by remember { mutableStateOf(characterId == null) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(characterId) {
        if (characterId != null) vm.load(characterId)
    }

    LaunchedEffect(character?.id) {
        val c = character
        if (c != null && !hydrated) {
            name = c.name
            systemPrompt = c.systemPrompt
            cardText = PRETTY_JSON.encodeToString(JsonObject.serializer(), c.card)
            hydrated = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (characterId == null) "New character" else "Edit character") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            error?.let {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("System prompt") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
            )

            OutlinedTextField(
                value = cardText,
                onValueChange = {
                    cardText = it
                    cardValid = runCatching { parseCard(it) }.isSuccess
                },
                label = { Text("Card (JSON object)") },
                isError = !cardValid,
                supportingText = {
                    Text(
                        if (cardValid) "有効な JSON オブジェクト" else "JSON object としてパースできません",
                        color = if (cardValid) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val card = runCatching { parseCard(cardText) }.getOrNull() ?: return@Button
                        if (characterId == null) {
                            vm.create(name.trim(), systemPrompt, card) { onBack() }
                        } else {
                            vm.save(characterId, name.trim(), systemPrompt, card) { onBack() }
                        }
                    },
                    enabled = !busy && name.isNotBlank() && cardValid,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (characterId == null) "Create" else "Save")
                }
                if (characterId != null) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        enabled = !busy,
                    ) {
                        Text("Delete")
                    }
                }
            }

            if (characterId != null) {
                Spacer(Modifier.padding(vertical = 4.dp))
                Text(
                    "CONVERSATIONS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(
                    onClick = {
                        vm.newConversation(characterId) { conv -> onOpenConversation(conv.id) }
                    },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text("New conversation")
                }
                conversations.forEach { conv ->
                    ConversationRow(conv, onClick = { onOpenConversation(conv.id) })
                }
            }
        }
    }

    if (confirmDelete && characterId != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete character?") },
            text = { Text("このキャラクターと紐づく会話も全て失われます。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(characterId) { onBack() }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ConversationRow(conv: Conversation, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                conv.title.ifBlank { "(untitled)" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                formatEpochSeconds(conv.updatedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun parseCard(text: String): JsonObject {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return JsonObject(emptyMap())
    return Json.parseToJsonElement(trimmed) as JsonObject
}
