package com.example.hockeyscoreboard.data

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.text.Charsets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RaspiRepository(
    private val settings: SettingsRepository
) {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    // ----------------------------------------------------------
    // ДИНАМИЧЕСКОЕ получение BASE_URL и API_KEY из настроек
    // ----------------------------------------------------------
    private suspend fun getBaseUrl(): String =
        settings.getServerUrl().trimEnd('/')

    private suspend fun getApiKey(): String =
        settings.getApiKey()

    // ----------------------------------------------------------
    // Публичные API-вызовы
    // ----------------------------------------------------------

    suspend fun uploadActiveGame(json: String): RaspiResult =
        postJson("/api/upload-active-game", json)

    suspend fun uploadFinishedGame(json: String): RaspiResult =
        postJson("/api/upload-finished-game", json)

    suspend fun uploadFinishedIndex(json: String): RaspiResult =
        postJson("/api/upload-finished-index", json)

    suspend fun uploadPlayersStats(json: String): RaspiResult =
        postJson("/api/upload-players-stats", json)

    suspend fun uploadRootIndex(json: String): RaspiResult =
        postJson("/api/upload-root-index", json)

    suspend fun uploadDebugJson(json: String): RaspiResult =
        postJson("/api/upload-json", json)

    suspend fun uploadSettings(json: String): RaspiResult =
        postJson("/api/upload-settings", json)

    suspend fun uploadBaseRoster(json: String): RaspiResult =
        postJson("/api/upload-base-roster", json)

    suspend fun deleteFinishedGame(season: String, gameId: String): RaspiResult {
        val body = """
            {
              "season": "$season",
              "id": "$gameId"
            }
        """.trimIndent()
        return postJson("/api/delete-finished-game", body)
    }

    // ----------------------------------------------------------
    // Качаем состав (простой вариант: roster.json)
    // ----------------------------------------------------------

    suspend fun downloadRoster(): RosterDownloadResult =
        withContext(Dispatchers.IO) {
            try {
                val baseUrl = getBaseUrl()
                val url = URL("$baseUrl/rosters/roster.json")

                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Accept", "application/json")
                }

                val code = conn.responseCode
                if (code !in 200..299) {
                    return@withContext RosterDownloadResult(
                        success = false,
                        error = "HTTP $code при скачивании roster.json"
                    )
                }

                val body = conn.inputStream.use { s ->
                    BufferedReader(InputStreamReader(s, Charsets.UTF_8)).readText()
                }

                RosterDownloadResult(success = true, json = body)
            } catch (e: Exception) {
                RosterDownloadResult(
                    success = false,
                    error = e.toString()
                )
            }
        }



    data class RosterDownloadResult(
        val success: Boolean,
        val json: String? = null,
        val error: String? = null
    )

    // ----------------------------------------------------------
    // Базовый POST клиент
    // ----------------------------------------------------------

    private suspend fun postJson(path: String, body: String): RaspiResult {
        return try {
            val baseUrl = getBaseUrl()
            val apiKey = getApiKey()
            val url = URL(baseUrl + path)

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                doInput = true

                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Api-Key", apiKey)
            }

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

            val json = try {
                JSONObject(responseBody)
            } catch (_: Exception) {
                return RaspiResult(
                    success = false,
                    errorMessage = "Некорректный JSON-ответ: $responseBody"
                )
            }

            if (json.optString("status") == "ok") {
                RaspiResult(success = true)
            } else {
                RaspiResult(
                    success = false,
                    errorMessage = "Ответ сервера не ok: $responseBody"
                )
            }

        } catch (e: Exception) {
            RaspiResult(success = false, errorMessage = e.message)
        }
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String? {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.use {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText()
        }
    }
}

data class RaspiResult(
    val success: Boolean,
    val errorMessage: String? = null
)
