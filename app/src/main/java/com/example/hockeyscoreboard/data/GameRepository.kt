package com.example.hockeyscoreboard.data

import android.content.Context
import com.example.hockeyscoreboard.model.PlayerStats
import com.example.hockeyscoreboard.model.PlayerStatsRow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Репозиторий для работы с локальными играми:
 * - папка с файлами игр
 * - сохранение JSON в файл
 * - список сохранённых игр
 * - протокол матча (чтение файла)
 * - итоговая статистика игроков (голы/передачи по всем матчам)
 */
class GameRepository(
    private val context: Context
) {

    /** Папка с файлами игр: .../Android/data/.../files/games */
    private fun getGamesDir(): File {
        val baseDir = context.getExternalFilesDir(null)
        return File(baseDir, "games")
    }

    /**
     * Сохранение JSON игры в файл с именем [fileName] в папке games.
     * Возвращает File для дальнейшей работы (история, Drive и т.д.).
     */
    fun saveGameJson(fileName: String, json: String): File {
        val dir = getGamesDir()
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        file.writeText(json, Charsets.UTF_8)
        return file
    }

    /** Список сохранённых игр (.json), отсортированный по дате (новые сверху). */
    fun listSavedGames(): List<File> {
        val dir = getGamesDir()
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles { file ->
            file.isFile && file.name.endsWith(".json", ignoreCase = true)
        }?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    /** Чтение одного файла матча и формирование текста протокола. */
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




}
