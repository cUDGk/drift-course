package com.driftcourse.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val iconDataUrl by vm.iconDataUrl.collectAsStateWithLifecycle()

    LaunchedEffect(conversationId) { vm.load(conversationId) }

    // ストリーム中で、まだ delta が来ていない (または非常に短い) 時だけ大きなアバターを出す。
    val lastAssistantLen = messages.lastOrNull()
        ?.takeIf { it.role == "assistant" }?.content?.length ?: 0
    val showLoadingAvatar = streaming && lastAssistantLen < 4

    // CHAT: 通常のバブル左右振り分け
    // NOTE_SIDED: 枠なし、ただし発言者で左右に寄せる
    // NOTE_FLAT: 枠なし、全て左寄せの文書形式
    var displayMode by rememberSaveable { mutableStateOf(DisplayMode.CHAT) }

    // 長押しで開くアクションシートと、編集/分岐のダイアログ状態。
    var menuFor by remember { mutableStateOf<UiMessage?>(null) }
    var editFor by remember { mutableStateOf<EditTarget?>(null) }

    // 範囲画像保存モード。index ベースで [start..end] の連続範囲 (end >= start) を保持。
    var selectionMode by remember { mutableStateOf(false) }
    var selStart by remember { mutableStateOf<Int?>(null) }
    var selEnd by remember { mutableStateOf<Int?>(null) }
    var exporting by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val density = LocalDensity.current
    val config = LocalConfiguration.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 画面のテーマが dark かどうか。設定経由 (SYSTEM/LIGHT/DARK) の最終解決は MainActivity がしているので
    // ここは現在の MaterialTheme の明暗だけ見て判断する。
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val bg = rememberSettingsBackground()
    AppBackground(bg = bg) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        TopAppBar(
            title = {
                if (selectionMode) {
                    Text(
                        "画像保存: 範囲を選択",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                } else {
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
                }
            },
            navigationIcon = {
                if (selectionMode) {
                    IconButton(onClick = {
                        selectionMode = false
                        selStart = null
                        selEnd = null
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "キャンセル")
                    }
                } else {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            },
            actions = {
                if (selectionMode) {
                    val canConfirm = selStart != null && selEnd != null && !exporting
                    IconButton(
                        enabled = canConfirm,
                        onClick = {
                            val s = selStart ?: return@IconButton
                            val e = selEnd ?: return@IconButton
                            val lo = minOf(s, e)
                            val hi = maxOf(s, e)
                            val slice = messages.subList(
                                lo.coerceAtLeast(0),
                                (hi + 1).coerceAtMost(messages.size),
                            ).toList()
                            if (slice.isEmpty()) return@IconButton
                            exporting = true
                            val widthPx = with(density) { config.screenWidthDp.dp.toPx() }.toInt()
                            scope.launch {
                                val ok = runCatching {
                                    val bmp = renderMessagesToBitmap(
                                        context = context,
                                        messages = slice,
                                        widthPx = widthPx,
                                        darkTheme = isDark,
                                    )
                                    val uri = withContext(Dispatchers.IO) {
                                        saveBitmapToPictures(context, bmp)
                                    }
                                    uri != null
                                }.getOrDefault(false)
                                exporting = false
                                if (ok) {
                                    selectionMode = false
                                    selStart = null
                                    selEnd = null
                                    snackbarHostState.showSnackbar("画像を保存しました")
                                } else {
                                    snackbarHostState.showSnackbar("保存に失敗しました")
                                }
                            }
                        },
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "決定")
                    }
                } else {
                    IconButton(onClick = {
                        displayMode = when (displayMode) {
                            DisplayMode.CHAT -> DisplayMode.NOTE_SIDED
                            DisplayMode.NOTE_SIDED -> DisplayMode.NOTE_FLAT
                            DisplayMode.NOTE_FLAT -> DisplayMode.CHAT
                        }
                    }) {
                        // 次に切り替わるモードのアイコンを表示する。
                        when (displayMode) {
                            DisplayMode.CHAT -> Icon(Icons.AutoMirrored.Filled.Notes, contentDescription = "枠なし (左右) 表示")
                            DisplayMode.NOTE_SIDED -> Icon(Icons.Default.Article, contentDescription = "ノート (左寄せ) 表示")
                            DisplayMode.NOTE_FLAT -> Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "チャット表示")
                        }
                    }
                    IconButton(onClick = {
                        selectionMode = true
                        selStart = null
                        selEnd = null
                    }) {
                        Icon(Icons.Default.PhotoCamera, contentDescription = "範囲を画像で保存")
                    }
                    IconButton(onClick = { onOpenMemory(conversationId) }) {
                        Icon(Icons.Default.Memory, contentDescription = "記憶")
                    }
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
            } else when (displayMode) {
                DisplayMode.NOTE_FLAT -> NoteListLocal(
                    messages = messages,
                    modelName = characterName,
                    onLongPress = { msg -> if (!selectionMode) menuFor = msg },
                    selectionMode = selectionMode,
                    selStart = selStart,
                    selEnd = selEnd,
                    onItemTap = { idx -> onSelectTap(idx, selStart, selEnd) { a, b -> selStart = a; selEnd = b } },
                )
                DisplayMode.NOTE_SIDED -> NoteSidedListLocal(
                    messages = messages,
                    modelName = characterName,
                    onLongPress = { msg -> if (!selectionMode) menuFor = msg },
                    selectionMode = selectionMode,
                    selStart = selStart,
                    selEnd = selEnd,
                    onItemTap = { idx -> onSelectTap(idx, selStart, selEnd) { a, b -> selStart = a; selEnd = b } },
                )
                DisplayMode.CHAT -> MessageListLocal(
                    messages = messages,
                    streaming = streaming,
                    onLongPress = { msg -> if (!selectionMode) menuFor = msg },
                    selectionMode = selectionMode,
                    selStart = selStart,
                    selEnd = selEnd,
                    onItemTap = { idx -> onSelectTap(idx, selStart, selEnd) { a, b -> selStart = a; selEnd = b } },
                )
            }

            if (exporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 4.dp,
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Spacer(Modifier.size(12.dp))
                            Text("画像を生成中…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp),
            ) { data ->
                Snackbar(snackbarData = data)
            }
        }

        if (!selectionMode) {
            ComposerLocal(
                streaming = streaming,
                onSend = vm::send,
                onCancel = vm::cancel,
            )
        }
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

/**
 * 連続範囲選択のタップ処理。
 * - どちらも未設定 → start 設定
 * - start のみ設定 → end 設定 (start 以下なら逆転)
 * - 既に両方設定済み → end を上書き (範囲を拡大/縮小)
 * シンプルな UX に倒していて、「範囲外をタップしたらリセット」とか「中抜け対応」は入れない。
 */
private inline fun onSelectTap(
    idx: Int,
    start: Int?,
    end: Int?,
    apply: (Int?, Int?) -> Unit,
) {
    when {
        start == null -> apply(idx, null)
        end == null -> apply(start, idx)
        else -> apply(start, idx)
    }
}

private fun isIndexSelected(idx: Int, start: Int?, end: Int?): Boolean {
    if (start == null) return false
    val s = start
    val e = end ?: start
    val lo = minOf(s, e)
    val hi = maxOf(s, e)
    return idx in lo..hi
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
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            TextButton(
                onClick = {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(target.content))
                    scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("コピー") }
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
    selectionMode: Boolean,
    selStart: Int?,
    selEnd: Int?,
    onItemTap: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty() && !selectionMode) {
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
        items(messages.size) { idx ->
            val msg = messages[idx]
            MessageBubbleLocal(
                msg = msg,
                streaming = streaming,
                onLongPress = onLongPress,
                selectionMode = selectionMode,
                selected = isIndexSelected(idx, selStart, selEnd),
                onSelectTap = { onItemTap(idx) },
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleLocal(
    msg: UiMessage,
    streaming: Boolean,
    onLongPress: (UiMessage) -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onSelectTap: () -> Unit,
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
    // 選択モード中は長押しを殺し、タップで選択を切替える。
    val longPressEnabled = msg.id != null && !selectionMode

    val interaction = if (selectionMode) {
        Modifier.clickable(onClick = onSelectTap)
    } else {
        Modifier.combinedClickable(
            enabled = longPressEnabled,
            onClick = {},
            onLongClick = { onLongPress(msg) },
        )
    }
    val borderMod = if (selected) {
        Modifier.border(
            width = 2.dp,
            color = MaterialTheme.colorScheme.tertiary,
            shape = shape,
        )
    } else Modifier

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = shape,
            color = bg,
            contentColor = fg,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .then(borderMod)
                .then(interaction),
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
    selectionMode: Boolean,
    selStart: Int?,
    selEnd: Int?,
    onItemTap: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty() && !selectionMode) {
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
        items(messages.size) { idx ->
            val msg = messages[idx]
            val label = if (msg.role == "user") "あなた" else (modelName.ifBlank { "モデル" })
            val body = buildString {
                append("**").append(label).append("**\n\n")
                append(msg.content)
            }
            val selected = isIndexSelected(idx, selStart, selEnd)
            val shape = RoundedCornerShape(8.dp)
            val interaction = if (selectionMode) {
                Modifier.clickable { onItemTap(idx) }
            } else {
                Modifier.combinedClickable(
                    enabled = msg.id != null,
                    onClick = {},
                    onLongClick = { onLongPress(msg) },
                )
            }
            val borderMod = if (selected) {
                Modifier.border(2.dp, MaterialTheme.colorScheme.tertiary, shape)
            } else Modifier
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(borderMod)
                    .padding(4.dp)
                    .then(interaction),
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

/**
 * ストリーム生成が始まったが、まだ最初の delta が戻っていない間だけ
 * モデルの大きなアバター + 「考え中…」を表示するオーバーレイ。
 * composer (下の入力欄) に被らないよう下側に 120.dp の余白を取る。
 */
@Composable
private fun LoadingAvatarOverlay(
    visible: Boolean,
    iconDataUrl: String?,
    fallbackName: String,
) {
    val transition = rememberInfiniteTransition(label = "thinking-pulse")
    val scale by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "thinking-scale",
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 120.dp),
            ) {
                ModelAvatar(
                    iconDataUrl = iconDataUrl,
                    fallbackName = fallbackName,
                    size = 128.dp,
                    modifier = Modifier.scale(scale),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "考え中…",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

enum class DisplayMode { CHAT, NOTE_SIDED, NOTE_FLAT }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteSidedListLocal(
    messages: List<UiMessage>,
    modelName: String,
    onLongPress: (UiMessage) -> Unit,
    selectionMode: Boolean,
    selStart: Int?,
    selEnd: Int?,
    onItemTap: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.content?.length) {
        if (messages.isNotEmpty() && !selectionMode) listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .scrollCaptureProvider(lazyListState = listState, itemCount = messages.size),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages.size) { idx ->
            val msg = messages[idx]
            val isUser = msg.role == "user"
            val label = if (isUser) "あなた" else (modelName.ifBlank { "モデル" })
            val body = buildString {
                append("**").append(label).append("**\n\n").append(msg.content)
            }
            val selected = isIndexSelected(idx, selStart, selEnd)
            val shape = RoundedCornerShape(8.dp)
            val interaction = if (selectionMode) {
                Modifier.clickable { onItemTap(idx) }
            } else {
                Modifier.combinedClickable(
                    enabled = msg.id != null,
                    onClick = {},
                    onLongClick = { onLongPress(msg) },
                )
            }
            val borderMod = if (selected) {
                Modifier.border(2.dp, MaterialTheme.colorScheme.tertiary, shape)
            } else Modifier
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(borderMod)
                    .padding(4.dp)
                    .then(interaction),
                horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            ) {
                Box(modifier = Modifier.widthIn(max = 320.dp)) {
                    MarkdownText(text = body, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** 色の輝度。ダークテーマ判定用の軽量ヘルパー。 */
private fun Color.luminance(): Float {
    // 近似。厳密な sRGB 線形化は不要 (薄暗いか明るいかの 2 分類で使うだけ)。
    return (red * 0.2126f + green * 0.7152f + blue * 0.0722f)
}
