package com.example.hockeyscoreboard

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.hockeyscoreboard.data.RaspiRepository
import com.example.hockeyscoreboard.data.db.GameDatabase
import com.example.hockeyscoreboard.ui.theme.HockeyScoreboardTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.text.Charsets

class MainActivity : ComponentActivity() {

    // Локальная БД с играми
    private val gameDb by lazy { GameDatabase.getInstance(this) }

    // Репозиторий работы с Raspi (HTTP к Malina)
    private val raspiRepo by lazy { RaspiRepository() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            HockeyScoreboardTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ScoreboardScreen(

                        // Финальное сохранение игры:
                        // локальная БД/файл делаются внутри ScoreboardScreen,
                        // здесь только отправка на Raspi в архив.
                        onGameSaved = { file ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val json = runCatching {
                                    file.readText(Charsets.UTF_8)
                                }.getOrElse { e ->
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
                                if (!res.success && res.errorMessage != null) {
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

                        // Онлайн-обновление текущей игры: отправляем только на Raspi
                        onGameJsonUpdated = { file ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val json = runCatching {
                                    file.readText(Charsets.UTF_8)
                                }.getOrElse { e ->
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
                                if (!res.success && res.errorMessage != null) {
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

                        // Начало новой игры – при необходимости сюда позже можно
                        // добавить отдельный запрос на Raspi (например, очистку active_game.json)
                        onNewGameStarted = {
                            // Пока ничего дополнительно не делаем
                        },

                        // Удаление игры:
                        // 1) пытаемся удалить на Raspi;
                        // 2) только при успехе чистим локальную БД и файл.
                        onGameDeleted = { gameId: String, file: File? ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val dao = gameDb.gameDao()

                                // Пытаемся получить запись, чтобы узнать сезон
                                val entry = runCatching {
                                    dao.getGameById(gameId)
                                }.getOrNull()

                                val season = entry?.season

                                if (season == null) {
                                    // Сезон не нашли – удаляем только локально
                                    dao.deleteGameById(gameId)
                                    file?.let { if (it.exists()) it.delete() }

                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Сезон игры не найден, удалена только локальная копия",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    return@launch
                                }

                                // Запрос на удаление игры на Raspi
                                val res = raspiRepo.deleteFinishedGame(season, gameId)

                                if (!res.success) {
                                    // Сервер не подтвердил удаление – локальную копию НЕ трогаем
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Не удалось удалить игру на сервере",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    return@launch
                                }

                                // Успешно удалено на Raspi – чистим локально
                                dao.deleteGameById(gameId)
                                file?.let { if (it.exists()) it.delete() }

                                withContext(Dispatchers.Main) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Игра удалена на сервере и на устройстве",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}
