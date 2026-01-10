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
import com.example.hockeyscoreboard.HttpOutboxRepository
import com.example.hockeyscoreboard.HttpRetryScheduler


class MainActivity : ComponentActivity() {

    // Репозиторий настроек — должен быть создан ПЕРВЫМ
    private val settingsRepository by lazy { SettingsRepositoryImpl(this) }

    // Репозиторий работы с Raspi (HTTP к серверу) — теперь зависит от settingsRepository
    private val raspiRepo by lazy { RaspiRepository(settingsRepository) }

    // Локальная БД с играми
    private val gameDb by lazy { GameDatabase.getInstance(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HockeyScoreboardTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ScoreboardScreen(

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

                                // season берём из имени папки (как есть сейчас)
                                val season = file.parentFile?.name.orEmpty()
                                val gameId = file.nameWithoutExtension

                                // 1) кладём в http-outbox (PENDING)
                                val httpOutbox = HttpOutboxRepository(this@MainActivity)
                                httpOutbox.upsertPending(
                                    gameId = gameId,
                                    season = season,
                                    finishedFilePath = file.absolutePath
                                )

                                // 2) прямая попытка отправки
                                val res = raspiRepo.uploadFinishedGame(json)
                                if (res.success) {
                                    httpOutbox.markSent(gameId, season)
                                } else {
                                    httpOutbox.markFailed(gameId, season, res.errorMessage ?: "HTTP upload failed")
                                    HttpRetryScheduler.schedule(this@MainActivity)

                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Поставлено в очередь (сервер)",
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

                    )
                }
            }
        }
    }
}
