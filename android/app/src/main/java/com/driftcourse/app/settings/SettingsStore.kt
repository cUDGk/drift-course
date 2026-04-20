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

    suspend fun save(url: String, token: String) {
        ds.edit { prefs ->
            prefs[KEY_URL] = url.trim().ifEmpty { DEFAULT_URL }
            prefs[KEY_TOKEN] = token.trim()
        }
    }

    companion object {
        private val KEY_URL = stringPreferencesKey("server_url")
        private val KEY_TOKEN = stringPreferencesKey("token")
    }
}
