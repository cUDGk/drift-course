package com.driftcourse.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.driftcourse.app.net.DriftApi
import com.driftcourse.app.net.MemorySnapshot
import com.driftcourse.app.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MemoryVM(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private var currentUrl = ""
    private var currentToken = ""
    private val api = DriftApi(
        baseUrlProvider = { currentUrl },
        tokenProvider = { currentToken },
    )

    private val _memory = MutableStateFlow(MemorySnapshot())
    val memory: StateFlow<MemorySnapshot> = _memory.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private var convId: String? = null

    init {
        viewModelScope.launch {
            settings.flow.collect {
                currentUrl = it.url
                currentToken = it.token
            }
        }
    }

    fun load(id: String) {
        convId = id
        viewModelScope.launch {
            refreshCfg()
            _busy.value = true
            _status.value = null
            try {
                _memory.value = api.getMemory(id)
            } catch (t: Throwable) {
                Log.e("MemoryVM", "load failed", t)
                _status.value = "load failed: ${t.message}"
            } finally {
                _busy.value = false
            }
        }
    }

    fun save(layer: String, content: String) {
        val id = convId ?: return
        viewModelScope.launch {
            refreshCfg()
            _busy.value = true
            _status.value = null
            try {
                api.patchMemory(id, layer, content)
                _memory.value = when (layer) {
                    "char" -> _memory.value.copy(char = content)
                    "recent" -> _memory.value.copy(recent = content)
                    "mid" -> _memory.value.copy(mid = content)
                    "long" -> _memory.value.copy(long = content)
                    else -> _memory.value
                }
                _status.value = "$layer saved"
            } catch (t: Throwable) {
                Log.e("MemoryVM", "save failed", t)
                _status.value = "save failed: ${t.message}"
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
