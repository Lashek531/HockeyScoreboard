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
import com.example.hockeyscoreboard.data.DriveRepository
import com.example.hockeyscoreboard.data.GameRepository
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

class MainActivity : ComponentActivity() {

    companion object {
        /** Папка Google Drive для текущих (онлайн) игр */
        private const val DRIVE_ACTIVE_FOLDER_ID: String =
            "1WsmDjls_j8Y0Ysl6jX3_SFjtODateBSZ"

        /** Папка Google Drive для архива завершённых игр */
        private const val DRIVE_ARCHIVE_FOLDER_ID: String =
            "1oCuj8Y_ygfbMhyKL1A8cgtWG-MneGyCI"
    }

    // ----- Google Sign-In -----

    private lateinit var googleSignInClient: GoogleSignInClient

    // email аккаунта Drive (null = нет подключения)
    private var driveAccountEmail by mutableStateOf<String?>(null)

    // Репозитории
    private lateinit var driveRepo: DriveRepository
    private lateinit var gameRepo: GameRepository

    // Единственная точка получения токена Drive
    private fun requestDriveToken(onReady: (String) -> Unit) {
        val account = GoogleSignIn.getLastSignedInAccount(this)
        if (account == null) {
            Toast.makeText(this, "Аккаунт Google не подключён", Toast.LENGTH_LONG).show()
            return
        }

        val email = account.email
        if (email.isNullOrEmpty()) {
            Toast.makeText(this, "Не удалось получить email аккаунта Google", Toast.LENGTH_LONG).show()
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

    // Загрузка игры на Drive (обёртка над репозиторием)
    private fun uploadGame(file: File, isFinal: Boolean) {
        requestDriveToken { token ->
            lifecycleScope.launch(Dispatchers.IO) {
                val result = driveRepo.uploadFileWithToken(token, file, isFinal)

                withContext(Dispatchers.Main) {
                    when {
                        result.success && isFinal ->
                            Toast.makeText(
                                this@MainActivity,
                                "Игра сохранена и архивирована",
                                Toast.LENGTH_LONG
                            ).show()

                        result.success && result.isUpdate ->
                            Toast.makeText(
                                this@MainActivity,
                                "Игра обновлена в Google Drive",
                                Toast.LENGTH_LONG
                            ).show()

                        result.success ->
                            Toast.makeText(
                                this@MainActivity,
                                "Игра загружена в Google Drive",
                                Toast.LENGTH_LONG
                            ).show()

                        else ->
                            Toast.makeText(
                                this@MainActivity,
                                result.errorMessage ?: "Ошибка загрузки",
                                Toast.LENGTH_LONG
                            ).show()
                    }
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
                        gameRepository = gameRepo,
                        driveAccountEmail = driveAccountEmail,
                        onConnectDrive = { startGoogleSignIn() },

                        // Финальное сохранение игры
                        onGameSaved = { file ->
                            uploadGame(file, isFinal = true)
                        },

                        // Онлайн-обновление JSON при изменении счёта
                        onGameJsonUpdated = { file ->
                            uploadGame(file, isFinal = false)
                        },

                        // Начало новой игры → забываем старый Drive-файл
                        onNewGameStarted = {
                            // 1. сброс локального указателя на текущий онлайн-файл
                            driveRepo.resetCurrentFile()

                            // 2. очистка онлайн-папки на Google Drive
                            requestDriveToken { token ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    driveRepo.clearActiveFolder(token)
                                    // можно без тостов, это фоновый сервис
                                }
                            }
                        }

                    )
                }
            }
        }
    }
}
