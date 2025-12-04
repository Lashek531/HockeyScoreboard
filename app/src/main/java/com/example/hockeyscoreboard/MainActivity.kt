package com.example.hockeyscoreboard

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.hockeyscoreboard.data.RaspiRepository
import com.example.hockeyscoreboard.data.SettingsRepositoryImpl
import com.example.hockeyscoreboard.data.db.GameDatabase
import com.example.hockeyscoreboard.ui.theme.HockeyScoreboardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.text.Charsets

class MainActivity : ComponentActivity() {

    // Репозиторий настроек — должен быть создан ПЕРВЫМ
    private val settingsRepository by lazy { SettingsRepositoryImpl(this) }

    // Репозиторий работы с Raspi (HTTP к серверу) — теперь зависит от settingsRepository
    private val raspiRepo by lazy { RaspiRepository(settingsRepository) }

    // Локальная БД с играми
    private val gameDb by lazy { GameDatabase.getInstance(this) }


    /** Снятие скриншота текущего экрана. */
    private fun captureCurrentScreen(onBitmapReady: (Bitmap) -> Unit) {
        val rootView = window.decorView.rootView

        rootView.post {
            val width = rootView.width
            val height = rootView.height
            if (width == 0 || height == 0) return@post

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            rootView.draw(canvas)

            onBitmapReady(bitmap)
        }
    }

    /** Создание PNG и отправка в Telegram */
    private fun takeAndSendScoreboardScreenshot() {
        captureCurrentScreen { bitmap ->
            lifecycleScope.launch {
                val file = withContext(Dispatchers.IO) {
                    val outFile = File(cacheDir, "scoreboard_${System.currentTimeMillis()}.png")
                    outFile.outputStream().use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    outFile
                }

                sendScreenshotToTelegram(file)
            }
        }
    }

    /** Отправка PNG в Telegram */
    private suspend fun sendScreenshotToTelegram(file: File) {
        val token = settingsRepository.getTelegramBotToken().trim()
        val chatId = settingsRepository.getTelegramChatId().trim()

        if (token.isEmpty() || chatId.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "Telegram не настроен (bot token / chat id)",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        try {
            withContext(Dispatchers.IO) {
                val url = URL("https://api.telegram.org/bot$token/sendPhoto")
                val boundary = "HSB-${System.currentTimeMillis()}"
                val lineEnd = "\r\n"
                val twoHyphens = "--"

                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doInput = true
                    doOutput = true
                    useCaches = false
                    setRequestProperty(
                        "Content-Type",
                        "multipart/form-data; boundary=$boundary"
                    )
                }

                DataOutputStream(connection.outputStream).use { output ->

                    // chat_id
                    output.writeBytes(twoHyphens + boundary + lineEnd)
                    output.writeBytes(
                        "Content-Disposition: form-data; name=\"chat_id\"$lineEnd$lineEnd"
                    )
                    output.writeBytes(chatId + lineEnd)

                    // Файл
                    output.writeBytes(twoHyphens + boundary + lineEnd)
                    output.writeBytes(
                        "Content-Disposition: form-data; name=\"photo\"; filename=\"${file.name}\"$lineEnd"
                    )
                    output.writeBytes("Content-Type: image/png$lineEnd$lineEnd")

                    file.inputStream().use { input ->
                        val buffer = ByteArray(4096)
                        var bytesRead = input.read(buffer)
                        while (bytesRead != -1) {
                            output.write(buffer, 0, bytesRead)
                            bytesRead = input.read(buffer)
                        }
                    }

                    output.writeBytes(lineEnd)
                    output.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd)
                    output.flush()
                }

                val code = connection.responseCode
                if (code != HttpURLConnection.HTTP_OK) {
                    throw RuntimeException("HTTP $code")
                }
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "Скриншот отправлен в Telegram",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    "Ошибка отправки скриншота: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HockeyScoreboardTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ScoreboardScreen(

                        // Когда игра завершена — отправляем finished JSON на Raspi
                        onGameSaved = { file ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val json = runCatching {
                                    file.readText(Charsets.UTF_8)
                                }.getOrElse {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Не удалось прочитать файл завершённой игры",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    return@launch
                                }

                                val res = raspiRepo.uploadFinishedGame(json)
                                if (!res.success) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Ошибка отправки завершённой игры на сервер",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        },

                        // Обновление active_game.json на сервере
                        onGameJsonUpdated = { file ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val json = runCatching {
                                    file.readText(Charsets.UTF_8)
                                }.getOrElse {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Не удалось прочитать файл активной игры",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                    return@launch
                                }

                                val res = raspiRepo.uploadActiveGame(json)
                                if (!res.success) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Ошибка обновления игры на сервере",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        },

                        onNewGameStarted = {
                            // пока ничего
                        },

                        onGameDeleted = { gameId: String, file: File?, onResult: (Boolean) -> Unit ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val dao = gameDb.gameDao()
                                val entry = dao.getGameById(gameId)
                                val season = entry?.season

                                if (season == null) {
                                    dao.deleteGameById(gameId)
                                    file?.delete()

                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Сезон игры не найден, удалена только локальная копия",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    onResult(true)
                                    return@launch
                                }

                                val res = raspiRepo.deleteFinishedGame(season, gameId)

                                if (!res.success) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Не удалось удалить игру на сервере",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    onResult(false)
                                    return@launch
                                }

                                dao.deleteGameById(gameId)
                                file?.delete()

                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Игра удалена на сервере и на устройстве",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                                onResult(true)
                            }
                        },

                        onFinalScreenshotRequested = {
                            takeAndSendScoreboardScreenshot()
                        }
                    )
                }
            }
        }
    }
}
