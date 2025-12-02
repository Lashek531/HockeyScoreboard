package com.example.hockeyscoreboard.data

import android.content.Context

private const val PREFS_NAME = "hockey_prefs"
private const val KEY_CURRENT_SEASON = "current_season"
private const val DEFAULT_SEASON = "25-26"

/**
 * Текущий сезон для приложения.
 * Если ещё не задан, возвращает "25-26".
 */
fun getCurrentSeason(context: Context): String {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    return prefs.getString(KEY_CURRENT_SEASON, DEFAULT_SEASON) ?: DEFAULT_SEASON
}

/**
 * Сохранить текущий сезон в настройках.
 */
fun setCurrentSeason(context: Context, season: String) {
    val clean = season.trim()
    if (clean.isEmpty()) return
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    prefs.edit().putString(KEY_CURRENT_SEASON, clean).apply()
}
