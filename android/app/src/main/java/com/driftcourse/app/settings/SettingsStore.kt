package com.driftcourse.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ServerSettings(
    val url: String,
    val token: String,
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * アプリ背景の設定。テーマ/surface に重ねて描画するレイヤー。
 *
 * - [Default] は何も描画しない (MaterialTheme surface がそのまま見える)
 * - [Color] は ARGB 固定色
 * - [Image] は `data:image/…;base64,…` 形式。GIF はアニメ保持、JPEG/PNG はビットマップ。
 */
sealed class Background {
    object Default : Background()
    data class Color(val argb: Long) : Background()
    data class Image(val dataUrl: String) : Background()
}

private const val DEFAULT_URL = "http://192.168.1.7:8787"

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "drift_settings")

class SettingsStore(context: Context) {
    private val ds = context.applicationContext.dataStore

    val flow: Flow<ServerSettings> = ds.data.map { prefs ->
        ServerSettings(
            url = prefs[KEY_URL]?.takeIf { it.isNotBlank() } ?: DEFAULT_URL,
            token = prefs[KEY_TOKEN].orEmpty(),
        )
    }

    val themeFlow: Flow<ThemeMode> = ds.data.map { prefs ->
        when (prefs[KEY_THEME_MODE]?.lowercase()) {
            "light" -> ThemeMode.LIGHT
            "dark" -> ThemeMode.DARK
            else -> ThemeMode.SYSTEM
        }
    }

    val bgFlow: Flow<Background> = ds.data.map { prefs ->
        val kind = prefs[KEY_BG_KIND]?.lowercase()
        val value = prefs[KEY_BG_VALUE].orEmpty()
        when (kind) {
            "color" -> {
                val argb = parseArgbHex(value)
                if (argb != null) Background.Color(argb) else Background.Default
            }
            "image" -> {
                if (value.startsWith("data:image/")) Background.Image(value) else Background.Default
            }
            else -> Background.Default
        }
    }

    suspend fun save(url: String, token: String) {
        ds.edit { prefs ->
            prefs[KEY_URL] = url.trim().ifEmpty { DEFAULT_URL }
            prefs[KEY_TOKEN] = token.trim()
        }
    }

    suspend fun saveTheme(mode: ThemeMode) {
        ds.edit { prefs ->
            prefs[KEY_THEME_MODE] = when (mode) {
                ThemeMode.SYSTEM -> "system"
                ThemeMode.LIGHT -> "light"
                ThemeMode.DARK -> "dark"
            }
        }
    }

    suspend fun saveBackground(bg: Background) {
        ds.edit { prefs ->
            when (bg) {
                is Background.Default -> {
                    prefs[KEY_BG_KIND] = "default"
                    prefs.remove(KEY_BG_VALUE)
                }
                is Background.Color -> {
                    prefs[KEY_BG_KIND] = "color"
                    prefs[KEY_BG_VALUE] = argbToHex(bg.argb)
                }
                is Background.Image -> {
                    prefs[KEY_BG_KIND] = "image"
                    prefs[KEY_BG_VALUE] = bg.dataUrl
                }
            }
        }
    }

    suspend fun clearBackground() {
        ds.edit { prefs ->
            prefs[KEY_BG_KIND] = "default"
            prefs.remove(KEY_BG_VALUE)
        }
    }

    // ユーザ自身の名前 (AI が呼ぶ時に使う)。空ならサーバに送らない。
    val userNameFlow: Flow<String> = ds.data.map { it[KEY_USER_NAME].orEmpty() }

    suspend fun saveUserName(name: String) {
        ds.edit { prefs -> prefs[KEY_USER_NAME] = name.trim() }
    }

    companion object {
        private val KEY_URL = stringPreferencesKey("server_url")
        private val KEY_TOKEN = stringPreferencesKey("token")
        private val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        private val KEY_BG_KIND = stringPreferencesKey("bg_kind")
        private val KEY_BG_VALUE = stringPreferencesKey("bg_value")
        private val KEY_USER_NAME = stringPreferencesKey("user_name")
    }
}

private fun argbToHex(argb: Long): String {
    val v = argb and 0xFFFFFFFFL
    return "#%08X".format(v)
}

private fun parseArgbHex(s: String): Long? {
    val hex = s.trim().removePrefix("#")
    if (hex.length != 8) return null
    return runCatching { java.lang.Long.parseLong(hex, 16) }.getOrNull()
}
