package com.example.hockeyscoreboard

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.hockeyscoreboard.data.SettingsRepositoryImpl
import com.example.hockeyscoreboard.data.getSeasonFinishedDir
import com.example.hockeyscoreboard.data.loadBasePlayers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ExportRetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val outboxRepo = ExportOutboxRepository(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = SettingsRepositoryImpl(applicationContext)

        val token = settings.getTelegramBotToken().trim()
        val defaultChatId = settings.getTelegramBotChatId().trim()

        // Если не настроено — ретраить бессмысленно; считаем, что “выполнено”, чтобы не крутить бесконечно.
        if (token.isBlank() || defaultChatId.isBlank()) {
            return@withContext Result.success()
        }

        val prefs = applicationContext.getSharedPreferences("hockey_prefs", Context.MODE_PRIVATE)
        val basePlayers = loadBasePlayers(prefs)

        val items = outboxRepo.getAll()
            .filter { it.status == ExportOutboxStatus.PENDING || it.status == ExportOutboxStatus.FAILED }

        if (items.isEmpty()) {
            return@withContext Result.success()
        }

        for (item in items) {
            // Лимит попыток: чтобы не долбить бесконечно
            if (item.attempts >= MAX_ATTEMPTS) continue

            val exportFile = resolveExportFile(item.exportFileName)

            // Если файл пропал — восстанавливаем из archived finished JSON
            val ensuredFile: File = if (exportFile.exists()) {
                exportFile
            } else {
                val finishedFile = resolveFinishedFile(item.season, item.gameId)
                if (!finishedFile.exists()) {
                    outboxRepo.markFailed(item.gameId, "Finished JSON not found: ${finishedFile.absolutePath}")
                    continue
                }
                val generated = ArchivedExportGenerator.generate(
                    context = applicationContext,
                    finishedGameFile = finishedFile,
                    basePlayers = basePlayers,
                    eventIdOverride = item.eventId
                )
                generated.exportFile
            }

            val sendResult = TelegramDocumentSender.sendDocument(
                token = token,
                chatId = defaultChatId,
                file = ensuredFile,
                contentType = "application/json"
            )

            if (sendResult.isSuccess) {
                outboxRepo.markSent(item.gameId)
            } else {
                val err = sendResult.exceptionOrNull()?.message ?: "Unknown send error"
                outboxRepo.markFailed(item.gameId, err)
            }
        }

        Result.success()
    }

    private fun resolveExportFile(exportFileName: String): File {
        val baseDir = applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir
        val exportDir = File(baseDir, "hockey-json/external-events-api")
        return File(exportDir, exportFileName)
    }

    private fun resolveFinishedFile(season: String, gameId: String): File {
        val seasonDir = getSeasonFinishedDir(applicationContext, season)
        return File(seasonDir, "$gameId.json")
    }

    companion object {
        private const val MAX_ATTEMPTS = 8
    }
}
