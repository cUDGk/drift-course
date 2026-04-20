package com.driftcourse.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.driftcourse.app.net.ChatMessage
import com.driftcourse.app.net.SseClient
import com.driftcourse.app.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * キャラ設計 AI の会話状態を持つ。サーバには何も永続化しない完全にエフェメラルなチャット。
 * system プロンプトは可視バブルに出さず、送信時だけ履歴の先頭に差し込む。
 */
class CharacterCreateChatVM(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val sseClient = SseClient(
        baseUrlProvider = { currentUrl },
        tokenProvider = { currentToken },
    )
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private var currentUrl: String = ""
    private var currentToken: String = ""

    // 可視バブルのみ保持（system は含めない）
    private val _messages = MutableStateFlow<List<UiMessage>>(
        listOf(UiMessage(role = "assistant", content = OPENING_LINE)),
    )
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _finalizing = MutableStateFlow(false)
    val finalizing: StateFlow<Boolean> = _finalizing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // 最終化完了を画面に伝える。Screen 側で収集してナビゲートする。
    private val _finalized = MutableStateFlow<CharacterDraft?>(null)
    val finalized: StateFlow<CharacterDraft?> = _finalized.asStateFlow()

    private var streamJob: Job? = null

    init {
        viewModelScope.launch {
            settings.flow.collect {
                currentUrl = it.url
                currentToken = it.token
            }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _streaming.value || _finalizing.value) return

        viewModelScope.launch {
            val cfg = settings.flow.first()
            currentUrl = cfg.url
            currentToken = cfg.token
            if (currentToken.isBlank()) {
                _error.value = "トークンが未設定です。設定画面から登録してください。"
                return@launch
            }

            val userMsg = UiMessage(role = "user", content = trimmed)
            val assistantMsg = UiMessage(role = "assistant", content = "")
            _messages.value = _messages.value + userMsg + assistantMsg
            _error.value = null

            val history = buildHistoryForSend(includeLastAssistantPlaceholder = false)
            runStream(history)
        }
    }

    fun finalize() {
        if (_streaming.value || _finalizing.value) return
        val visible = _messages.value
        // アシスタントの最初の呼び水以外に実発言が無い状態で最終化させない
        if (visible.none { it.role == "user" }) {
            _error.value = "もう少し対話を進めてから最終化してください。"
            return
        }

        viewModelScope.launch {
            val cfg = settings.flow.first()
            currentUrl = cfg.url
            currentToken = cfg.token
            if (currentToken.isBlank()) {
                _error.value = "トークンが未設定です。設定画面から登録してください。"
                return@launch
            }

            _error.value = null
            _finalizing.value = true

            // 不可視の最終化指示。UI バブルには出さないが、サーバへ送る履歴には含める。
            val historyForServer = buildList {
                add(ChatMessage("system", SYSTEM_PROMPT))
                visible.forEach { add(ChatMessage(it.role, it.content)) }
                add(ChatMessage("user", FINALIZE_INSTRUCTION))
            }

            _streaming.value = true
            streamJob = launch {
                val buf = StringBuilder()
                try {
                    sseClient.chat(historyForServer).collect { delta ->
                        buf.append(delta)
                    }
                    val parsed = parseDraft(buf.toString())
                    if (parsed == null) {
                        _error.value = "JSON の抽出に失敗しました。もう一度お試しください。"
                    } else {
                        _finalized.value = parsed
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

    fun dismissError() {
        _error.value = null
    }

    fun consumeFinalized() {
        _finalized.value = null
    }

    private fun runStream(history: List<ChatMessage>) {
        _streaming.value = true
        streamJob = viewModelScope.launch {
            val buf = StringBuilder()
            try {
                sseClient.chat(history).collect { delta ->
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

    private fun buildHistoryForSend(includeLastAssistantPlaceholder: Boolean): List<ChatMessage> {
        val visible = _messages.value
        val effective = if (includeLastAssistantPlaceholder) visible else visible.dropLast(1)
        return buildList {
            add(ChatMessage("system", SYSTEM_PROMPT))
            effective.forEach { add(ChatMessage(it.role, it.content)) }
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
        // まず素直にそのまま試す
        runCatching {
            return json.decodeFromString(CharacterDraft.serializer(), trimmed)
        }
        // 許容抽出: 最初の `{` から最後の `}` までを切り出して再試行
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

    companion object {
        private const val TAG = "CharCreateChatVM"

        private const val OPENING_LINE =
            "どんなモデルを作りましょうか？ 名前・性格・舞台設定など、思いつく範囲で自由に教えてください。まだ決まってない部分はあとで一緒に詰めていきましょう。"

        private const val FINALIZE_INSTRUCTION =
            "ここまでの内容を以下の JSON だけで返してください。前置き・説明・``` コードフェンス・" +
                "『はい、わかりました』のような返事は一切禁止。キー: " +
                "name / description / personality / scenario / first_mes / mes_example / memory。" +
                "値は日本語の文字列で、未決定の項目は空文字にしてください。"

        private val SYSTEM_PROMPT = """
            あなたはキャラクター設計アシスタントです。ユーザーと自然な日本語で対話し、以下の要素を引き出すのがゴール:
            名前・人物説明・性格・状況/舞台・初回挨拶・例会話・覚えておきたい事実 (記憶)。

            大前提:
            - 通常の対話では必ず自然な日本語の文で応答する。JSON・キー名の列挙・コードブロックは一切禁止。
            - ユーザーの発言を尊重し、こちらで勝手に設定を足さない。1ターンに質問は2〜3個まで。
            - 不足している要素があれば、自然な雑談のように 1〜2 個ずつ聞いていく。

            例外:
            - ユーザーから明示的に「最終化」「この内容で」「JSON で」等の指示が来た時だけ、指定されたフォーマットの JSON のみを返す。それ以外のタイミングで JSON を出力してはならない。
        """.trimIndent()
    }
}
