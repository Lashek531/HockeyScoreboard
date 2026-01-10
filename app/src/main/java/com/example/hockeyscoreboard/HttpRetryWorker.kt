package com.example.hockeyscoreboard

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.hockeyscoreboard.data.RaspiRepository
import com.example.hockeyscoreboard.data.SettingsRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class HttpRetryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settingsRepo = SettingsRepositoryImpl(applicationContext)
        val raspiRepo = RaspiRepository(settingsRepo)

        val outboxRepo = HttpOutboxRepository(applicationContext)

        val items = outboxRepo.getAll()

        for (item in items) {
            if (item.status == HttpOutboxStatus.SENT) continue
            if (item.attempts >= MAX_ATTEMPTS) continue

            if (!outboxRepo.tryMarkSending(item.gameId, item.season)) continue

            val finishedFile = File(item.finishedFilePath)
            if (!finishedFile.exists()) {
                outboxRepo.markFailed(item.gameId, item.season, "Finished JSON not found: ${finishedFile.absolutePath}")
                continue
            }

            val jsonResult = runCatching { finishedFile.readText(Charsets.UTF_8) }
            if (jsonResult.isFailure) {
                outboxRepo.markFailed(
                    item.gameId,
                    item.season,
                    "Read finished JSON failed: ${jsonResult.exceptionOrNull()?.message ?: "Unknown error"}"
                )
                continue
            }

            val json = jsonResult.getOrThrow()


            val res = raspiRepo.uploadFinishedGame(json)
            if (res.success) {
                outboxRepo.markSent(item.gameId, item.season)
            } else {
                outboxRepo.markFailed(item.gameId, item.season, res.errorMessage ?: "HTTP upload failed")
            }
        }

        Result.success()
    }

    companion object {
        private const val MAX_ATTEMPTS = 30
    }
}
