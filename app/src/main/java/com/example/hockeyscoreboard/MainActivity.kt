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
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject


class MainActivity : ComponentActivity() {


    companion object {
                private const val REQUEST_DRIVE_CONSENT = 1001

        // Сюда можно вписать ID папки в Google Drive, куда грузить файлы.
        // Если оставить пустой строкой, файлы будут грузиться в корень (.
        private const val DRIVE_FOLDER_ID: String = "1WsmDjls_j8Y0Ysl6jX3_SFjtODateBSZ"   // пример: "1AbCdEfGhIjKlMnOpQrStUvWxYz"
    }

    // ----- Google Sign-In -----

    private lateinit var googleSignInClient: GoogleSignInClient

    // email аккаунта Drive (null = не подключён)
    private var driveAccountEmail by mutableStateOf<String?>(null)

    // ID файла текущей игры на Google Drive (если уже создан)
    private var currentDriveFileId: String? = null

    // Имя файла текущей игры, для которого этот ID относится
    private var currentDriveFileName: String? = null

    // Лаунчер результата Google Sign-In
    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                try {
                    val account = task.getResult(ApiException::class.java)
                    val email = account?.email
                    driveAccountEmail = email   // обновляем состояние для UI

                    Toast.makeText(
                        this,
                        "Вход через Google: ${email ?: "без email"}",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: ApiException) {
                    Toast.makeText(
                        this,
                        "Ошибка входа: ${e.statusCode}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else {
                Toast.makeText(this, "Авторизация отменена", Toast.LENGTH_SHORT).show()
            }
        }




    private fun startGoogleSignIn() {
        val lastAccount = GoogleSignIn.getLastSignedInAccount(this)
        if (lastAccount != null) {
            // Уже авторизованы — просто поднимем email в состояние
            driveAccountEmail = lastAccount.email

            Toast.makeText(
                this,
                "Уже вошли как: ${lastAccount.email ?: "неизвестный аккаунт"}",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Запускаем окно выбора аккаунта
        googleSignInLauncher.launch(googleSignInClient.signInIntent)
    }

    // ----- Загрузка игры в Google Drive -----

    private fun uploadGameToDrive(file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            // 1. Проверяем, что есть залогиненный аккаунт
            val account = GoogleSignIn.getLastSignedInAccount(this@MainActivity)
            if (account == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Нет подключённого аккаунта Google",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            // 2. Берём email и используем перегрузку getToken(Context, String, String)
            val email = account.email
            if (email.isNullOrEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Не удалось получить email аккаунта Google",
                        Toast.LENGTH_LONG
                    ).show()
                }
                return@launch
            }

            val scope = "oauth2:https://www.googleapis.com/auth/drive.file"

            try {
                // ПЕРВАЯ попытка получить токен
                val token = GoogleAuthUtil.getToken(
                    applicationContext,
                    email,          // ВАЖНО: строка, а не account.account
                    scope
                )

                // Если сюда дошли — токен есть, грузим файл
                uploadFileToDriveWithToken(token, file)

            } catch (e: UserRecoverableAuthException) {
                // Нужно показать системное окно согласия на доступ к Drive
                withContext(Dispatchers.Main) {
                    val consentIntent = e.intent
                    if (consentIntent != null) {
                        Toast.makeText(
                            this@MainActivity,
                            "Нужно подтвердить доступ к Google Drive. Откроется окно согласия.",
                            Toast.LENGTH_LONG
                        ).show()
                        startActivity(consentIntent)
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Нужно подтвердить доступ к Google Drive (нет intent).",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Исключение при загрузке в Drive: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun uploadFileToDriveWithToken(token: String, file: File) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val boundary = "HockeyScoreboardBoundary_${System.currentTimeMillis()}"
                val url = URL("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty(
                        "Content-Type",
                        "multipart/related; boundary=$boundary"
                    )
                    setRequestProperty("Accept", "application/json")
                }

                // Если DRIVE_FOLDER_ID не пустой — добавляем parents, иначе грузим в корень
                val metadataJson = if (DRIVE_FOLDER_ID.isNotBlank()) {
                    """{"name": "${file.name}", "parents": ["$DRIVE_FOLDER_ID"]}"""
                } else {
                    """{"name": "${file.name}"}"""
                }

                val lineEnd = "\r\n"
                val twoHyphens = "--"

                connection.outputStream.use { out ->
                    // Часть с метаданными
                    val metaPart = buildString {
                        append(twoHyphens).append(boundary).append(lineEnd)
                        append("Content-Type: application/json; charset=UTF-8").append(lineEnd)
                        append(lineEnd)
                        append(metadataJson).append(lineEnd)
                    }
                    out.write(metaPart.toByteArray(Charsets.UTF_8))

                    // Часть с самим файлом
                    val fileHeaderPart = buildString {
                        append(twoHyphens).append(boundary).append(lineEnd)
                        append("Content-Type: text/plain; charset=UTF-8").append(lineEnd)
                        append(lineEnd)
                    }
                    out.write(fileHeaderPart.toByteArray(Charsets.UTF_8))

                    file.inputStream().use { input ->
                        input.copyTo(out)
                    }

                    // Закрывающий boundary
                    val endPart = buildString {
                        append(lineEnd)
                        append(twoHyphens).append(boundary).append(twoHyphens).append(lineEnd)
                    }
                    out.write(endPart.toByteArray(Charsets.UTF_8))
                    out.flush()
                }

                val code = connection.responseCode
                val body = run {
                    val stream = if (code in 200..299) {
                        connection.inputStream
                    } else {
                        connection.errorStream
                    }
                    stream?.use { s ->
                        BufferedReader(InputStreamReader(s)).readText()
                    } ?: ""
                }

                withContext(Dispatchers.Main) {
                    if (code in 200..299) {
                        Toast.makeText(
                            this@MainActivity,
                            "Игра загружена в Google Drive",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(
                            this@MainActivity,
                            "Ошибка загрузки в Drive: $code\n$body",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Исключение при загрузке в Drive (отправка файла): ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ----- Жизненный цикл / UI -----

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Если уже когда-то логинились, подхватываем аккаунт
        driveAccountEmail = GoogleSignIn
            .getLastSignedInAccount(this)
            ?.email

        // Конфигурация Google Sign-In с доступом к Google Drive (drive.file)
        val signInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope("https://www.googleapis.com/auth/drive.file")
            )
            .build()

        googleSignInClient = GoogleSignIn.getClient(this, signInOptions)

        setContent {
            HockeyScoreboardTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    ScoreboardScreen(
                        driveAccountEmail = driveAccountEmail,
                        onConnectDrive = { startGoogleSignIn() },
                        onGameSaved = { file -> uploadGameToDrive(file) },
                        onGameJsonUpdated = { file -> uploadGameToDrive(file) },
                        onNewGameStarted = {
                            // каждая НОВАЯ игра = новый файл на Google Drive
                            currentDriveFileId = null
                            currentDriveFileName = null
                        }

                    )

                }
            }
        }
    }
}
