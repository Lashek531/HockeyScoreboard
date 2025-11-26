package com.example.hockeyscoreboard.data

import android.content.Context
import com.example.hockeyscoreboard.model.PlayerStats
import com.example.hockeyscoreboard.model.PlayerStatsRow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Папка с файлами игр
fun getGamesDir(context: Context): File {
    val baseDir = context.getExternalFilesDir(null)
    return File(baseDir, "HockeyScoreboard")
}

// Список сохранённых игр (.json), отсортированный по дате (новые сверху)
fun listSavedGames(context: Context): List<File> {
    val dir = getGamesDir(context)
    if (!dir.exists() || !dir.isDirectory) return emptyList()
    return dir.listFiles { file ->
        file.isFile && file.name.endsWith(".json", ignoreCase = true)
    }?.sortedByDescending { it.lastModified() } ?: emptyList()
}

// Чтение одного файла матча и формирование текста протокола
fun loadHistoryDetails(file: File): String {
    return try {
        val text = file.readText(Charsets.UTF_8)
        val root = JSONObject(text)

        val gameId = root.optString("gameId", file.name)
        val arena = root.optString("arena", "")
        val date = root.optString("date", "")

        val teams = root.optJSONObject("teams")
        val redObj = teams?.optJSONObject("RED")
        val whiteObj = teams?.optJSONObject("WHITE")

        val redName = redObj?.optString("name", "Красные") ?: "Красные"
        val whiteName = whiteObj?.optString("name", "Белые") ?: "Белые"

        val redPlayersJson = redObj?.optJSONArray("players") ?: JSONArray()
        val whitePlayersJson = whiteObj?.optJSONArray("players") ?: JSONArray()

        val redPlayers = (0 until redPlayersJson.length()).map { i ->
            redPlayersJson.optString(i)
        }
        val whitePlayers = (0 until whitePlayersJson.length()).map { i ->
            whitePlayersJson.optString(i)
        }

        val finalScore = root.optJSONObject("finalScore")
        val redScoreSaved = finalScore?.optInt("RED", 0) ?: 0
        val whiteScoreSaved = finalScore?.optInt("WHITE", 0) ?: 0

        val goalsJson = root.optJSONArray("goals") ?: JSONArray()
        val goalsList = (0 until goalsJson.length()).map { i ->
            val obj = goalsJson.optJSONObject(i)
            val idx = obj.optInt("index", i + 1)
            val team = obj.optString("team", "")
            val scoreAfter = obj.optString("scoreAfter", "")
            val scorer = obj.optString("scorer", "")
            val a1 = obj.optString("assist1", "")
            val a2 = obj.optString("assist2", "")
            val assists = listOf(a1, a2).filter { it.isNotBlank() }
            val teamName = if (team == "RED") redName else whiteName
            val playersText =
                if (assists.isEmpty()) scorer
                else "$scorer (${assists.joinToString(", ")})"
            "$idx. $scoreAfter  $teamName — $playersText"
        }

        buildString {
            appendLine("Игра: $gameId")
            if (date.isNotBlank()) appendLine("Дата: $date")
            if (arena.isNotBlank()) appendLine("Арена: $arena")
            appendLine()
            appendLine("Счёт: $redName $redScoreSaved : $whiteScoreSaved $whiteName")
            appendLine()
            appendLine("$redName (игроки):")
            redPlayers.forEach { appendLine("  • $it") }
            appendLine()
            appendLine("$whiteName (игроки):")
            whitePlayers.forEach { appendLine("  • $it") }
            appendLine()
            appendLine("Голы по ходу матча:")
            if (goalsList.isEmpty()) {
                appendLine("  (голов нет)")
            } else {
                goalsList.forEach { appendLine("  $it") }
            }
        }
    } catch (e: Exception) {
        "Не удалось прочитать файл ${file.name}: ${e.message}"
    }
}

// Сбор статистики по всем сохранённым играм
fun collectPlayerStats(context: Context): Map<String, PlayerStats> {
    val result = mutableMapOf<String, PlayerStats>()
    val files = listSavedGames(context)
    for (file in files) {
        try {
            val text = file.readText(Charsets.UTF_8)
            val root = JSONObject(text)

            val teams = root.optJSONObject("teams")
            val redObj = teams?.optJSONObject("RED")
            val whiteObj = teams?.optJSONObject("WHITE")

            val redPlayersJson = redObj?.optJSONArray("players") ?: JSONArray()
            val whitePlayersJson = whiteObj?.optJSONArray("players") ?: JSONArray()

            val playersInGame = mutableSetOf<String>()
            for (i in 0 until redPlayersJson.length()) {
                val name = redPlayersJson.optString(i).trim()
                if (name.isNotEmpty()) playersInGame += name
            }
            for (i in 0 until whitePlayersJson.length()) {
                val name = whitePlayersJson.optString(i).trim()
                if (name.isNotEmpty()) playersInGame += name
            }

            for (name in playersInGame) {
                val stats = result.getOrPut(name) { PlayerStats() }
                stats.games++
            }

            val goalsJson = root.optJSONArray("goals") ?: JSONArray()
            for (i in 0 until goalsJson.length()) {
                val obj = goalsJson.optJSONObject(i)
                val scorer = obj.optString("scorer", "").trim()
                val a1 = obj.optString("assist1", "").trim()
                val a2 = obj.optString("assist2", "").trim()

                if (scorer.isNotEmpty()) {
                    val stats = result.getOrPut(scorer) { PlayerStats() }
                    stats.goals++
                }
                if (a1.isNotEmpty()) {
                    val stats = result.getOrPut(a1) { PlayerStats() }
                    stats.assists++
                }
                if (a2.isNotEmpty()) {
                    val stats = result.getOrPut(a2) { PlayerStats() }
                    stats.assists++
                }
            }
        } catch (_: Exception) {
            // пропускаем битый файл
        }
    }
    return result
}

// Построение таблицы "Лучшие снайперы"
fun buildTopScorersRows(stats: Map<String, PlayerStats>): List<PlayerStatsRow> {
    val list = stats.entries
        .filter { it.value.goals > 0 }
        .sortedWith(
            compareByDescending<Map.Entry<String, PlayerStats>> { it.value.goals }
                .thenByDescending { it.value.points }
                .thenBy { it.key }
        )
    return list.mapIndexed { index, (name, st) ->
        PlayerStatsRow(
            rank = index + 1,
            name = name,
            games = st.games,
            goals = st.goals,
            assists = st.assists,
            points = st.points
        )
    }
}



// Построение таблицы "Лучшие бомбардиры"
fun buildTopBombersRows(stats: Map<String, PlayerStats>): List<PlayerStatsRow> {
    val list = stats.entries
        .filter { it.value.points > 0 }
        .sortedWith(
            compareByDescending<Map.Entry<String, PlayerStats>> { it.value.points }
                .thenByDescending { it.value.goals }
                .thenBy { it.key }
        )
    return list.mapIndexed { index, (name, st) ->
        PlayerStatsRow(
            rank = index + 1,
            name = name,
            games = st.games,
            goals = st.goals,
            assists = st.assists,
            points = st.points
        )
    }
}
