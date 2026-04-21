package com.driftcourse.app.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.driftcourse.app.cache.LocalCache
import com.driftcourse.app.net.Character
import com.driftcourse.app.net.DriftApi
import com.driftcourse.app.net.isBare
import com.driftcourse.app.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class CharacterListVM(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsStore(app)
    private val cache = LocalCache(app.applicationContext)
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
        // 起動時にキャッシュを先に反映 (オフラインでも即座に表示)。
        viewModelScope.launch {
            _characters.value = cache.readCharacters()
                .filterNot { it.isBare() }
                .sortedByDescending { it.updatedAt }
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
                val fresh = api.listCharacters()
                cache.writeCharacters(fresh)
                // bare モデル (`card.bare == true`) は内部用なので一覧には出さない。
                _characters.value = fresh
                    .filterNot { it.isBare() }
                    .sortedByDescending { it.updatedAt }
            } catch (t: Throwable) {
                Log.e("CharacterListVM", "listCharacters failed", t)
                _error.value = t.message ?: "読み込みに失敗しました (キャッシュ表示中)"
            } finally {
                _loading.value = false
            }
        }
    }

    fun copy(id: String) {
        viewModelScope.launch {
            val cfg = settings.flow.first()
            currentUrl = cfg.url
            currentToken = cfg.token
            _error.value = null
            try {
                api.copyCharacter(id)
                reload()
            } catch (t: Throwable) {
                Log.e("CharacterListVM", "copy failed", t)
                _error.value = t.message ?: "複製に失敗しました"
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
                val updated = _characters.value.filterNot { it.id == id }
                _characters.value = updated
                cache.writeCharacters(updated)
            } catch (t: Throwable) {
                Log.e("CharacterListVM", "delete failed", t)
                _error.value = t.message ?: "削除に失敗しました"
            }
        }
    }
}
