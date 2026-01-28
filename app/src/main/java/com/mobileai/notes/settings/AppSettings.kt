package com.mobileai.notes.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class HostSettings(
    val baseUrl: String,
) {
    companion object {
        val Default = HostSettings(baseUrl = "https://api.mock-edu.com")
    }
}

class AppSettings(private val context: Context) {
    private val keyHostBaseUrl = stringPreferencesKey("host_base_url")

    val hostSettings: Flow<HostSettings> =
        context.dataStore.data.map { prefs ->
            HostSettings(
                baseUrl = prefs[keyHostBaseUrl] ?: HostSettings.Default.baseUrl,
            )
        }

    suspend fun setHostBaseUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[keyHostBaseUrl] = url.trim()
        }
    }

    companion object {
        fun create(context: Context): AppSettings = AppSettings(context.applicationContext)
    }
}

