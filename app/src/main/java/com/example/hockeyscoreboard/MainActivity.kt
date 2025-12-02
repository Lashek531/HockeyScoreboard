package com.example.hockeyscoreboard

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.hockeyscoreboard.data.GameRepository
import com.example.hockeyscoreboard.data.DriveRepository
import com.example.hockeyscoreboard.data.RaspiRepository
import com.example.hockeyscoreboard.data.db.GameDatabase
import com.example.hockeyscoreboard.data.writeSeasonFinishedIndexFile
import com.example.hockeyscoreboard.data.writeSeasonPlayersStatsFile
import com.example.hockeyscoreboard.ui.theme.HockeyScoreboardTheme
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.text.Charsets

class MainActivity : ComponentActivity() {

    // Локальная БД с играми
    private val gameDb by lazy {
        GameDatabase.getInstance(this)
    }

    // Репозиторий работы с Raspi (HTTP к Malina)
    private val raspiRepo by lazy {
        RaspiRepository()
    }

    // ----- Google Drive -----

    private lateinit var googleSignInClient: GoogleSignInClient

    // email аккаунта Drive (null = нет подключения)
    private var driveAccountEmail by mutableStateOf<String?>(null)

    // Репозитории
    private lateinit var driveRepo: DriveRepository
    private lateinit var gameRepo: GameRepository

    /** Эта часть для теста */
    companion object {
        /** Папка Google Drive для текущих (онлайн) игр */
        private const val DRIVE_ACTIVE_FOLDER_ID: String =
            "1WsmDjls_j8Y0Ysl6jX3_SFjtODateBSZ"

        /** Папка Google Drive для архива завершённых игр */
        private const val DRIVE_ARCHIVE_FOLDER_ID: String =
            "1oCuj8Y_ygfbMhyKL1A8cgtWG-MneGyCI"


    }

//    /** Эта часть для релиза */
//    companion object {
//        /** Папка Google Drive для текущих (онлайн) игр */
//        private const val DRIVE_ACTIVE_FOLDER_ID: String =
//            "158ttYeax1ab_ilCUuROybr6G4ZUJ5dEP"
//
//        /** Папка Google Drive для архива завершённых игр */
//        private const val DRIVE_ARCHIVE_FOLDER_ID: String =
//            "1xXkAOtax1d_3H66XLFqFnfkLUSAYOIzz"
//    }

    // ----- Google Sign-In / токен Drive -----

    // Единственная точка получения токена Drive
    private fun requestDriveToken(onReady: (String) -> Unit) {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account == null) {
            Toast.makeText(this, "Аккаунт Google не подключён", Toast.LENGTH_LONG).show()
            return
        }

        val email = account.email
        if (email.isNullOrEmpty()) {
            Toast.makeText(this, "Не удалось получить email аккаунта Google", Toast.LENGTH_LONG)
                .show()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = GoogleAuthUtil.getToken(
                    applicationContext,
                    email,
                    "oauth2:https://www.googleapis.com/auth/drive.file"
                )
                withContext(Dispatchers.Main) {
                    onReady(token)
                }
            } catch (e: UserRecoverableAuthException) {
                withContext(Dispatchers.Main) {
                    startActivity(e.intent)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Ошибка авторизации Drive: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    /**
     * Удаление игры с Google Drive по gameId / имени файла.
     * Локальная БД и файл должны быть уже очищены.
     */
    private fun deleteGameOnDrive(gameId: String, file: File?) {
        // Если Drive не подключён, просто игнорируем
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account == null) return

        requestDriveToken { token ->
            lifecycleScope.launch(Dispatchers.IO) {
                val result = driveRepo.deleteGameFileOnDrive(token, gameId, file)

                if (!result.success && result.errorMessage != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@MainActivity,
                            "Не удалось удалить игру с Google Drive: ${result.errorMessage}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    // Загрузка игры на Drive (только архивный файл, без онлайн-обновлений)
    private fun uploadGame(file: File, isFinal: Boolean) {
        // Онлайн-обновления на Google Drive больше не делаем
        if (!isFinal) return

        requestDriveToken { token ->
            lifecycleScope.launch(Dispatchers.IO) {
                val result = driveRepo.ensureGameInArchive(token, file)

                withContext(Dispatchers.Main) {
                    if (result.success) {
                        val msg = if (result.isUpdate) {
                            "Игра уже была в архиве Google Drive"
                        } else {
                            "Игра сохранена в архив Google Drive"
                        }
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            result.errorMessage ?: "Ошибка загрузки в архив",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    /**
     * Ручная проверка и дозагрузка всех игр в архивную папку на Google Drive.
     */
    private fun syncAllGamesWithDrive() {
        requestDriveToken { token ->
            lifecycleScope.launch(Dispatchers.IO) {
                val allGames = gameDb.gameDao().getAllGames()

                var alreadyOnDrive = 0
                var uploaded = 0
                var missingLocal = 0
                var failed = 0

                allGames.forEach { entry ->
                    val path = entry.localPath
                    if (path.isNullOrBlank()) {
                        missingLocal++
                        return@forEach
                    }

                    val file = File(path)
                    if (!file.exists()) {
                        missingLocal++
                        return@forEach
                    }

                    val result = driveRepo.ensureGameInArchive(token, file)
                    if (result.success) {
                        if (result.isUpdate) {
                            alreadyOnDrive++
                        } else {
                            uploaded++
                        }
                    } else {
                        failed++
                    }
                }

                withContext(Dispatchers.Main) {
                    val msg = buildString {
                        append("Синхронизация завершена.\n")
                        append("Всего игр в списке: ${allGames.size}.\n")
                        append("На Диске уже было: $alreadyOnDrive.\n")
                        append("Дозагружено: $uploaded.\n")
                        append("Без локального файла: $missingLocal.\n")
                        append("С ошибками: $failed.")
                    }
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Авторизация в Google — UI-триггер
    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                try {
                    val account = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                        .getResult(ApiException::class.java)
                    driveAccountEmail = account?.email
                    Toast.makeText(this, "Google Drive подключён", Toast.LENGTH_LONG).show()
                } catch (e: ApiException) {
                    Toast.makeText(this, "Ошибка входа: ${e.statusCode}", Toast.LENGTH_LONG).show()
                }
            }
        }

    private fun startGoogleSignIn() {
        val already = GoogleSignIn.getLastSignedInAccount(this)
        if (already != null) {
            driveAccountEmail = already.email
            Toast.makeText(this, "Drive уже подключён", Toast.LENGTH_LONG).show()
            return
        }
        googleSignInLauncher.launch(googleSignInClient.signInIntent)
    }

    // ----- Lifecycle -----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Репозитории
        driveRepo = DriveRepository(
            activeFolderId = DRIVE_ACTIVE_FOLDER_ID,
            archiveFolderId = DRIVE_ARCHIVE_FOLDER_ID
        )
        gameRepo = GameRepository(this)

        // Подхватываем прошлый вход
        driveAccountEmail = GoogleSignIn
            .getLastSignedInAccount(this)
            ?.email

        // Настройка Google Sign-In
        googleSignInClient = GoogleSignIn.getClient(
            this,
            GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail()
                .requestScopes(Scope("https://www.googleapis.com/auth/drive.file"))
                .build()
        )

        setContent {
            HockeyScoreboardTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ScoreboardScreen(
                        driveAccountEmail = driveAccountEmail,
                        onConnectDrive = { startGoogleSignIn() },

                        // Финальное сохранение игры: Drive (архив) + Raspi
                        onGameSaved = { file ->
                            // 1. Google Drive: финальное сохранение в архив
                            uploadGame(file, isFinal = true)

                            // 2. Сезон и DAO
                            val season = "25-26"   // пока жёстко, дальше вынесем в настройки
                            val gameDao = gameDb.gameDao()

                            // 3. В фоне: пересчёт сезонных файлов + отправка на Raspi
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    // 3.1. Пересчитываем и сохраняем локально:
                                    // finished/<season>/index.json
                                    val finishedIndexFile = writeSeasonFinishedIndexFile(
                                        context = this@MainActivity,
                                        season = season,
                                        gameDao = gameDao
                                    )

                                    // stats/<season>/players.json
                                    val playersStatsFile = writeSeasonPlayersStatsFile(
                                        context = this@MainActivity,
                                        season = season,
                                        gameDao = gameDao
                                    )

                                    // 3.2. Отправляем на Raspi сам json игры в архив
                                    runCatching {
                                        val gameJson = file.readText()
                                        raspiRepo.uploadFinishedGame(gameJson)
                                    }

                                    // 3.3. Отправляем обновлённый finished-index
                                    runCatching {
                                        val idxJson = finishedIndexFile.readText()
                                        raspiRepo.uploadFinishedIndex(idxJson)
                                    }

                                    // 3.4. Отправляем обновлённую статистику игроков
                                    runCatching {
                                        val statsJson = playersStatsFile.readText()
                                        raspiRepo.uploadPlayersStats(statsJson)
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    // при желании можно вывести тост на Main-потоке
                                }
                            }
                        },


                        // Онлайн-обновление текущей игры: ТОЛЬКО Raspi, без Google Drive
                        onGameJsonUpdated = { file ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                val json = file.readText(Charsets.UTF_8)
                                val res = raspiRepo.uploadActiveGame(json)

                                if (!res.success && res.errorMessage != null) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Ошибка обновления Raspi: ${res.errorMessage}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }
                            }
                        },

                        // Начало новой игры
                        onNewGameStarted = {
                            // 1. сброс локального указателя на текущий онлайн-файл Drive
                            driveRepo.resetCurrentFile()

                            // 2. очистка онлайн-папки на Google Drive
                            requestDriveToken { token ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    driveRepo.clearActiveFolder(token)
                                    // без тостов, фоновая операция
                                }
                            }

                            // при необходимости сюда можно добавить логику очистки на Raspi
                        },

                        onGameDeleted = { gameId, file ->
                            lifecycleScope.launch(Dispatchers.IO) {
                                // 1. удалить запись из Room
                                gameDb.gameDao().deleteGameById(gameId)

                                // 2. удалить локальный файл
                                file?.let {
                                    if (it.exists()) it.delete()
                                }

                                // 3. удалить файл на Google Drive
                                deleteGameOnDrive(gameId, file)

                                // Удаление на Raspi добавим позже, когда определимся с API
                            }
                        },

                        onSyncWithDrive = {
                            syncAllGamesWithDrive()
                        }
                    )
                }
            }
        }
    }
}
