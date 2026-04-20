package com.driftcourse.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.driftcourse.app.net.Character
import com.driftcourse.app.net.CharacterPatch
import com.driftcourse.app.net.ChatMessage
import com.driftcourse.app.net.DriftApi
import com.driftcourse.app.net.SseClient
import com.driftcourse.app.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 既存モデルを AI と対話して編集するフロー。最終化時は PATCH /characters/{id} に
 * 非空の項目だけを書き戻し、未知のカードキーは保持する。
 */
class ModelEditChatVM(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private var currentUrl = ""
    private var currentToken = ""
    private val api = DriftApi(
        baseUrlProvider = { currentUrl },
        tokenProvider = { currentToken },
    )
    private val sse = SseClient(
        baseUrlProvider = { currentUrl },
        tokenProvider = { currentToken },
    )
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _character = MutableStateFlow<Character?>(null)
    val character: StateFlow<Character?> = _character.asStateFlow()

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _finalizing = MutableStateFlow(false)
    val finalizing: StateFlow<Boolean> = _finalizing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done: StateFlow<Boolean> = _done.asStateFlow()

    private var modelId: String? = null
    private var streamJob: Job? = null

    init {
        viewModelScope.launch {
            settings.flow.collect {
                currentUrl = it.url
                currentToken = it.token
            }
        }
    }

    fun load(id: String) {
        if (modelId == id && _character.value != null) return
        modelId = id
        viewModelScope.launch {
            refreshCfg()
            _error.value = null
            try {
                val c = api.getCharacter(id)
                _character.value = c
                val opener = "現在のモデル『${c.name.ifBlank { "(名前なし)" }}』の内容を読み込みました。どこをどう変えたいですか？"
                _messages.value = listOf(UiMessage("assistant", opener))
            } catch (t: Throwable) {
                Log.e(TAG, "load failed", t)
                _error.value = t.message ?: "読み込みに失敗しました"
            }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _streaming.value || _finalizing.value) return
        val c = _character.value ?: return

        viewModelScope.launch {
            refreshCfg()
            if (currentToken.isBlank()) {
                _error.value = "トークンが未設定です"
                return@launch
            }
            val visible = _messages.value + UiMessage("user", trimmed) + UiMessage("assistant", "")
            _messages.value = visible
            _error.value = null
            _streaming.value = true

            val history = buildList {
                add(ChatMessage("system", systemPromptWithState(c)))
                visible.dropLast(1).forEach { add(ChatMessage(it.role, it.content)) }
            }

            streamJob = launch {
                val buf = StringBuilder()
                try {
                    sse.chat(history).collect { delta ->
                        buf.append(delta)
                        updateLastAssistant(buf.toString())
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "chat failed", t)
                    _error.value = t.message ?: "ストリーミングに失敗しました"
                    updateLastAssistant(buf.toString() + "\n\n[エラー] ${t.message ?: t::class.java.simpleName}")
                } finally {
                    _streaming.value = false
                }
            }
        }
    }

    fun finalize() {
        if (_streaming.value || _finalizing.value) return
        val c = _character.value ?: return
        val visible = _messages.value
        if (visible.none { it.role == "user" }) {
            _error.value = "もう少し対話を進めてから最終化してください。"
            return
        }

        viewModelScope.launch {
            refreshCfg()
            if (currentToken.isBlank()) {
                _error.value = "トークンが未設定です"
                return@launch
            }
            _error.value = null
            _finalizing.value = true

            val historyForServer = buildList {
                add(ChatMessage("system", systemPromptWithState(c)))
                visible.forEach { add(ChatMessage(it.role, it.content)) }
                add(ChatMessage("user", FINALIZE_INSTRUCTION))
            }

            _streaming.value = true
            streamJob = launch {
                val buf = StringBuilder()
                try {
                    sse.chat(historyForServer).collect { delta -> buf.append(delta) }
                    val parsed = parseDraft(buf.toString())
                    if (parsed == null) {
                        _error.value = "JSON の抽出に失敗しました。もう一度お試しください。"
                    } else {
                        applyPatch(c, parsed)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "finalize failed", t)
                    _error.value = t.message ?: "最終化に失敗しました"
                } finally {
                    _streaming.value = false
                    _finalizing.value = false
                }
            }
        }
    }

    fun cancel() {
        streamJob?.cancel()
        _streaming.value = false
        _finalizing.value = false
    }

    private suspend fun applyPatch(current: Character, draft: CharacterDraft) {
        // 非空の項目だけ書き戻す。未知のカードキーは保持する。
        val merged = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        current.card.forEach { (k, v) -> merged[k] = v }
        if (draft.description.isNotBlank()) merged["description"] = JsonPrimitive(draft.description)
        if (draft.personality.isNotBlank()) merged["personality"] = JsonPrimitive(draft.personality)
        if (draft.scenario.isNotBlank()) merged["scenario"] = JsonPrimitive(draft.scenario)
        if (draft.first_mes.isNotBlank()) merged["first_mes"] = JsonPrimitive(draft.first_mes)
        if (draft.mes_example.isNotBlank()) merged["mes_example"] = JsonPrimitive(draft.mes_example)
        if (draft.memory.isNotBlank()) merged["memory"] = JsonPrimitive(draft.memory)
        val patch = CharacterPatch(
            name = if (draft.name.isNotBlank()) draft.name.trim() else null,
            systemPrompt = null,
            card = JsonObject(merged),
        )
        try {
            api.patchCharacter(current.id, patch)
            _done.value = true
        } catch (t: Throwable) {
            Log.e(TAG, "patch failed", t)
            _error.value = t.message ?: "保存に失敗しました"
        }
    }

    private fun updateLastAssistant(content: String) {
        val list = _messages.value.toMutableList()
        val idx = list.indexOfLast { it.role == "assistant" }
        if (idx < 0) return
        list[idx] = list[idx].copy(content = content)
        _messages.value = list
    }

    private fun parseDraft(raw: String): CharacterDraft? {
        val trimmed = raw.trim()
        runCatching {
            return json.decodeFromString(CharacterDraft.serializer(), trimmed)
        }
        val first = trimmed.indexOf('{')
        val last = trimmed.lastIndexOf('}')
        if (first in 0 until last) {
            val slice = trimmed.substring(first, last + 1)
            runCatching {
                return json.decodeFromString(CharacterDraft.serializer(), slice)
            }
        }
        return null
    }

    private fun systemPromptWithState(c: Character): String {
        val state = buildString {
            append("現在のモデル:\n")
            append("- name: ").append(c.name).append('\n')
            append("- description: ").append(readCard(c.card, "description")).append('\n')
            append("- personality: ").append(readCard(c.card, "personality")).append('\n')
            append("- scenario: ").append(readCard(c.card, "scenario")).append('\n')
            append("- first_mes: ").append(readCard(c.card, "first_mes")).append('\n')
            append("- mes_example: ").append(readCard(c.card, "mes_example")).append('\n')
            append("- memory: ").append(readCard(c.card, "memory")).append('\n')
            append('\n')
        }
        return state + SYSTEM_PROMPT
    }

    private fun readCard(card: JsonObject, key: String): String {
        val el = card[key] ?: return ""
        return runCatching { el.jsonPrimitive.contentOrNull.orEmpty() }.getOrDefault("")
    }

    private suspend fun refreshCfg() {
        val cfg = settings.flow.first()
        currentUrl = cfg.url
        currentToken = cfg.token
    }

    companion object {
        private const val TAG = "ModelEditChatVM"

        private const val FINALIZE_INSTRUCTION =
            "ここまでの内容を以下の JSON だけで返してください。前置き・説明・``` コードフェンス・" +
                "『はい、わかりました』のような返事は一切禁止。キー: " +
                "name / description / personality / scenario / first_mes / mes_example / memory。" +
                "変更する必要がない項目は空文字のままにして、変更したい項目だけ書き換えた値を入れてください。"

        private val SYSTEM_PROMPT = """
            あなたはキャラクター設計アシスタントです。既存モデルを対話で改良するのがゴール。
            扱う要素: 名前・人物説明・性格・状況/舞台・初回挨拶・例会話・記憶。

            大前提:
            - 通常の対話では必ず自然な日本語の文で応答する。JSON・キー名の列挙・コードブロックは一切禁止。
            - 変更したい箇所を自然な会話で引き出す。1ターンに質問は2〜3個まで。
            - ユーザーの言葉を尊重し、勝手に上書きしすぎない。

            例外:
            - ユーザーから明示的に「最終化」「この内容で」「JSON で」等の指示が来た時だけ、指定されたフォーマットの JSON のみを返す。それ以外のタイミングで JSON を出力してはならない。
        """.trimIndent()
    }
}
