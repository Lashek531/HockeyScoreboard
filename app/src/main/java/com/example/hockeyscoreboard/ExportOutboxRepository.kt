package com.example.hockeyscoreboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class ExportOutboxStatus { PENDING, SENT, FAILED }

data class ExportOutboxItem(
    val gameId: String,
    val season: String,
    val eventId: Int,
    val exportFileName: String, // например: result_11.json
    val status: ExportOutboxStatus,
    val attempts: Int,
    val lastError: String?,
    val updatedAt: Long
)

class ExportOutboxRepository(
    private val context: Context
) {
    private val lock = ReentrantLock()

    private fun getDbRoot(): File {
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(baseDir, "hockey-json").apply { if (!exists()) mkdirs() }
    }

    private fun outboxDir(): File =
        File(getDbRoot(), "outbox").apply { if (!exists()) mkdirs() }

    private fun outboxFile(): File =
        File(outboxDir(), "outbox.json")

    fun getAll(): List<ExportOutboxItem> = lock.withLock {
        readState().items
    }

    fun getByGameId(gameId: String): ExportOutboxItem? = lock.withLock {
        readState().items.firstOrNull { it.gameId == gameId }
    }

    /**
     * Upsert по ключу gameId. "Последний победил":
     * - если игра уже была в outbox, запись заменяется
     * - если eventId изменился, вызывающий код позже сможет удалить старый result_<old>.json (сделаем на следующем шаге)
     */
    fun upsertPending(
        gameId: String,
        season: String,
        eventId: Int,
        exportFileName: String
    ): ExportOutboxItem = lock.withLock {
        val state = readState()
        val now = System.currentTimeMillis()

        val newItem = ExportOutboxItem(
            gameId = gameId,
            season = season,
            eventId = eventId,
            exportFileName = exportFileName,
            status = ExportOutboxStatus.PENDING,
            attempts = 0,
            lastError = null,
            updatedAt = now
        )

        val newItems = state.items
            .filterNot { it.gameId == gameId }
            .toMutableList()
            .apply { add(newItem) }

        writeState(newItems)
        newItem
    }

    fun markSent(gameId: String) = lock.withLock {
        update(gameId) { it.copy(status = ExportOutboxStatus.SENT, updatedAt = System.currentTimeMillis(), lastError = null) }
    }

    fun markFailed(gameId: String, error: String) = lock.withLock {
        update(gameId) {
            it.copy(
                status = ExportOutboxStatus.FAILED,
                attempts = it.attempts + 1,
                lastError = error,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private fun update(gameId: String, transform: (ExportOutboxItem) -> ExportOutboxItem) {
        val state = readState()
        val idx = state.items.indexOfFirst { it.gameId == gameId }
        if (idx < 0) return

        val newItems = state.items.toMutableList()
        newItems[idx] = transform(newItems[idx])
        writeState(newItems)
    }

    private data class State(val version: Int, val items: List<ExportOutboxItem>)

    private fun readState(): State {
        val f = outboxFile()
        if (!f.exists()) return State(version = 1, items = emptyList())

        val text = f.readText(Charsets.UTF_8).trim()
        if (text.isEmpty()) return State(version = 1, items = emptyList())

        val root = JSONObject(text)
        val version = root.optInt("version", 1)
        val arr = root.optJSONArray("items") ?: JSONArray()

        val items = buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(
                    ExportOutboxItem(
                        gameId = obj.optString("gameId", ""),
                        season = obj.optString("season", ""),
                        eventId = obj.optInt("eventId", 0),
                        exportFileName = obj.optString("exportFileName", ""),
                        status = ExportOutboxStatus.valueOf(obj.optString("status", ExportOutboxStatus.PENDING.name)),
                        attempts = obj.optInt("attempts", 0),
                        lastError = obj.optString("lastError", null),
                        updatedAt = obj.optLong("updatedAt", 0L)
                    )
                )
            }
        }.filter { it.gameId.isNotBlank() && it.season.isNotBlank() && it.exportFileName.isNotBlank() }

        return State(version = version, items = items)
    }

    private fun writeState(items: List<ExportOutboxItem>) {
        val root = JSONObject()
        root.put("version", 1)

        val arr = JSONArray()
        for (it in items) {
            val obj = JSONObject()
            obj.put("gameId", it.gameId)
            obj.put("season", it.season)
            obj.put("eventId", it.eventId)
            obj.put("exportFileName", it.exportFileName)
            obj.put("status", it.status.name)
            obj.put("attempts", it.attempts)
            obj.put("lastError", it.lastError)
            obj.put("updatedAt", it.updatedAt)
            arr.put(obj)
        }
        root.put("items", arr)

        outboxFile().writeText(root.toString(), Charsets.UTF_8)
    }
}
