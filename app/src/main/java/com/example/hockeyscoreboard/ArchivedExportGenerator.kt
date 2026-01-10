package com.example.hockeyscoreboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import com.example.hockeyscoreboard.model.PlayerInfo




data class GeneratedExport(
    val eventId: Int,
    val exportFile: File
)

object ArchivedExportGenerator {

    /**
     * Генерация result_<eventId>.json из finished-game JSON.
     *
     * @param finishedGameFile файл вида finished/<season>/<gameId>.json
     * @param basePlayers список базовых игроков (для маппинга name -> user_id)
     * @param eventIdOverride если задан — используется вместо externalEventId из файла
     */
    fun generate(
        context: Context,
        finishedGameFile: File,
        basePlayers: List<PlayerInfo>,
        eventIdOverride: Int? = null
    ): GeneratedExport {

        require(finishedGameFile.exists()) {
            "Finished game file not found: ${finishedGameFile.absolutePath}"
        }

        val finishedJson = JSONObject(finishedGameFile.readText(Charsets.UTF_8))

        val eventId = run {
            val overrideId = eventIdOverride?.takeIf { it > 0 }

            val externalId = finishedJson
                .optString("externalEventId")
                .toIntOrNull()
                ?.takeIf { it > 0 }

            if (overrideId != null) return@run overrideId
            if (externalId != null) return@run externalId

            // Fallback: генерируем компактный ID YYDDDHHMM (вариант A).
            // Берём дату из finishedJson["date"], а если не парсится — используем текущее время,
            // чтобы гарантированно не получить 0.
            val dateStr = finishedJson.optString("date")
            val dt = try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).parse(dateStr)
            } catch (_: Exception) {
                null
            } ?: Date()

            val cal = Calendar.getInstance().apply { time = dt }
            val yy = cal.get(Calendar.YEAR) % 100
            val ddd = cal.get(Calendar.DAY_OF_YEAR)
            val hh = cal.get(Calendar.HOUR_OF_DAY)
            val mm = cal.get(Calendar.MINUTE)

            yy * 10_000_000 + ddd * 10_000 + hh * 100 + mm
        }



        val teams = finishedJson.getJSONObject("teams")
        val goalsArray = finishedJson.optJSONArray("goals") ?: JSONArray()

        val redPlayers = teams.getJSONObject("RED").getJSONArray("players")
        val whitePlayers = teams.getJSONObject("WHITE").getJSONArray("players")

        fun findUserIdByName(playerName: String): String {
            val p = basePlayers.firstOrNull { it.name == playerName }
            return p?.userId ?: playerName
        }


        // ---------- players[] ----------
        val playersStats = mutableMapOf<String, PlayerStat>()

        fun registerPlayer(name: String, team: String) {
            playersStats.getOrPut(name) {
                PlayerStat(
                    userId = findUserIdByName(name),
                    name = name,
                    team = team,
                    goals = 0,
                    assists = 0
                )
            }
        }

        for (i in 0 until redPlayers.length()) {
            registerPlayer(redPlayers.getString(i), "red")
        }
        for (i in 0 until whitePlayers.length()) {
            registerPlayer(whitePlayers.getString(i), "white")
        }

        // ---------- goals[] ----------
        val goalsResult = JSONArray()

        for (i in 0 until goalsArray.length()) {
            val g = goalsArray.getJSONObject(i)

            val team = g.getString("team").lowercase()
            val scorer = g.getString("scorer")
            val assist1 = g.optString("assist1", null)
            val assist2 = g.optString("assist2", null)
            val order = g.optInt("order", i + 1)

            playersStats[scorer]?.goals = playersStats[scorer]?.goals?.plus(1) ?: 1
            assist1?.let { playersStats[it]?.assists = playersStats[it]?.assists?.plus(1) ?: 1 }
            assist2?.let { playersStats[it]?.assists = playersStats[it]?.assists?.plus(1) ?: 1 }

            goalsResult.put(
                JSONObject().apply {
                    put("idx", order)
                    put("team", team)
                    put("minute", JSONObject.NULL)
                    put("scorer_user_id", findUserIdByName(scorer).toLongOrNull() ?: 0)
                    put("assist1_user_id", assist1?.let { findUserIdByName(it).toLongOrNull() ?: 0 })
                    put("assist2_user_id", assist2?.let { findUserIdByName(it).toLongOrNull() ?: 0 })
                    put("scorer_name", scorer)
                    put("assist1_name", assist1)
                    put("assist2_name", assist2)
                }
            )
        }

        val playersArray = JSONArray()
        playersStats.values.forEach {
            playersArray.put(
                JSONObject().apply {
                    put("user_id", it.userId)
                    put("name", it.name)
                    put("team", it.team)
                    put("goals", it.goals)
                    put("assists", it.assists)
                }
            )
        }

        val finalScore = finishedJson.getJSONObject("finalScore")

        val resultJson = JSONObject().apply {
            put("event_id", eventId)
            put("score_white", finalScore.optInt("WHITE", 0))
            put("score_red", finalScore.optInt("RED", 0))
            put("players", playersArray)
            put("goals", goalsResult)
        }

        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val exportDir = File(baseDir, "hockey-json/external-events-api").apply { mkdirs() }

        val exportFile = File(exportDir, "result_$eventId.json")
        exportFile.writeText(resultJson.toString(2), Charsets.UTF_8)

        return GeneratedExport(
            eventId = eventId,
            exportFile = exportFile
        )
    }

    private data class PlayerStat(
        val userId: String,
        val name: String,
        val team: String,
        var goals: Int,
        var assists: Int
    )
}
