package com.example.hockeyscoreboard.data

import android.content.SharedPreferences
import com.example.hockeyscoreboard.model.PlayerInfo
import com.example.hockeyscoreboard.model.PlayerRole
import org.json.JSONArray
import org.json.JSONObject

// Ключ для SharedPreferences
const val PREF_PLAYERS_META = "players_meta_json"
// Ключ для хранения внешнего EventID текущей активной игры
const val PREF_ACTIVE_EVENT_ID = "active_event_id"

// Базовый список игроков по умолчанию
private val DefaultBasePlayers = listOf(
    "Чижик Сергей",
    "Чижик Тимофей",
    "Асатуров Александр",
    "Манайкин Николай",
    "Бенгин Александр",
    "Черепков Александр",
    "Крощинский Владимир",
    "Павликов Вадим",
    "Павликов Сергей",
    "Павликов Олег",
    "Борисов Василий",
    "Суворов Влад",
    "Майоров Денис",
    "Лукьянов Дмитрий",
    "Горбачев Евгений",
    "Мезенов Евгений",
    "Зеленкин Сергей",
    "Матвейчук Игорь",
    "Антонов Кирилл",
    "Лаврухин Сергей",
    "Третьяков Лев",
    "Кошелченков Леонид",
    "Куликов Алексей",
    "Кустов Макс",
    "Братанов Михаил",
    "Плюснин Михаил",
    "Опадчий Никита",
    "Алексеев Глеб",
    "Манайкин Николай",
    "Мутьков Николай",
    "Беспалов Сергей",
    "Коптевский Юрий",
    "Русов Юрий",
    "Нестеров Павел",
    "Волков Дмитрий",
    "Вихнов Макс",
    "Вавилов Илья"
)

/** Загрузка базового списка игроков из SharedPreferences (JSON) или из DefaultBasePlayers. */
fun loadBasePlayers(prefs: SharedPreferences): List<PlayerInfo> {
    val raw = prefs.getString(PREF_PLAYERS_META, null)
    if (raw.isNullOrBlank()) {
        // Первый запуск – строим список из зашитых имён
        return DefaultBasePlayers
            .distinct()
            .sorted()
            .map { PlayerInfo(name = it) }
    }
    return try {
        val arr = JSONArray(raw)
        val result = mutableListOf<PlayerInfo>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            if (name.isEmpty()) continue

            val roleStr = obj.optString("role", PlayerRole.UNIVERSAL.name)
            val role = runCatching { PlayerRole.valueOf(roleStr) }
                .getOrDefault(PlayerRole.UNIVERSAL)

            val rating = obj.optInt("rating", 0).coerceIn(0, 999)

            // Читаем userId как строку, независимо от того, было оно числом или строкой
            val userId: String? = when {
                obj.has("userId") -> {
                    // наш новый формат: строковое поле "userId"
                    obj.optString("userId").trim().ifEmpty { null }
                }
                obj.has("user_id") -> {
                    // на будущее: если вдруг когда-то сохраняли с таким именем
                    obj.optString("user_id").trim().ifEmpty { null }
                }
                else -> null
            }

            result += PlayerInfo(
                name = name,
                role = role,
                rating = rating,
                userId = userId
            )
        }
        if (result.isEmpty()) {
            DefaultBasePlayers
                .distinct()
                .sorted()
                .map { PlayerInfo(name = it) }
        } else {
            result.sortedBy { it.name }
        }
    } catch (_: Exception) {
        DefaultBasePlayers
            .distinct()
            .sorted()
            .map { PlayerInfo(name = it) }
    }
}

/** Получить внешний EventID текущей активной игры (или null, если не задан). */
fun getActiveEventId(prefs: SharedPreferences): String? =
    prefs.getString(PREF_ACTIVE_EVENT_ID, null)

/** Установить/очистить внешний EventID текущей активной игры. */
fun setActiveEventId(prefs: SharedPreferences, value: String?) {
    prefs.edit().apply {
        if (value == null) {
            remove(PREF_ACTIVE_EVENT_ID)
        } else {
            putString(PREF_ACTIVE_EVENT_ID, value)
        }
    }.apply()
}



/** Сохранение базового списка игроков в SharedPreferences (JSON). */
fun saveBasePlayers(prefs: SharedPreferences, players: List<PlayerInfo>) {
    val arr = JSONArray()
    players.sortedBy { it.name }.forEach { p ->
        val obj = JSONObject()
        obj.put("name", p.name)
        obj.put("role", p.role.name)
        obj.put("rating", p.rating)
        // Сохраняем userId, если он есть
        p.userId?.let { id ->
            obj.put("userId", id)
        }
        arr.put(obj)
    }
    prefs.edit().putString(PREF_PLAYERS_META, arr.toString()).apply()
}
