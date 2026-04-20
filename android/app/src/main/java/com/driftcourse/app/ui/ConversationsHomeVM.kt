package com.driftcourse.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.driftcourse.app.net.Character
import com.driftcourse.app.net.Conversation
import com.driftcourse.app.net.DriftApi
import com.driftcourse.app.net.ensureBareCharacter
import com.driftcourse.app.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ConversationsHomeVM(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private var currentUrl = ""
    private var currentToken = ""
    private val api = DriftApi(
        baseUrlProvider = { currentUrl },
        tokenProvider = { currentToken },
    )

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    // character_id → Character。アバター表示用に一覧ロード時にまとめて取得する。
    private val _charactersById = MutableStateFlow<Map<String, Character>>(emptyMap())
    val charactersById: StateFlow<Map<String, Character>> = _charactersById.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    init {
        viewModelScope.launch {
            settings.flow.collect {
                currentUrl = it.url
                currentToken = it.token
            }
        }
    }

    fun reload() {
        viewModelScope.launch {
            refreshCfg()
            if (currentToken.isBlank()) {
                _error.value = "トークンが未設定です"
                return@launch
            }
            _loading.value = true
            _error.value = null
            try {
                _conversations.value = api.listConversations().sortedByDescending { it.updatedAt }
                // アバター表示用。characters は通常数十件程度なので 1 回の GET で OK。
                // 失敗してもメイン機能は損なわないので握り潰す (エラーは個別ログのみ)。
                try {
                    _charactersById.value = api.listCharacters().associateBy { it.id }
                } catch (t: Throwable) {
                    Log.w("ConversationsHomeVM", "listCharacters failed (avatar only)", t)
                }
            } catch (t: Throwable) {
                Log.e("ConversationsHomeVM", "listConversations failed", t)
                _error.value = t.message ?: "読み込みに失敗しました"
            } finally {
                _loading.value = false
            }
        }
    }

    fun createNew(onDone: (Conversation) -> Unit) {
        viewModelScope.launch {
            refreshCfg()
            if (currentToken.isBlank()) {
                _error.value = "トークンが未設定です"
                return@launch
            }
            _busy.value = true
            _error.value = null
            try {
                val character = api.ensureBareCharacter()
                val conv = api.createConversation(character.id)
                _conversations.value = listOf(conv) + _conversations.value
                onDone(conv)
            } catch (t: Throwable) {
                Log.e("ConversationsHomeVM", "createNew failed", t)
                _error.value = t.message ?: "作成に失敗しました"
            } finally {
                _busy.value = false
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            refreshCfg()
            _busy.value = true
            _error.value = null
            try {
                api.deleteConversation(id)
                _conversations.value = _conversations.value.filterNot { it.id == id }
            } catch (t: Throwable) {
                Log.e("ConversationsHomeVM", "delete failed", t)
                _error.value = t.message ?: "削除に失敗しました"
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun refreshCfg() {
        val cfg = settings.flow.first()
        currentUrl = cfg.url
        currentToken = cfg.token
    }
}
