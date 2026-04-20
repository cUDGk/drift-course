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
import androidx.compose.material.icons.filled.AutoAwesome
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.driftcourse.app.net.Conversation
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

private val STRUCTURED_KEYS = setOf("gender", "user_address", "description", "personality", "scenario", "first_mes", "mes_example", "memory", "icon", "max_tokens", "response_style", "no_narration")

private val RESPONSE_STYLE_PRESETS = listOf(
    "1行だけ" to "基本的に1行 (1〜2文) で返答する。情景描写や補足は書かない。",
    "短文中心" to "短めの文 (50〜150文字) を基本にする。長々と説明しない。",
    "長文中心" to "しっかり描写を含む長めの文章 (300〜800文字) で返答する。情景・内面・口調を丁寧に書く。",
    "感情で揺らす" to "通常は短文〜中文。感情が高ぶった場面では長文になって勢いで書き連ねる。冷静な場面は一言で切り上げる。",
    "会話主体" to "ナレーションは極力省き、セリフ中心で返す。地の文は短く。",
)

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun CharacterEditScreen(
    characterId: String?,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onEditByChat: (() -> Unit)? = null,
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
    var gender by remember { mutableStateOf(initialDraft?.gender.orEmpty()) }
    var userAddress by remember { mutableStateOf(initialDraft?.user_address.orEmpty()) }
    var description by remember { mutableStateOf(initialDraft?.description.orEmpty()) }
    var personality by remember { mutableStateOf(initialDraft?.personality.orEmpty()) }
    var scenario by remember { mutableStateOf(initialDraft?.scenario.orEmpty()) }
    var firstMes by remember { mutableStateOf(initialDraft?.first_mes.orEmpty()) }
    var mesExample by remember { mutableStateOf(initialDraft?.mes_example.orEmpty()) }
    var memoryText by remember { mutableStateOf(initialDraft?.memory.orEmpty()) }
    var icon by remember { mutableStateOf(initialDraft?.icon.orEmpty()) }
    var responseStyle by remember { mutableStateOf("") }
    var noNarration by remember { mutableStateOf(false) }
    var originalCard by remember { mutableStateOf(JsonObject(emptyMap())) }
    var hydrated by remember { mutableStateOf(characterId == null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val launchIconPicker = rememberIconPicker { picked -> icon = picked }

    LaunchedEffect(characterId) {
        if (characterId != null) vm.load(characterId)
    }

    LaunchedEffect(character?.id) {
        val c = character
        if (c != null && !hydrated) {
            name = c.name
            systemPrompt = c.systemPrompt
            originalCard = c.card
            gender = readCardString(c.card, "gender")
            userAddress = readCardString(c.card, "user_address")
            description = readCardString(c.card, "description")
            personality = readCardString(c.card, "personality")
            scenario = readCardString(c.card, "scenario")
            firstMes = readCardString(c.card, "first_mes")
            mesExample = readCardString(c.card, "mes_example")
            memoryText = readCardString(c.card, "memory")
            icon = readCardString(c.card, "icon")
            responseStyle = readCardString(c.card, "response_style")
            noNarration = (c.card["no_narration"] as? JsonPrimitive)?.booleanOrNull == true
            hydrated = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (characterId == null) "新規モデル" else "モデル編集") },
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

            if (characterId != null && onEditByChat != null) {
                OutlinedButton(
                    onClick = onEditByChat,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text("AI で編集")
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                ModelAvatar(
                    iconDataUrl = icon,
                    fallbackName = name,
                    size = 96.dp,
                    modifier = Modifier.clickable { launchIconPicker() },
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { launchIconPicker() }) { Text("画像を選択") }
                    TextButton(
                        onClick = { icon = "" },
                        enabled = icon.isNotBlank(),
                    ) { Text("削除") }
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
                value = gender,
                onValueChange = { gender = it },
                label = { Text("性別") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = userAddress,
                onValueChange = { userAddress = it },
                label = { Text("ユーザの呼び方 (シチュエーション別に改行して列挙)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
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

            OutlinedTextField(
                value = memoryText,
                onValueChange = { memoryText = it },
                label = { Text("記憶") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp),
            )

            Text(
                "応答のスタイル・分量",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // プリセット。押すと現在の文章の末尾に追記 (空なら置換)。
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                RESPONSE_STYLE_PRESETS.forEach { (label, phrase) ->
                    androidx.compose.material3.AssistChip(
                        onClick = {
                            responseStyle = if (responseStyle.isBlank()) phrase
                                            else responseStyle.trimEnd() + "\n" + phrase
                        },
                        label = { Text(label) },
                    )
                }
            }
            OutlinedTextField(
                value = responseStyle,
                onValueChange = { responseStyle = it },
                label = { Text("応答のスタイル (自由記述、例: 普段は1〜2行、感情が高ぶった時だけ長文)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ナレーションを書かない", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "ON にするとセリフ本体のみで応答。地の文・動作描写・情景描写が抑止される。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                androidx.compose.material3.Switch(
                    checked = noNarration,
                    onCheckedChange = { noNarration = it },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val card = mergeCard(originalCard, gender, userAddress, description, personality, scenario, firstMes, mesExample, memoryText, icon, responseStyle, noNarration)
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
                    "対話",
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
                    Text("新しい対話")
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
            title = { Text("モデルを削除しますか？") },
            text = { Text("このモデルと紐づく対話も全て失われます。") },
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

private fun mergeCard(
    original: JsonObject,
    gender: String,
    userAddress: String,
    description: String,
    personality: String,
    scenario: String,
    firstMes: String,
    mesExample: String,
    memory: String,
    icon: String,
    responseStyle: String,
    noNarration: Boolean,
): JsonObject {
    val merged = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
    original.forEach { (k, v) ->
        if (k !in STRUCTURED_KEYS) merged[k] = v
    }
    if (gender.isNotEmpty()) merged["gender"] = JsonPrimitive(gender)
    if (userAddress.isNotEmpty()) merged["user_address"] = JsonPrimitive(userAddress)
    if (description.isNotEmpty()) merged["description"] = JsonPrimitive(description)
    if (personality.isNotEmpty()) merged["personality"] = JsonPrimitive(personality)
    if (scenario.isNotEmpty()) merged["scenario"] = JsonPrimitive(scenario)
    if (firstMes.isNotEmpty()) merged["first_mes"] = JsonPrimitive(firstMes)
    if (mesExample.isNotEmpty()) merged["mes_example"] = JsonPrimitive(mesExample)
    if (memory.isNotEmpty()) merged["memory"] = JsonPrimitive(memory)
    if (icon.isNotEmpty()) merged["icon"] = JsonPrimitive(icon)
    if (responseStyle.isNotBlank()) merged["response_style"] = JsonPrimitive(responseStyle)
    if (noNarration) merged["no_narration"] = JsonPrimitive(true)
    return JsonObject(merged)
}
