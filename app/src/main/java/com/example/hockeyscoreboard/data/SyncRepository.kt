package com.example.hockeyscoreboard.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

sealed class SyncResult {
    object Success : SyncResult()
    data class Error(val message: String) : SyncResult()
}

/**
 * settingsRepository должен уметь вернуть:
 *  - serverUrl: String (например, https://hockey.ch73210.keenetic.pro:8443)
 *  - apiKey: String (наш X-Api-Key)
 */
interface SettingsRepository {
    suspend fun getServerUrl(): String
    suspend fun getApiKey(): String
}

class SyncRepository(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val httpClient: OkHttpClient = OkHttpClient()
) {

    // базовая директория там же, где лежат игры (Android/data/.../files)
    private val baseDir: File
        get() = context.getExternalFilesDir(null) ?: context.filesDir

    // сюда должна попадать база hockey-json
    private val dbDir: File
        get() = File(baseDir, "hockey-json")

    // сюда временно распаковываем архив
    private val tmpRootDir: File
        get() = File(baseDir, "hockey-json-tmp-root")

    // ZIP можно продолжать хранить во внутреннем cacheDir
    private val tmpZipFile: File
        get() = File(context.cacheDir, "hockey-db.zip")

    suspend fun syncDatabase(): SyncResult = withContext(Dispatchers.IO) {
        try {
            val serverUrl = settingsRepository.getServerUrl().trimEnd('/')
            val apiKey = settingsRepository.getApiKey()

            if (serverUrl.isBlank()) {
                return@withContext SyncResult.Error("Не указан URL сервера")
            }

            val url = "$serverUrl/api/download-db"

            val request = Request.Builder()
                .url(url)
                .header("X-Api-Key", apiKey)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext SyncResult.Error(
                    "HTTP ${response.code} при скачивании архива"
                )
            }

            val body = response.body ?: return@withContext SyncResult.Error("Пустой ответ сервера")

            // 1. Сохраняем ZIP во временный файл
            saveResponseBodyToFile(body.byteStream(), tmpZipFile)

            // 2. Распаковываем ZIP во временную папку
            if (tmpRootDir.exists()) {
                tmpRootDir.deleteRecursively()
            }
            tmpRootDir.mkdirs()

            unzipToDir(tmpZipFile, tmpRootDir)

            // 3. Проверяем, что внутри есть hockey-json
            val newDbDir = File(tmpRootDir, "hockey-json")
            if (!newDbDir.exists() || !newDbDir.isDirectory) {
                return@withContext SyncResult.Error("В архиве нет папки hockey-json")
            }

            // Можно добавить ещё валидацию: наличие index.json и т.п.
            val indexFile = File(newDbDir, "index.json")
            if (!indexFile.exists()) {
                return@withContext SyncResult.Error("В архиве нет index.json")
            }

            // 4. Атомарная замена: старую базу удаляем, новую переименовываем
            if (dbDir.exists()) {
                dbDir.deleteRecursively()
            }

            if (!newDbDir.renameTo(dbDir)) {
                // если rename не удался, пробуем копирование (на всякий случай)
                copyDirRecursively(newDbDir, dbDir)
                newDbDir.deleteRecursively()
            }

            // 5. Чистим временный мусор
            tmpZipFile.delete()
            tmpRootDir.deleteRecursively()

            SyncResult.Success
        } catch (e: Exception) {
            SyncResult.Error("Ошибка синхронизации: ${e.message}")
        }
    }

    private fun saveResponseBodyToFile(input: InputStream, target: File) {
        input.use { inStream ->
            FileOutputStream(target).use { out ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read: Int
                while (true) {
                    read = inStream.read(buffer)
                    if (read == -1) break
                    out.write(buffer, 0, read)
                }
                out.flush()
            }
        }
    }

    private fun unzipToDir(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry = zis.nextEntry
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

            while (entry != null) {
                val outFile = File(targetDir, entry.name)

                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        var read: Int
                        while (true) {
                            read = zis.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                        }
                        out.flush()
                    }
                }

                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun copyDirRecursively(from: File, to: File) {
        if (!from.exists()) return
        if (!to.exists()) to.mkdirs()

        from.walk().forEach { src ->
            if (src.isFile) {
                val relPath = src.relativeTo(from).path
                val dst = File(to, relPath)
                dst.parentFile?.mkdirs()
                src.copyTo(dst, overwrite = true)
            }
        }
    }
}
