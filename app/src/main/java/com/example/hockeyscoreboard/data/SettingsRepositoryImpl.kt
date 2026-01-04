package com.example.hockeyscoreboard.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.hockeyscoreboard.DEFAULT_BREAK_DURATION_MS
import com.example.hockeyscoreboard.DEFAULT_SHIFT_DURATION_MS


private const val PREFS_NAME = "hockey_settings"
private const val KEY_SERVER_URL = "server_url"
private const val KEY_API_KEY = "api_key"

// Длительности таймера (из интерфейса)
private const val KEY_SHIFT_DURATION_MS = "shift_duration_ms"
private const val KEY_BREAK_DURATION_MS = "break_duration_ms"

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
    suspend fun getShiftDurationMs(): Long = withContext(Dispatchers.IO) {
        prefs.getLong(KEY_SHIFT_DURATION_MS, DEFAULT_SHIFT_DURATION_MS)
    }

    suspend fun getBreakDurationMs(): Long = withContext(Dispatchers.IO) {
        prefs.getLong(KEY_BREAK_DURATION_MS, DEFAULT_BREAK_DURATION_MS)
    }

    suspend fun setShiftDurationMs(valueMs: Long) = withContext(Dispatchers.IO) {
        prefs.edit().putLong(KEY_SHIFT_DURATION_MS, valueMs).apply()
    }

    suspend fun setBreakDurationMs(valueMs: Long) = withContext(Dispatchers.IO) {
        prefs.edit().putLong(KEY_BREAK_DURATION_MS, valueMs).apply()
    }


    private val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
    private val KEY_TELEGRAM_CHAT_ID = "telegram_chat_id"
    private val KEY_TELEGRAM_BOT_CHAT_ID = "telegram_bot_chat_id"


    fun getTelegramBotToken(): String {
        val prefs = context.getSharedPreferences("hockey_prefs", Context.MODE_PRIVATE)
        return prefs.getString(KEY_TELEGRAM_BOT_TOKEN, "") ?: ""
    }

    fun setTelegramBotToken(token: String) {
        val prefs = context.getSharedPreferences("hockey_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_TELEGRAM_BOT_TOKEN, token)
            .apply()
    }

    fun getTelegramChatId(): String {
        val prefs = context.getSharedPreferences("hockey_prefs", Context.MODE_PRIVATE)
        return prefs.getString(KEY_TELEGRAM_CHAT_ID, "") ?: ""
    }

    fun setTelegramChatId(chatId: String) {
        val prefs = context.getSharedPreferences("hockey_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_TELEGRAM_CHAT_ID, chatId)
            .apply()
    }

    fun getTelegramBotChatId(): String {
        val prefs = context.getSharedPreferences("hockey_prefs", Context.MODE_PRIVATE)
        return prefs.getString(KEY_TELEGRAM_BOT_CHAT_ID, "") ?: ""
    }

    fun setTelegramBotChatId(chatId: String) {
        val prefs = context.getSharedPreferences("hockey_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_TELEGRAM_BOT_CHAT_ID, chatId)
            .apply()
    }


}
