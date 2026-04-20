package com.driftcourse.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenMemory: (String) -> Unit,
    onNavigateConversation: (String) -> Unit,
    vm: ConversationVM = viewModel(),
) {
    val conversation by vm.conversation.collectAsStateWithLifecycle()
    val messages by vm.messages.collectAsStateWithLifecycle()
    val streaming by vm.streaming.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val characterName by vm.characterName.collectAsStateWithLifecycle()

    LaunchedEffect(conversationId) { vm.load(conversationId) }

    var noteMode by rememberSaveable { mutableStateOf(false) }

    // 長押しで開くアクションシートと、編集/分岐のダイアログ状態。
    var menuFor by remember { mutableStateOf<UiMessage?>(null) }
    var editFor by remember { mutableStateOf<EditTarget?>(null) }

    val bg = rememberSettingsBackground()
    AppBackground(bg = bg) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        TopAppBar(
            title = {
                Column {
                    Text(
                        conversation?.title?.ifBlank { "(無題)" } ?: "…",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDotLocal(streaming = streaming, hasError = error != null)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                error != null -> "エラー"
                                streaming -> "生成中…"
                                else -> "待機中"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                }
            },
            actions = {
                IconButton(onClick = { noteMode = !noteMode }) {
                    // 押したら切り替わる先のアイコンを出す (常に「現在の反対モード」を示す)。
                    if (noteMode) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "チャット表示に切替")
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = "ノート表示に切替")
                    }
                }
                IconButton(onClick = { onOpenMemory(conversationId) }) {
                    Icon(Icons.Default.Memory, contentDescription = "記憶")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        )

        error?.let { msg ->
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                EmptyConv()
            } else if (noteMode) {
                NoteListLocal(
                    messages = messages,
                    modelName = characterName,
                    onLongPress = { msg -> menuFor = msg },
                )
            } else {
                MessageListLocal(
                    messages = messages,
                    streaming = streaming,
                    onLongPress = { msg -> menuFor = msg },
                )
            }
        }

        ComposerLocal(
            streaming = streaming,
            onSend = vm::send,
            onCancel = vm::cancel,
        )
    }
    }

    // 長押しメニュー。
    menuFor?.let { target ->
        ActionSheet(
            target = target,
            onDismiss = { menuFor = null },
            onEdit = {
                editFor = EditTarget(msg = target, branch = false)
                menuFor = null
            },
            onFork = {
                editFor = EditTarget(msg = target, branch = true)
                menuFor = null
            },
        )
    }

    // 編集/分岐ダイアログ。
    editFor?.let { target ->
        EditDialog(
            target = target,
            onDismiss = { editFor = null },
            onSave = { newText ->
                val id = target.msg.id ?: return@EditDialog
                if (target.branch) {
                    vm.forkFrom(id, newText, onNavigate = onNavigateConversation)
                } else {
                    vm.editMessage(id, newText)
                }
                editFor = null
            },
        )
    }
}

private data class EditTarget(val msg: UiMessage, val branch: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionSheet(
    target: UiMessage,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onFork: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            TextButton(
                onClick = {
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onEdit() }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("ここを編集") }
            if (target.role == "user") {
                TextButton(
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onFork() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("ここから分岐") }
            }
            Spacer(Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun EditDialog(
    target: EditTarget,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(target.msg.id) { mutableStateOf(target.msg.content) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (target.branch) "ここから分岐" else "ここを編集") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                enabled = text.isNotBlank(),
            ) { Text(if (target.branch) "分岐" else "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

@Composable
private fun StatusDotLocal(streaming: Boolean, hasError: Boolean) {
    val color = when {
        hasError -> MaterialTheme.colorScheme.error
        streaming -> Color(0xFFFF9500)
        else -> Color(0xFF34C759)
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(50))
            .background(color),
    )
}

@Composable
private fun EmptyConv() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "最初のメッセージを送ると対話が始まります。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun MessageListLocal(
    messages: List<UiMessage>,
    streaming: Boolean,
    onLongPress: (UiMessage) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .scrollCaptureProvider(lazyListState = listState, itemCount = messages.size),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages) { msg ->
            MessageBubbleLocal(msg = msg, streaming = streaming, onLongPress = onLongPress)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleLocal(
    msg: UiMessage,
    streaming: Boolean,
    onLongPress: (UiMessage) -> Unit,
) {
    val isUser = msg.role == "user"
    val shape = if (isUser) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
    }
    val bg = if (isUser) MaterialTheme.colorScheme.primary
             else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimary
             else MaterialTheme.colorScheme.onSurface

    // 長押しで編集/分岐メニュー。id が無い (ローカルのみの placeholder) 場合は無効化。
    val longPressEnabled = msg.id != null

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = shape,
            color = bg,
            contentColor = fg,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .combinedClickable(
                    enabled = longPressEnabled,
                    onClick = {},
                    onLongClick = { onLongPress(msg) },
                ),
        ) {
            if (msg.content.isEmpty() && !isUser && streaming) {
                Text(
                    text = "…",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            } else {
                MarkdownText(
                    text = msg.content,
                    color = fg,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteListLocal(
    messages: List<UiMessage>,
    modelName: String,
    onLongPress: (UiMessage) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .scrollCaptureProvider(lazyListState = listState, itemCount = messages.size),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(messages) { msg ->
            val label = if (msg.role == "user") "あなた" else (modelName.ifBlank { "モデル" })
            val body = buildString {
                append("**").append(label).append("**\n\n")
                append(msg.content)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        enabled = msg.id != null,
                        onClick = {},
                        onLongClick = { onLongPress(msg) },
                    ),
            ) {
                MarkdownText(
                    text = body,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComposerLocal(
    streaming: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember { mutableStateOf("") }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp, max = 110.dp),
                placeholder = { Text("メッセージを送信") },
                enabled = !streaming,
                maxLines = 3,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
            Spacer(Modifier.width(8.dp))
            if (streaming) {
                FilledIconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "停止")
                }
            } else {
                FilledIconButton(
                    onClick = {
                        val t = text
                        if (t.isNotBlank()) {
                            onSend(t)
                            text = ""
                        }
                    },
                    enabled = text.isNotBlank(),
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "送信")
                }
            }
        }
    }
}
