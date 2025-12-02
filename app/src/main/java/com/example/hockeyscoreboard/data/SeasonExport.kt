package com.example.hockeyscoreboard.data

import android.content.Context
import com.example.hockeyscoreboard.data.db.GameDao
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.text.Charsets

/**
 * Построение сезонных JSON-файлов:
 *  - finished/<season>/index.json
 *  - stats/<season>/players.json
 *
 * Эти функции НИЧЕГО не шлют на Raspi/Drive — только собирают строки JSON
 * и, при необходимости, сохраняют их в файлы.
 */

/**
 * Формат даты без таймзоны, как в game-json (yyyy-MM-dd'T'HH:mm:ss)
 */
private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

// ===== Вспомогательные директории на устройстве =====

/**
 * Корень для структуры, аналогичной Raspi:
 *
 * <externalFilesDir>/hockey-json/
 *   finished/<season>/index.json
 *   stats/<season>/players.json
 */
private fun getHockeyJsonRootDir(context: Context): File {
    val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
    return File(baseDir, "hockey-json")
}

private fun getFinishedSeasonDir(context: Context, season: String): File {
    return File(getHockeyJsonRootDir(context), "finished/$season")
}

private fun getStatsSeasonDir(context: Context, season: String): File {
    return File(getHockeyJsonRootDir(context), "stats/$season")
}

// ===== 1. finished/<season>/index.json =====

/**
 * Собирает JSON индекса завершённых игр сезона.
 *
 * Формат:
 * {
 *   "season": "25-26",
 *   "updatedAt": "2025-12-01T12:34:56",
 *   "games": [
 *     {
 *       "id": "25-26-2025-11-30-001",
 *       "date": "2025-11-30T18:30:00",
 *       "arena": "Пестово Арена",
 *       "teamRed": "Красные",
 *       "teamWhite": "Белые",
 *       "scoreRed": 5,
 *       "scoreWhite": 3,
 *       "file": "finished/25-26/2025-11-30-001.json"
 *     },
 *     ...
 *   ]
 * }
 *
 * Основано на:
 *  - записях в Room (GameEntry),
 *  - соответствующих json-файлах игр на диске.
 */
fun buildSeasonFinishedIndexJson(
    season: String,
    gameDao: GameDao
): String {
    val allGames = gameDao.getAllGames()
    val seasonGames = allGames
        .filter { it.season == season }
        .sortedBy { it.startedAt } // от старых к новым

    val gamesArray = JSONArray()

    for (entry in seasonGames) {
        val path = entry.localPath ?: continue
        val file = File(path)
        if (!file.exists()) continue

        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
        val root = runCatching { JSONObject(text) }.getOrNull() ?: continue

        // id игры: из json.id, иначе gameId из БД
        val id = root.optString("id", entry.gameId)

        // дата: из json.date, иначе по startedAt
        val dateStr = root.optString("date", null)
        val date = dateStr ?: isoFormat.format(Date(entry.startedAt))

        // арена
        val arena = root.optString("arena", "Пестово Арена")

        // названия команд
        val teamsObj = root.optJSONObject("teams")
        val redName = teamsObj
            ?.optJSONObject("RED")
            ?.optString("name", "Красные")
            ?: "Красные"
        val whiteName = teamsObj
            ?.optJSONObject("WHITE")
            ?.optString("name", "Белые")
            ?: "Белые"

        // счёт
        val finalScoreObj = root.optJSONObject("finalScore")
        val scoreRed = finalScoreObj?.optInt("RED", entry.redScore) ?: entry.redScore
        val scoreWhite = finalScoreObj?.optInt("WHITE", entry.whiteScore) ?: entry.whiteScore

        // путь к файлу в структуре Raspi
        val fileRel = "finished/$season/${file.name}"

        val gameObj = JSONObject().apply {
            put("id", id)
            put("date", date)
            put("arena", arena)
            put("teamRed", redName)
            put("teamWhite", whiteName)
            put("scoreRed", scoreRed)
            put("scoreWhite", scoreWhite)
            put("file", fileRel)
        }

        gamesArray.put(gameObj)
    }

    val rootIndex = JSONObject().apply {
        put("season", season)
        put("updatedAt", isoFormat.format(Date()))
        put("games", gamesArray)
    }

    return rootIndex.toString(2)
}

/**
 * Строит finished/<season>/index.json и сохраняет его в файл на устройстве.
 *
 * Возвращает File на записанный index.json.
 */
fun writeSeasonFinishedIndexFile(
    context: Context,
    season: String,
    gameDao: GameDao
): File {
    val json = buildSeasonFinishedIndexJson(season, gameDao)
    val dir = getFinishedSeasonDir(context, season)
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, "index.json")
    file.writeText(json, Charsets.UTF_8)
    return file
}

// ===== 2. stats/<season>/players.json =====

/**
 * Внутренняя структура для накопления статистики по игроку за сезон.
 */
private data class SeasonPlayerAgg(
    val name: String,
    var games: Int = 0,
    var wins: Int = 0,
    var draws: Int = 0,
    var losses: Int = 0,
    var goals: Int = 0,
    var assists: Int = 0,
    var lastTeam: String? = null,     // "Красные"/"Белые" или кастомное имя из json
    var lastGameMillis: Long = 0L
)

/**
 * Собирает JSON статистики игроков по сезону.
 *
 * Формат:
 * {
 *   "season": "25-26",
 *   "updatedAt": "2025-12-01T12:34:56",
 *   "players": [
 *     {
 *       "name": "Игрок 1",
 *       "games": 25,
 *       "wins": 17,
 *       "draws": 3,
 *       "losses": 5,
 *       "goals": 30,
 *       "assists": 18,
 *       "points": 48,
 *       "lastTeam": "Красные",
 *       "lastGameDate": "2025-11-30T18:30:00"
 *     },
 *     ...
 *   ]
 * }
 */
fun buildSeasonPlayersStatsJson(
    season: String,
    gameDao: GameDao
): String {
    val allGames = gameDao.getAllGames()
    val seasonGames = allGames
        .filter { it.season == season }
        .sortedBy { it.startedAt }

    val players = mutableMapOf<String, SeasonPlayerAgg>()

    for (entry in seasonGames) {
        val path = entry.localPath ?: continue
        val file = File(path)
        if (!file.exists()) continue

        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: continue
        val root = runCatching { JSONObject(text) }.getOrNull() ?: continue

        // Проверяем, завершён ли матч
        // (если нет поля finished, считаем, что завершён — старые файлы).
        val finished = root.optBoolean("finished", true)
        if (!finished) continue

        // --- общие данные игры ---

        val dateStr = root.optString("date", null)
        val gameMillis: Long = runCatching {
            if (!dateStr.isNullOrBlank()) {
                isoFormat.parse(dateStr)?.time
            } else null
        }.getOrNull() ?: entry.startedAt

        val teamsObj = root.optJSONObject("teams") ?: continue

        val redObj = teamsObj.optJSONObject("RED")
        val whiteObj = teamsObj.optJSONObject("WHITE")

        val redName = redObj?.optString("name", "Красные") ?: "Красные"
        val whiteName = whiteObj?.optString("name", "Белые") ?: "Белые"

        val redPlayersJson = redObj?.optJSONArray("players")
        val whitePlayersJson = whiteObj?.optJSONArray("players")

        val redPlayers = mutableListOf<String>()
        if (redPlayersJson != null) {
            for (i in 0 until redPlayersJson.length()) {
                val name = redPlayersJson.optString(i).trim()
                if (name.isNotEmpty()) redPlayers += name
            }
        }

        val whitePlayers = mutableListOf<String>()
        if (whitePlayersJson != null) {
            for (i in 0 until whitePlayersJson.length()) {
                val name = whitePlayersJson.optString(i).trim()
                if (name.isNotEmpty()) whitePlayers += name
            }
        }

        if (redPlayers.isEmpty() && whitePlayers.isEmpty()) {
            // странный матч без игроков — пропускаем
            continue
        }

        val finalScoreObj = root.optJSONObject("finalScore")
        val scoreRed = finalScoreObj?.optInt("RED", entry.redScore) ?: entry.redScore
        val scoreWhite = finalScoreObj?.optInt("WHITE", entry.whiteScore) ?: entry.whiteScore

        val outcomeRed: Int = when {
            scoreRed > scoreWhite -> +1   // победа
            scoreRed < scoreWhite -> -1   // поражение
            else -> 0                     // ничья
        }

        // --- учёт игр + W/D/L + lastTeam/lastGameDate ---

        fun ensurePlayer(name: String): SeasonPlayerAgg =
            players.getOrPut(name) { SeasonPlayerAgg(name = name) }

        // Красные
        redPlayers.forEach { name ->
            val p = ensurePlayer(name)
            p.games++

            when (outcomeRed) {
                +1 -> p.wins++
                -1 -> p.losses++
                0 -> p.draws++
            }

            if (gameMillis >= p.lastGameMillis) {
                p.lastGameMillis = gameMillis
                p.lastTeam = redName
            }
        }

        // Белые
        whitePlayers.forEach { name ->
            val p = ensurePlayer(name)
            p.games++

            when (outcomeRed) {
                +1 -> p.losses++   // красные выиграли → белые проиграли
                -1 -> p.wins++     // красные проиграли → белые выиграли
                0 -> p.draws++
            }

            if (gameMillis >= p.lastGameMillis) {
                p.lastGameMillis = gameMillis
                p.lastTeam = whiteName
            }
        }

        // --- голы и передачи ---

        val goalsArray = root.optJSONArray("goals")
        if (goalsArray != null) {
            for (i in 0 until goalsArray.length()) {
                val g = goalsArray.optJSONObject(i) ?: continue
                val scorer = g.optString("scorer", "").trim()
                val a1 = g.optString("assist1", "").trim()
                val a2 = g.optString("assist2", "").trim()

                if (scorer.isNotEmpty()) {
                    val p = ensurePlayer(scorer)
                    p.goals++
                }
                if (a1.isNotEmpty()) {
                    val p = ensurePlayer(a1)
                    p.assists++
                }
                if (a2.isNotEmpty()) {
                    val p = ensurePlayer(a2)
                    p.assists++
                }
            }
        }
    }

    // --- формируем выходной JSON ---

    val playersArray = JSONArray()

    // сортировка: по очкам, потом по голам, потом по имени
    val sorted = players.values.sortedWith(
        compareByDescending<SeasonPlayerAgg> { it.goals + it.assists }
            .thenByDescending { it.goals }
            .thenBy { it.name }
    )

    for (p in sorted) {
        val points = p.goals + p.assists
        val obj = JSONObject().apply {
            put("name", p.name)
            put("games", p.games)
            put("wins", p.wins)
            put("draws", p.draws)
            put("losses", p.losses)
            put("goals", p.goals)
            put("assists", p.assists)
            put("points", points)
            p.lastTeam?.let { put("lastTeam", it) }
            if (p.lastGameMillis > 0L) {
                put("lastGameDate", isoFormat.format(Date(p.lastGameMillis)))
            }
        }
        playersArray.put(obj)
    }

    val rootStats = JSONObject().apply {
        put("season", season)
        put("updatedAt", isoFormat.format(Date()))
        put("players", playersArray)
    }

    return rootStats.toString(2)
}

/**
 * Строит stats/<season>/players.json и сохраняет его в файл на устройстве.
 *
 * Возвращает File на записанный players.json.
 */
fun writeSeasonPlayersStatsFile(
    context: Context,
    season: String,
    gameDao: GameDao
): File {
    val json = buildSeasonPlayersStatsJson(season, gameDao)
    val dir = getStatsSeasonDir(context, season)
    if (!dir.exists()) dir.mkdirs()
    val file = File(dir, "players.json")
    file.writeText(json, Charsets.UTF_8)
    return file
}
