package com.driftcourse.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.driftcourse.app.cache.LocalCache
import com.driftcourse.app.net.Character
import com.driftcourse.app.net.Conversation
import com.driftcourse.app.net.DriftApi
import com.driftcourse.app.net.Message
import com.driftcourse.app.net.PostMessage
import com.driftcourse.app.net.SseClient
import com.driftcourse.app.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ConversationVM(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private val cache = LocalCache(app.applicationContext)
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

    private val _conversation = MutableStateFlow<Conversation?>(null)
    val conversation: StateFlow<Conversation?> = _conversation.asStateFlow()

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ノート表示で「**<モデル名>**」ラベルを出すために character を引いておく。
    private val _characterName = MutableStateFlow("")
    val characterName: StateFlow<String> = _characterName.asStateFlow()

    // ストリーム中の大きなアバター表示用。card.icon は data URL (可 GIF)。
    private val _iconDataUrl = MutableStateFlow<String?>(null)
    val iconDataUrl: StateFlow<String?> = _iconDataUrl.asStateFlow()

    // グループチャット用。`【名前】` 話者検出 → アバター引き当てと、
    // メンバー管理シートの表示ソースとして使う。
    private val _characters = MutableStateFlow<Map<String, Character>>(emptyMap())
    val characters: StateFlow<Map<String, Character>> = _characters.asStateFlow()

    private val _allCharacters = MutableStateFlow<List<Character>>(emptyList())
    val allCharacters: StateFlow<List<Character>> = _allCharacters.asStateFlow()

    private var convId: String? = null
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
        if (convId == id && _messages.value.isNotEmpty()) return
        convId = id
        viewModelScope.launch {
            // キャッシュから先に表示 (オフラインでも履歴閲覧可能)。
            val cached = cache.readMessages(id)
            if (cached.isNotEmpty()) {
                _messages.value = cached.map { UiMessage(it.role, it.content, it.id) }
            }
            val cachedCharacters = cache.readCharacters()
            _allCharacters.value = cachedCharacters
            _characters.value = cachedCharacters.associateBy { it.id }
            val cachedChar = cachedCharacters.firstOrNull { c ->
                cache.readConversations().firstOrNull { it.id == id }?.characterId == c.id
            }
            if (cachedChar != null) {
                _characterName.value = cachedChar.name
                _iconDataUrl.value = readCardString(cachedChar.card, "icon").ifBlank { null }
            }

            refreshCfg()
            _error.value = null
            try {
                val conv = api.getConversation(id)
                _conversation.value = conv
                val msgs = api.listMessages(id)
                _messages.value = msgs.map { UiMessage(it.role, it.content, it.id) }
                cache.writeMessages(id, msgs)
                try {
                    val c = api.getCharacter(conv.characterId)
                    _characterName.value = c.name
                    _iconDataUrl.value = readCardString(c.card, "icon").ifBlank { null }
                } catch (t: Throwable) {
                    Log.w("ConversationVM", "getCharacter failed (label/avatar only)", t)
                }
                try {
                    val all = api.listCharacters()
                    _allCharacters.value = all
                    _characters.value = all.associateBy { it.id }
                    cache.writeCharacters(all)
                } catch (t: Throwable) {
                    Log.w("ConversationVM", "listCharacters failed (group avatars only)", t)
                }
            } catch (t: Throwable) {
                Log.e("ConversationVM", "load failed", t)
                // キャッシュ表示済みならエラーを出さない。ゼロからなら出す。
                if (_messages.value.isEmpty()) {
                    _error.value = t.message ?: "読み込みに失敗しました"
                }
            }
        }
    }

    fun updateMembers(members: List<String>) {
        val id = convId ?: return
        viewModelScope.launch {
            refreshCfg()
            _error.value = null
            try {
                val updated = api.updateMembers(id, members)
                _conversation.value = updated
            } catch (t: Throwable) {
                Log.e("ConversationVM", "updateMembers failed", t)
                _error.value = t.message ?: "メンバー更新に失敗しました"
            }
        }
    }

    fun send(text: String) {
        val id = convId ?: return
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _streaming.value) return
        viewModelScope.launch {
            refreshCfg()
            if (currentToken.isBlank()) {
                _error.value = "トークンが未設定です"
                return@launch
            }
            val userMsg = UiMessage("user", trimmed)
            val assistant = UiMessage("assistant", "")
            _messages.value = _messages.value + userMsg + assistant
            _error.value = null
            _streaming.value = true
            val uname = settings.userNameFlow.first().trim().ifEmpty { null }
            streamJob = launch {
                val buf = StringBuilder()
                try {
                    sse.convMessage(id, PostMessage(content = trimmed, userName = uname)).collect { delta ->
                        buf.append(delta)
                        updateLastAssistant(buf.toString())
                    }
                    // ストリーム完了後、サーバが確定した ID を取りに行く。
                    // 編集/分岐のために新メッセージに id を付けたい。
                    refreshMessageIds(id)
                } catch (t: Throwable) {
                    Log.e("ConversationVM", "stream failed", t)
                    _error.value = t.message ?: "ストリーミングに失敗しました"
                    updateLastAssistant(buf.toString() + "\n\n[エラー] ${t.message ?: t::class.java.simpleName}")
                } finally {
                    _streaming.value = false
                }
            }
        }
    }

    fun cancel() {
        streamJob?.cancel()
        _streaming.value = false
    }

    fun editMessage(msgId: String, newText: String) {
        val id = convId ?: return
        viewModelScope.launch {
            refreshCfg()
            _error.value = null
            try {
                api.updateMessage(id, msgId, newText)
                refreshMessageIds(id)
            } catch (t: Throwable) {
                Log.e("ConversationVM", "editMessage failed", t)
                _error.value = t.message ?: "編集に失敗しました"
            }
        }
    }

    /**
     * 指定 user メッセージ (msgId) の直前までを元会話から複製した新会話を作り、
     * 新会話に対して [editedText] を送って応答をストリームさせる。
     * 新会話 id は [onNavigate] へ渡し、画面遷移は UI 側に委ねる。
     */
    fun forkFrom(msgId: String, editedText: String, onNavigate: (String) -> Unit) {
        val id = convId ?: return
        val trimmed = editedText.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            refreshCfg()
            if (currentToken.isBlank()) {
                _error.value = "トークンが未設定です"
                return@launch
            }
            try {
                val newConv = api.forkConversation(id, msgId, inclusive = false)
                // 先にナビゲート。新 VM 側で load → send を期待するのではなく、
                // ここから明示的に最初のユーザ発言をストリームしに行く。
                onNavigate(newConv.id)
                // convId は画面が新 VM を作って load するので触らない。
                // 以下の stream は「新会話宛」に直接叩く。ストリーム完了時点では
                // UI の messages state は新会話側 VM が別途所有する。
                val uname = settings.userNameFlow.first().trim().ifEmpty { null }
                sse.convMessage(newConv.id, PostMessage(content = trimmed, userName = uname)).collect { /* ignore deltas */ }
            } catch (t: Throwable) {
                Log.e("ConversationVM", "forkFrom failed", t)
                _error.value = t.message ?: "分岐に失敗しました"
            }
        }
    }

    private suspend fun refreshMessageIds(id: String) {
        try {
            val fresh = api.listMessages(id)
            _messages.value = fresh.map { UiMessage(it.role, it.content, it.id) }
            cache.writeMessages(id, fresh)
        } catch (t: Throwable) {
            Log.w("ConversationVM", "refreshMessageIds failed", t)
        }
    }

    private fun updateLastAssistant(content: String) {
        val list = _messages.value.toMutableList()
        val idx = list.indexOfLast { it.role == "assistant" }
        if (idx < 0) return
        list[idx] = list[idx].copy(content = content)
        _messages.value = list
    }

    private suspend fun refreshCfg() {
        val cfg = settings.flow.first()
        currentUrl = cfg.url
        currentToken = cfg.token
    }
}
