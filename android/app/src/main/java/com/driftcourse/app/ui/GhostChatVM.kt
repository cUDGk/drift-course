package com.driftcourse.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.driftcourse.app.net.Character
import com.driftcourse.app.net.ChatMessage
import com.driftcourse.app.net.DriftApi
import com.driftcourse.app.net.SseClient
import com.driftcourse.app.net.composeCharacterSystem
import com.driftcourse.app.settings.SettingsStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ゴーストモード: サーバに何も書かない単発の対話。system はクライアント側で合成し、
 * [SseClient.chat] の stateless エンドポイントに投げる。
 */
class GhostChatVM(app: Application) : AndroidViewModel(app) {
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

    private val _character = MutableStateFlow<Character?>(null)
    val character: StateFlow<Character?> = _character.asStateFlow()

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var modelId: String? = null
    private var systemText: String = ""
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
                systemText = composeCharacterSystem(c)
            } catch (t: Throwable) {
                Log.e("GhostChatVM", "load failed", t)
                _error.value = t.message ?: "読み込みに失敗しました"
            }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _streaming.value) return
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
                if (systemText.isNotEmpty()) add(ChatMessage("system", systemText))
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
                    Log.e("GhostChatVM", "stream failed", t)
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
