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

data class UiMessage(
    val role: String,
    val content: String,
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val sseClient = SseClient(
        baseUrlProvider = { currentUrl },
        tokenProvider = { currentToken },
    )

    private var currentUrl: String = ""
    private var currentToken: String = ""

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val _streaming = MutableStateFlow(false)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var streamJob: Job? = null

    init {
        // 設定は送信直前に読み直すので、ここは最新値をキャッシュするだけ。
        viewModelScope.launch {
            settings.flow.collect {
                currentUrl = it.url
                currentToken = it.token
            }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _streaming.value) return

        viewModelScope.launch {
            val cfg = settings.flow.first()
            currentUrl = cfg.url
            currentToken = cfg.token
            if (currentToken.isBlank()) {
                _error.value = "token が未設定です。Settings で登録してください。"
                return@launch
            }

            val userMsg = UiMessage(role = "user", content = trimmed)
            val assistantMsg = UiMessage(role = "assistant", content = "")
            _messages.value = _messages.value + userMsg + assistantMsg
            _error.value = null

            val history = _messages.value
                .dropLast(1)  // アシスタントの空プレースホルダは送らない
                .map { ChatMessage(it.role, it.content) }

            _streaming.value = true
            streamJob = launch {
                val buf = StringBuilder()
                try {
                    sseClient.chat(history).collect { delta ->
                        buf.append(delta)
                        updateLastAssistant(buf.toString())
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "chat failed", t)
                    _error.value = t.message ?: "stream error"
                    updateLastAssistant(buf.toString() + "\n\n[error] ${t.message ?: t::class.java.simpleName}")
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

    fun clear() {
        if (_streaming.value) return
        _messages.value = emptyList()
        _error.value = null
    }

    fun dismissError() {
        _error.value = null
    }

    private fun updateLastAssistant(content: String) {
        val list = _messages.value.toMutableList()
        val idx = list.indexOfLast { it.role == "assistant" }
        if (idx < 0) return
        list[idx] = list[idx].copy(content = content)
        _messages.value = list
    }

    companion object {
        private const val TAG = "DriftChatVM"
    }
}
