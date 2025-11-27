package com.example.hockeyscoreboard.data

import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL

data class DriveResult(
    val success: Boolean,
    val isUpdate: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Работа с Google Drive:
 *  - загрузка/обновление онлайн-файла в ACTIVE-папке
 *  - копирование файла в ARCHIVE-папке при завершении игры
 *  - очистка ACTIVE-папки при начале НОВОЙ игры
 */
class DriveRepository(
    private val activeFolderId: String,
    private val archiveFolderId: String
) {

    // ID текущего онлайн-файла (в ACTIVE-папке)
    private var currentFileId: String? = null
    private var currentFileName: String? = null

    fun resetCurrentFile() {
        currentFileId = null
        currentFileName = null
    }

    /**
     * Загрузка / обновление онлайн-файла.
     *
     * isFinal = false → просто обновляем файл в ACTIVE-папке.
     * isFinal = true  → обновляем файл в ACTIVE и копируем его в ARCHIVE (отдельный файл).
     */
    suspend fun uploadFileWithToken(
        token: String,
        file: File,
        isFinal: Boolean
    ): DriveResult {
        return try {
            val isUpdate = (currentFileId != null)

            val boundary = "HockeyScoreboardBoundary_${System.currentTimeMillis()}"
            val url = if (isUpdate) {
                URL("https://www.googleapis.com/upload/drive/v3/files/${currentFileId}?uploadType=multipart")
            } else {
                URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
            }

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = if (isUpdate) "PATCH" else "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
                setRequestProperty("Accept", "application/json")
            }

            // Для create указываем родителя ACTIVE;
            // для update НЕ трогаем parents, только имя.
            val metadataJson = when {
                !isUpdate && activeFolderId.isNotBlank() ->
                    """{"name": "${file.name}", "parents": ["$activeFolderId"]}"""
                else ->
                    """{"name": "${file.name}"}"""
            }

            val lineEnd = "\r\n"
            val twoHyphens = "--"

            connection.outputStream.use { out ->
                // metadata part
                val metaPart = buildString {
                    append(twoHyphens).append(boundary).append(lineEnd)
                    append("Content-Type: application/json; charset=UTF-8").append(lineEnd)
                    append(lineEnd)
                    append(metadataJson).append(lineEnd)
                }
                out.write(metaPart.toByteArray(Charsets.UTF_8))

                // file part
                val fileHeaderPart = buildString {
                    append(twoHyphens).append(boundary).append(lineEnd)
                    append("Content-Type: application/json; charset=UTF-8").append(lineEnd)
                    append(lineEnd)
                }
                out.write(fileHeaderPart.toByteArray(Charsets.UTF_8))

                file.inputStream().use { input ->
                    input.copyTo(out)
                }

                // end boundary
                val endPart = buildString {
                    append(lineEnd)
                    append(twoHyphens).append(boundary).append(twoHyphens).append(lineEnd)
                }
                out.write(endPart.toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val code = connection.responseCode
            val body = readBody(connection, code)

            if (code in 200..299) {
                // create → запоминаем id
                if (!isUpdate) {
                    try {
                        val json = JSONObject(body ?: "")
                        val id = json.optString("id", null)
                        if (!id.isNullOrEmpty()) {
                            currentFileId = id
                            currentFileName = file.name
                        }
                    } catch (_: Exception) {
                    }
                } else {
                    currentFileName = file.name
                }

                // Если игра завершена, создаём КОПИЮ в архивной папке
                if (isFinal && currentFileId != null && archiveFolderId.isNotBlank()) {
                    val copyRes = copyFileToArchive(
                        token = token,
                        sourceFileId = currentFileId!!,
                        newName = currentFileName ?: file.name
                    )
                    if (!copyRes.success) {
                        return copyRes
                    }
                }

                DriveResult(
                    success = true,
                    isUpdate = isUpdate,
                    errorMessage = null
                )
            } else {
                DriveResult(
                    success = false,
                    isUpdate = isUpdate,
                    errorMessage = "Ошибка Drive: $code\n${body ?: ""}"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            DriveResult(
                success = false,
                errorMessage = "Исключение при загрузке: ${e.message}"
            )
        }
    }

    /**
     * Копирование файла из ACTIVE-папки в ARCHIVE-папку.
     * Используем метод Drive API: POST /files/{fileId}/copy
     */
    private suspend fun copyFileToArchive(
        token: String,
        sourceFileId: String,
        newName: String
    ): DriveResult {
        return try {
            if (archiveFolderId.isBlank()) {
                return DriveResult(success = true)
            }

            val url = URL("https://www.googleapis.com/drive/v3/files/$sourceFileId/copy")

            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
            }

            val bodyJson = """{
                "name": "$newName",
                "parents": ["$archiveFolderId"]
            }""".trimIndent()

            connection.outputStream.use { out ->
                out.write(bodyJson.toByteArray(Charsets.UTF_8))
                out.flush()
            }

            val code = connection.responseCode
            val body = readBody(connection, code)

            if (code in 200..299) {
                DriveResult(success = true)
            } else {
                DriveResult(
                    success = false,
                    errorMessage = "Не удалось скопировать в архив: $code\n${body ?: ""}"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            DriveResult(
                success = false,
                errorMessage = "Исключение при копировании в архив: ${e.message}"
            )
        }
    }

    /**
     * Очистка онлайн-папки (ACTIVE):
     *  - находим все файлы с parent = activeFolderId
     *  - удаляем их
     *
     * Используется ТОЛЬКО при начале НОВОЙ игры.
     */
    suspend fun clearActiveFolder(token: String): DriveResult {
        return try {
            if (activeFolderId.isBlank()) {
                return DriveResult(success = true)
            }

            val query = URLEncoder.encode("'$activeFolderId' in parents and trashed = false", "UTF-8")
            val url = URL(
                "https://www.googleapis.com/drive/v3/files?q=$query&fields=files(id,name)"
            )

            val listConn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
            }

            val listCode = listConn.responseCode
            val listBody = readBody(listConn, listCode)

            if (listCode !in 200..299) {
                return DriveResult(
                    success = false,
                    errorMessage = "Не удалось получить список файлов: $listCode\n${listBody ?: ""}"
                )
            }

            val filesJson = JSONObject(listBody ?: "{}").optJSONArray("files")
                ?: return DriveResult(success = true)

            for (i in 0 until filesJson.length()) {
                val f = filesJson.optJSONObject(i) ?: continue
                val id = f.optString("id", null) ?: continue

                val delUrl = URL("https://www.googleapis.com/drive/v3/files/$id")
                val delConn = (delUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "DELETE"
                    setRequestProperty("Authorization", "Bearer $token")
                }

                val delCode = delConn.responseCode
                // если что-то не удалилось — не валим всю операцию
                if (delCode !in 200..299 && delCode != 204) {
                    // можно залогировать, но не критично
                }
            }

            resetCurrentFile()

            DriveResult(success = true)
        } catch (e: Exception) {
            e.printStackTrace()
            DriveResult(
                success = false,
                errorMessage = "Исключение при очистке онлайн-папки: ${e.message}"
            )
        }
    }

    /**
     * Удаление файла игры с Google Drive.
     * Пока заглушка — фактическое удаление не реализовано.
     */
    suspend fun deleteGameFileOnDrive(
        localFile: File?,
        gameId: String
    ) {
        // TODO: реализовать удаление файла на Google Drive по gameId / имени / driveFileId
    }

    // --- утилита чтения тела ответа ---
    private fun readBody(conn: HttpURLConnection, code: Int): String? {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return stream?.use { s ->
            BufferedReader(InputStreamReader(s)).readText()
        }
    }
}
