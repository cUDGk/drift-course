package com.driftcourse.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.driftcourse.app.net.Character
import com.driftcourse.app.net.Conversation
import com.driftcourse.app.net.DriftApi
import com.driftcourse.app.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ModelDetailVM(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private var currentUrl = ""
    private var currentToken = ""
    private val api = DriftApi(
        baseUrlProvider = { currentUrl },
        tokenProvider = { currentToken },
    )

    private val _character = MutableStateFlow<Character?>(null)
    val character: StateFlow<Character?> = _character.asStateFlow()

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        viewModelScope.launch {
            settings.flow.collect {
                currentUrl = it.url
                currentToken = it.token
            }
        }
    }

    fun load(id: String) {
        viewModelScope.launch {
            refreshCfg()
            _busy.value = true
            _error.value = null
            try {
                _character.value = api.getCharacter(id)
                _conversations.value = api.listConversations(id).sortedByDescending { it.updatedAt }
            } catch (t: Throwable) {
                Log.e("ModelDetailVM", "load failed", t)
                _error.value = t.message ?: "読み込みに失敗しました"
            } finally {
                _busy.value = false
            }
        }
    }

    fun newConversation(id: String, onDone: (Conversation) -> Unit) {
        viewModelScope.launch {
            refreshCfg()
            _busy.value = true
            _error.value = null
            try {
                val created = api.createConversation(id)
                _conversations.value = listOf(created) + _conversations.value
                onDone(created)
            } catch (t: Throwable) {
                Log.e("ModelDetailVM", "newConversation failed", t)
                _error.value = t.message ?: "対話の作成に失敗しました"
            } finally {
                _busy.value = false
            }
        }
    }

    fun deleteConversation(convId: String) {
        viewModelScope.launch {
            refreshCfg()
            _busy.value = true
            _error.value = null
            try {
                api.deleteConversation(convId)
                _conversations.value = _conversations.value.filterNot { it.id == convId }
            } catch (t: Throwable) {
                Log.e("ModelDetailVM", "deleteConversation failed", t)
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
