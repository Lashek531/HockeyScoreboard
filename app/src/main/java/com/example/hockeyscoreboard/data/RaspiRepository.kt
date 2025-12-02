package com.example.hockeyscoreboard.data

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.text.Charsets

data class RaspiResult(
    val success: Boolean,
    val errorMessage: String? = null
)

/**
 * Клиент к Raspberry Pi API.
 *
 * Использует спецификацию:
 *  - POST /api/upload-active-game
 *  - POST /api/upload-finished-game
 *  - POST /api/upload-finished-index
 *  - POST /api/upload-players-stats
 *  - POST /api/upload-root-index  (пока не вызываем, но есть)
 *  - POST /api/upload-json        (отладка, пишет в incoming/)
 *
 * Все запросы:
 *  - Content-Type: application/json; charset=utf-8
 *  - X-Api-Key: <секретный ключ>
 *
 * Ответ при успехе:
 *  { "status": "ok", "file": "relative/path/to/file.json" }
 */
class RaspiRepository {

    companion object {
        /** Базовый URL Malina (Flask/Nginx) */
        private const val BASE_URL = "https://hockey.ch73210.keenetic.pro:8443"

        /** API key, обязательный для всех эндпоинтов */
        private const val API_KEY = "3vXjhEr1YvFzgL6gO2fc_"

        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    // --- Публичные методы под каждый тип данных ---

    /** Активная игра – пишется в active_game.json */
    fun uploadActiveGame(json: String): RaspiResult =
        postJson("/api/upload-active-game", json)

    /** Одна завершённая игра – finished/<season>/<id>.json */
    fun uploadFinishedGame(json: String): RaspiResult =
        postJson("/api/upload-finished-game", json)

    /** Индекс завершённых игр сезона – finished/<season>/index.json */
    fun uploadFinishedIndex(json: String): RaspiResult =
        postJson("/api/upload-finished-index", json)

    /** Статистика игроков сезона – stats/<season>/players.json */
    fun uploadPlayersStats(json: String): RaspiResult =
        postJson("/api/upload-players-stats", json)

    /** Глобальный корневой индекс сезонов – index.json (на будущее) */
    fun uploadRootIndex(json: String): RaspiResult =
        postJson("/api/upload-root-index", json)

    /** Универсальный логгер в incoming/ – для отладки */
    fun uploadDebugJson(json: String): RaspiResult =
        postJson("/api/upload-json", json)

    // --- Базовый HTTP-клиент ---

    private fun postJson(path: String, body: String): RaspiResult {
        return try {
            val url = URL(BASE_URL + path)

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                doInput = true

                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Api-Key", API_KEY)
            }

            // Тело запроса
            conn.outputStream.use { out ->
                out.write(body.toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val code = conn.responseCode
            val responseBody = readBody(conn, code)

            if (code !in 200..299) {
                return RaspiResult(
                    success = false,
                    errorMessage = "HTTP $code: $responseBody"
                )
            }

            if (responseBody.isNullOrBlank()) {
                return RaspiResult(
                    success = false,
                    errorMessage = "Пустой ответ сервера"
                )
            }

            // Ожидаем JSON вида {"status":"ok","file":"..."}
            val json = try {
                JSONObject(responseBody)
            } catch (e: Exception) {
                return RaspiResult(
                    success = false,
                    errorMessage = "Некорректный JSON-ответ: $responseBody"
                )
            }

            val status = json.optString("status", "")
            val file = json.optString("file", null)

            if (status == "ok") {
                RaspiResult(success = true, errorMessage = null)
            } else {
                RaspiResult(
                    success = false,
                    errorMessage = "Ответ сервера status='$status', file='$file'"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            RaspiResult(success = false, errorMessage = e.message)
        }
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String? {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.use { s ->
            BufferedReader(InputStreamReader(s, Charsets.UTF_8)).readText()
        }
    }
}
