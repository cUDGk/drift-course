package com.driftcourse.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.driftcourse.app.net.Character
import com.driftcourse.app.net.Conversation
import com.driftcourse.app.net.isBare

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsHomeScreen(
    onOpenSettings: () -> Unit,
    onOpenConversation: (String) -> Unit,
    vm: ConversationsHomeVM = viewModel(),
) {
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val charactersById by vm.charactersById.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.reload() }

    val bg = rememberSettingsBackground()
    AppBackground(bg = bg) {
    Scaffold(
        containerColor = androidx.compose.ui.graphics.Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("DriftCourse", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "設定")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (!busy) showPicker = true },
            ) {
                Icon(Icons.Default.Add, contentDescription = "新しい対話")
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            if (loading || busy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            error?.let { msg ->
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            if (conversations.isEmpty() && !loading) {
                EmptyConversationsState()
            } else {
                LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                    items(conversations, key = { it.id }) { conv ->
                        ConversationHomeRow(
                            conv = conv,
                            character = charactersById[conv.characterId],
                            onOpen = { onOpenConversation(conv.id) },
                            onDelete = { vm.delete(conv.id) },
                            onRename = { newTitle -> vm.rename(conv.id, newTitle) },
                        )
                    }
                }
            }
        }
    }
    }

    if (showPicker) {
        NewConversationPicker(
            characters = charactersById.values.filterNot { it.isBare() }.sortedByDescending { it.updatedAt },
            onDismiss = { showPicker = false },
            onPickBare = {
                showPicker = false
                if (!busy) vm.createNew { onOpenConversation(it.id) }
            },
            onPickCharacter = { character ->
                showPicker = false
                if (!busy) vm.createWithCharacter(character.id) { onOpenConversation(it.id) }
            },
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NewConversationPicker(
    characters: List<com.driftcourse.app.net.Character>,
    onDismiss: () -> Unit,
    onPickBare: () -> Unit,
    onPickCharacter: (com.driftcourse.app.net.Character) -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState()
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(max = 480.dp),
        ) {
            Text(
                "相手を選ぶ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            androidx.compose.material3.ListItem(
                headlineContent = { Text("素のモデル") },
                supportingContent = { Text("ペルソナなしで直接 LLM と話す") },
                leadingContent = {
                    ModelAvatar(iconDataUrl = null, fallbackName = "素", size = 40.dp)
                },
                modifier = Modifier.clickable { onPickBare() },
            )
            if (characters.isEmpty()) {
                Text(
                    "作成済みのモデルはまだありません",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(characters, key = { it.id }) { c ->
                        androidx.compose.material3.ListItem(
                            headlineContent = { Text(c.name.ifBlank { "(名前なし)" }) },
                            leadingContent = {
                                ModelAvatar(
                                    iconDataUrl = readCardString(c.card, "icon").ifBlank { null },
                                    fallbackName = c.name,
                                    size = 40.dp,
                                )
                            },
                            modifier = Modifier.clickable { onPickCharacter(c) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyConversationsState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Text(
                "まだ対話がありません",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "下の + ボタンから始めてください",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationHomeRow(
    conv: Conversation,
    character: Character?,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onOpen,
                onLongClick = { menuOpen = true },
            ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            ModelAvatar(
                iconDataUrl = character?.card?.let { readCardString(it, "icon") },
                // bare モデルは名前空なので「素」で対話一覧から判別できるようにする。
                fallbackName = if (character?.isBare() == true) "素" else character?.name.orEmpty(),
                size = 32.dp,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    conv.title.ifBlank { "(無題)" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    formatEpochSeconds(conv.updatedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("名前を変更") },
                    onClick = {
                        menuOpen = false
                        renameOpen = true
                    },
                )
                DropdownMenuItem(
                    text = { Text("削除") },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }

    if (renameOpen) {
        var text by remember(conv.id) { mutableStateOf(conv.title) }
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("名前を変更") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    renameOpen = false
                    onRename(text)
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameOpen = false }) { Text("キャンセル") }
            },
        )
    }
}
