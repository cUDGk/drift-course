package com.driftcourse.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.driftcourse.app.net.Character
import com.driftcourse.app.net.DriftApi
import com.driftcourse.app.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CharacterListVM(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private var currentUrl = ""
    private var currentToken = ""
    private val api = DriftApi(
        baseUrlProvider = { currentUrl },
        tokenProvider = { currentToken },
    )

    private val _characters = MutableStateFlow<List<Character>>(emptyList())
    val characters: StateFlow<List<Character>> = _characters.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

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

    fun reload() {
        viewModelScope.launch {
            val cfg = settings.flow.first()
            currentUrl = cfg.url
            currentToken = cfg.token
            if (currentToken.isBlank()) {
                _error.value = "トークンが未設定です"
                return@launch
            }
            _loading.value = true
            _error.value = null
            try {
                _characters.value = api.listCharacters().sortedByDescending { it.updatedAt }
            } catch (t: Throwable) {
                Log.e("CharacterListVM", "listCharacters failed", t)
                _error.value = t.message ?: "読み込みに失敗しました"
            } finally {
                _loading.value = false
            }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            val cfg = settings.flow.first()
            currentUrl = cfg.url
            currentToken = cfg.token
            _error.value = null
            try {
                api.deleteCharacter(id)
                _characters.value = _characters.value.filterNot { it.id == id }
            } catch (t: Throwable) {
                Log.e("CharacterListVM", "delete failed", t)
                _error.value = t.message ?: "削除に失敗しました"
            }
        }
    }
}
