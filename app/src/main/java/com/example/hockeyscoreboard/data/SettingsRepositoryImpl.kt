package com.example.hockeyscoreboard.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFS_NAME = "hockey_settings"
private const val KEY_SERVER_URL = "server_url"
private const val KEY_API_KEY = "api_key"

// дефолтные значения — те, что у тебя сейчас реально работают
private const val DEFAULT_SERVER_URL = "https://hockey.ch73210.keenetic.pro:8443"
private const val DEFAULT_API_KEY = "3vXjhEr1YvFzgL6gO2fc_"

class SettingsRepositoryImpl(
    private val context: Context
) : SettingsRepository {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override suspend fun getServerUrl(): String = withContext(Dispatchers.IO) {
        prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    override suspend fun getApiKey(): String = withContext(Dispatchers.IO) {
        prefs.getString(KEY_API_KEY, DEFAULT_API_KEY) ?: DEFAULT_API_KEY
    }

    // на будущее: можно будет вызывать из экрана настроек
    suspend fun setServerUrl(url: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_SERVER_URL, url.trim()).apply()
    }

    suspend fun setApiKey(key: String) = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
    }
}
