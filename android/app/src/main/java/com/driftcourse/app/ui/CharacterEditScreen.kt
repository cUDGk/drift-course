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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

private val STRUCTURED_KEYS = setOf("description", "personality", "scenario", "first_mes", "mes_example")

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

    // 新規作成時のみ、AI 対話フローからのドロップボックスをワンショット消費する。
    val initialDraft = remember(characterId) {
        if (characterId == null) {
            PendingCharacterPrefill.draft.also { PendingCharacterPrefill.draft = null }
        } else {
            null
        }
    }

    var name by remember { mutableStateOf(initialDraft?.name.orEmpty()) }
    var systemPrompt by remember { mutableStateOf("") }
    var description by remember { mutableStateOf(initialDraft?.description.orEmpty()) }
    var personality by remember { mutableStateOf(initialDraft?.personality.orEmpty()) }
    var scenario by remember { mutableStateOf(initialDraft?.scenario.orEmpty()) }
    var firstMes by remember { mutableStateOf(initialDraft?.first_mes.orEmpty()) }
    var mesExample by remember { mutableStateOf(initialDraft?.mes_example.orEmpty()) }
    var originalCard by remember { mutableStateOf(JsonObject(emptyMap())) }
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
            originalCard = c.card
            description = readCardString(c.card, "description")
            personality = readCardString(c.card, "personality")
            scenario = readCardString(c.card, "scenario")
            firstMes = readCardString(c.card, "first_mes")
            mesExample = readCardString(c.card, "mes_example")
            hydrated = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (characterId == null) "新規キャラクター" else "キャラクター編集") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
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
                label = { Text("名前") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("システムプロンプト") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("人物説明") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
            )

            OutlinedTextField(
                value = personality,
                onValueChange = { personality = it },
                label = { Text("性格") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
            )

            OutlinedTextField(
                value = scenario,
                onValueChange = { scenario = it },
                label = { Text("状況設定") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
            )

            OutlinedTextField(
                value = firstMes,
                onValueChange = { firstMes = it },
                label = { Text("初回挨拶") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
            )

            OutlinedTextField(
                value = mesExample,
                onValueChange = { mesExample = it },
                label = { Text("例会話") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val card = mergeCard(originalCard, description, personality, scenario, firstMes, mesExample)
                        if (characterId == null) {
                            vm.create(name.trim(), systemPrompt, card) { onBack() }
                        } else {
                            vm.save(characterId, name.trim(), systemPrompt, card) { onBack() }
                        }
                    },
                    enabled = !busy && name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (characterId == null) "作成" else "保存")
                }
                if (characterId != null) {
                    OutlinedButton(
                        onClick = { confirmDelete = true },
                        enabled = !busy,
                    ) {
                        Text("削除")
                    }
                }
            }

            if (characterId != null) {
                Spacer(Modifier.padding(vertical = 4.dp))
                Text(
                    "会話",
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
                    Text("新しい会話")
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
            title = { Text("キャラクターを削除しますか？") },
            text = { Text("このキャラクターと紐づく会話も全て失われます。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.delete(characterId) { onBack() }
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("キャンセル") }
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
                conv.title.ifBlank { "(無題)" },
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

private fun readCardString(card: JsonObject, key: String): String {
    val el = card[key] ?: return ""
    return runCatching { el.jsonPrimitive.contentOrNull.orEmpty() }.getOrDefault("")
}

private fun mergeCard(
    original: JsonObject,
    description: String,
    personality: String,
    scenario: String,
    firstMes: String,
    mesExample: String,
): JsonObject {
    val merged = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    original.forEach { (k, v) ->
        if (k !in STRUCTURED_KEYS) merged[k] = v
    }
    if (description.isNotEmpty()) merged["description"] = JsonPrimitive(description)
    if (personality.isNotEmpty()) merged["personality"] = JsonPrimitive(personality)
    if (scenario.isNotEmpty()) merged["scenario"] = JsonPrimitive(scenario)
    if (firstMes.isNotEmpty()) merged["first_mes"] = JsonPrimitive(firstMes)
    if (mesExample.isNotEmpty()) merged["mes_example"] = JsonPrimitive(mesExample)
    return JsonObject(merged)
}
