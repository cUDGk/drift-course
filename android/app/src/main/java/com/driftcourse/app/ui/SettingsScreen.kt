package com.driftcourse.app.ui

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.driftcourse.app.net.DriftApi
import com.driftcourse.app.settings.Background
import com.driftcourse.app.settings.SettingsStore
import com.driftcourse.app.settings.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context.applicationContext as Application) }
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val themeMode by store.themeFlow.collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
    val bg by store.bgFlow.collectAsStateWithLifecycle(initialValue = Background.Default)

    LaunchedEffect(Unit) {
        val s = store.flow.first()
        url = s.url
        token = s.token
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { inner ->
        if (!loaded) return@Scaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SectionHeader("サーバー")

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("サーバー URL") },
                placeholder = { Text("http://192.168.1.7:8787") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("トークン") },
                placeholder = { Text("server/.drift-token の中身") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = {
                    scope.launch {
                        store.save(url, token)
                        status = "保存しました"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("設定を保存")
            }

            Spacer(Modifier.size(4.dp))
            SectionHeader("LLM モデル")
            LlmModelSection(url = url, token = token)

            Spacer(Modifier.size(4.dp))
            SectionHeader("テーマ")

            ThemeModeRow(
                label = "システムに従う",
                selected = themeMode == ThemeMode.SYSTEM,
                onClick = { scope.launch { store.saveTheme(ThemeMode.SYSTEM) } },
            )
            ThemeModeRow(
                label = "ライト",
                selected = themeMode == ThemeMode.LIGHT,
                onClick = { scope.launch { store.saveTheme(ThemeMode.LIGHT) } },
            )
            ThemeModeRow(
                label = "ダーク",
                selected = themeMode == ThemeMode.DARK,
                onClick = { scope.launch { store.saveTheme(ThemeMode.DARK) } },
            )

            Spacer(Modifier.size(4.dp))
            SectionHeader("背景")

            BackgroundSection(
                bg = bg,
                onChange = { scope.launch { store.saveBackground(it) } },
                onClear = { scope.launch { store.clearBackground() } },
            )

            Spacer(Modifier.size(4.dp))
            SectionHeader("接続確認")

            Button(
                onClick = {
                    scope.launch {
                        store.save(url, token)
                        status = "テスト中…"
                        val api = DriftApi(
                            baseUrlProvider = { url },
                            tokenProvider = { token },
                        )
                        status = runCatching { api.health() }
                            .fold(
                                onSuccess = { h ->
                                    val model = h.currentModel.ifEmpty { "(なし)" }
                                    "OK · モデル=$model · 稼働=${h.ok}"
                                },
                                onFailure = { "NG: ${it.message ?: it::class.java.simpleName}" },
                            )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("接続を確認")
            }

            status?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun ThemeModeRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private enum class BgChoice { DEFAULT, COLOR, IMAGE }

private val PRESET_SWATCHES: List<Long> = listOf(
    0xFF000000, // 黒
    0xFFFFFFFF, // 白
    0xFF2C2C2E, // ダークグレー
    0xFFE5E5EA, // ライトグレー
    0xFF1C2E4A, // 紺 (青寄り)
    0xFF0F3D2E, // 深緑
    0xFF3A1C1C, // 深赤
    0xFF2A1E3F, // 紫系
    0xFFCFE3FF, // 淡い青 (primaryContainer 風)
    0xFFFFE4C7, // 淡いオレンジ
    0xFFF5F2E8, // 紙色
    0xFF1A1A2E, // 深紺
)

@Composable
private fun BackgroundSection(
    bg: Background,
    onChange: (Background) -> Unit,
    onClear: () -> Unit,
) {
    val current = when (bg) {
        is Background.Default -> BgChoice.DEFAULT
        is Background.Color -> BgChoice.COLOR
        is Background.Image -> BgChoice.IMAGE
    }
    var customOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BgChoiceRow(
            label = "デフォルト",
            selected = current == BgChoice.DEFAULT,
            onClick = { onClear() },
        )
        BgChoiceRow(
            label = "単色",
            selected = current == BgChoice.COLOR,
            onClick = {
                // 初めて単色を選んだときは黒をデフォルトとして入れる。
                if (current != BgChoice.COLOR) onChange(Background.Color(0xFF000000L))
            },
        )
        if (current == BgChoice.COLOR) {
            SwatchGrid(
                selectedArgb = (bg as? Background.Color)?.argb,
                onPick = { onChange(Background.Color(it)) },
            )
            OutlinedButton(
                onClick = { customOpen = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("カスタム色") }
        }

        BgChoiceRow(
            label = "画像",
            selected = current == BgChoice.IMAGE,
            onClick = {
                if (current != BgChoice.IMAGE) {
                    // 画像未選択の段階でラジオだけ ON はせず、画像ピッカ経由で実際の data URL を埋める。
                    // ここでは何もしない — ユーザーが下の「画像を選択」を押す。
                }
            },
        )
        if (current == BgChoice.IMAGE || current == BgChoice.DEFAULT || current == BgChoice.COLOR) {
            val pick = rememberBackgroundImagePicker { dataUrl ->
                onChange(Background.Image(dataUrl))
            }
            OutlinedButton(
                onClick = pick,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("画像を選択") }
        }
    }

    if (customOpen) {
        CustomColorDialog(
            initial = (bg as? Background.Color)?.argb ?: 0xFF000000L,
            onDismiss = { customOpen = false },
            onPick = {
                onChange(Background.Color(it))
                customOpen = false
            },
        )
    }
}

@Composable
private fun BgChoiceRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SwatchGrid(
    selectedArgb: Long?,
    onPick: (Long) -> Unit,
) {
    // 4 列 × 3 行の簡易グリッド。
    val rows = PRESET_SWATCHES.chunked(4)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { argb ->
                    SwatchCell(
                        argb = argb,
                        selected = selectedArgb == argb,
                        onClick = { onPick(argb) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 端数埋め。
                repeat(4 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SwatchCell(
    argb: Long,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(argb.toInt()))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
    ) {
        if (selected) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)),
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun CustomColorDialog(
    initial: Long,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val initA = ((initial shr 24) and 0xFFL).toInt()
    val initR = ((initial shr 16) and 0xFFL).toInt()
    val initG = ((initial shr 8) and 0xFFL).toInt()
    val initB = (initial and 0xFFL).toInt()

    var r by remember { mutableStateOf(initR.toFloat()) }
    var g by remember { mutableStateOf(initG.toFloat()) }
    var b by remember { mutableStateOf(initB.toFloat()) }
    val a = initA.coerceIn(1, 255) // alpha は常に不透明寄りに固定しても良いが初期値を保つ。

    val argb: Long = run {
        val aa = (if (a < 1) 0xFF else a).toLong()
        (aa shl 24) or (r.toInt().toLong() shl 16) or (g.toInt().toLong() shl 8) or b.toInt().toLong()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("カスタム色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(argb.toInt())),
                )
                Text("R: ${r.toInt()}", style = MaterialTheme.typography.bodySmall)
                Slider(value = r, onValueChange = { r = it }, valueRange = 0f..255f)
                Text("G: ${g.toInt()}", style = MaterialTheme.typography.bodySmall)
                Slider(value = g, onValueChange = { g = it }, valueRange = 0f..255f)
                Text("B: ${b.toInt()}", style = MaterialTheme.typography.bodySmall)
                Slider(value = b, onValueChange = { b = it }, valueRange = 0f..255f)
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(argb) }) { Text("決定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        },
    )
}

@Composable
private fun LlmModelSection(url: String, token: String) {
    val scope = rememberCoroutineScope()
    var listing by remember { mutableStateOf<com.driftcourse.app.net.ModelsResponse?>(null) }
    var fetchError by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }

    val api = remember(url, token) {
        DriftApi(baseUrlProvider = { url }, tokenProvider = { token })
    }

    suspend fun refresh() {
        loading = true
        fetchError = null
        runCatching { api.listModels() }
            .onSuccess { listing = it }
            .onFailure { fetchError = it.message ?: it::class.java.simpleName }
        loading = false
    }

    LaunchedEffect(url, token) { if (token.isNotBlank()) refresh() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val current = listing?.current.orEmpty()
        Text(
            text = "現在: ${if (current.isEmpty()) "(未取得)" else current}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (fetchError != null) {
            Text(
                text = "読み込み失敗: $fetchError",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        listing?.available?.forEach { m ->
            val isCurrent = m.isCurrent
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !isCurrent && switching == null) { confirm = m.name }
                    .padding(vertical = 8.dp),
            ) {
                RadioButton(selected = isCurrent, onClick = null)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        m.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    Text(
                        "${"%.1f".format(m.sizeBytes / (1024.0 * 1024.0 * 1024.0))} GB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        OutlinedButton(
            onClick = { scope.launch { refresh() } },
            enabled = !loading && switching == null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (loading) "取得中…" else "モデル一覧を再取得")
        }
    }

    switching?.let { name ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("モデル切替中…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$name を読み込み中。30B は 30〜60 秒かかることがあります。")
                    androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {},
        )
    }

    confirm?.let { name ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("モデルを切替") },
            text = { Text("$name に切り替えます。llama-server が再起動するため応答まで数十秒かかります。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    scope.launch {
                        switching = name
                        runCatching { api.loadModel(name) }
                            .onSuccess {
                                listing = listing?.copy(current = it.current, draft = it.draft, available = listing?.available?.map { mi ->
                                    mi.copy(isCurrent = mi.name == it.current, isDraft = mi.name == it.draft)
                                }.orEmpty())
                            }
                            .onFailure { fetchError = it.message ?: it::class.java.simpleName }
                        switching = null
                    }
                }) { Text("切替") }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("キャンセル") }
            },
        )
    }
}
