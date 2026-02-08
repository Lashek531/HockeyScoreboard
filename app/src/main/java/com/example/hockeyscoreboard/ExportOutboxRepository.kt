package com.example.hockeyscoreboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class ExportOutboxStatus { PENDING, SENT, FAILED, SENDING }

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

class ExportOutboxRepository(private val context: Context) {

    private val lock = ReentrantLock()

    private fun getDbRoot(): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    private fun outboxDir(): File =
        File(getDbRoot(), "hockey-json/outbox").apply { if (!exists()) mkdirs() }

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

    /**
     * Защита от дублей: "захват" отправки.
     * Возвращает true, если удалось перевести запись в SENDING.
     * Если запись уже SENT или уже SENDING — вернёт false.
     */
    fun tryMarkSending(gameId: String): Boolean = lock.withLock {
        val state = readState()
        val idx = state.items.indexOfFirst { it.gameId == gameId }
        if (idx < 0) return@withLock false

        val item = state.items[idx]
        if (item.status == ExportOutboxStatus.SENT || item.status == ExportOutboxStatus.SENDING) {
            return@withLock false
        }

        val newItems = state.items.toMutableList()
        newItems[idx] = item.copy(
            status = ExportOutboxStatus.SENDING,
            updatedAt = System.currentTimeMillis()
        )
        writeState(newItems)
        true
    }

    fun markSent(gameId: String) = lock.withLock {
        update(gameId) {
            it.copy(
                status = ExportOutboxStatus.SENT,
                updatedAt = System.currentTimeMillis(),
                lastError = null
            )
        }
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

        try {
            fun parseValue(raw: String): Any? = JSONTokener(raw).nextValue()

            var value: Any? = parseValue(text)

            // Иногда файл может содержать JSON как строку:  "{...}" или "[...]"
            if (value is String) {
                val s = value.trim()
                if ((s.startsWith("{") && s.endsWith("}")) || (s.startsWith("[") && s.endsWith("]"))) {
                    value = parseValue(s)
                }
            }

            val version: Int
            val arr: JSONArray = when (value) {
                is JSONObject -> {
                    version = value.optInt("version", 1)
                    value.optJSONArray("items") ?: JSONArray()
                }
                is JSONArray -> {
                    // Старый/упрощённый формат: файл = массив items
                    version = 1
                    value
                }
                else -> {
                    // Неожиданный тип — считаем как пустое состояние
                    return State(version = 1, items = emptyList())
                }
            }

            val items = buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        ExportOutboxItem(
                            gameId = obj.optString("gameId", ""),
                            season = obj.optString("season", ""),
                            eventId = obj.optInt("eventId", 0),
                            exportFileName = obj.optString("exportFileName", ""),
                            status = ExportOutboxStatus.valueOf(
                                obj.optString("status", ExportOutboxStatus.PENDING.name)
                            ),
                            attempts = obj.optInt("attempts", 0),
                            lastError = obj.optString("lastError", null),
                            updatedAt = obj.optLong("updatedAt", 0L)
                        )
                    )
                }
            }.filter { it.gameId.isNotBlank() && it.season.isNotBlank() && it.exportFileName.isNotBlank() }

            return State(version = version, items = items)
        } catch (e: Exception) {
            // Файл повреждён/непарсится — чтобы не падать, бэкапнем и начнем заново
            runCatching {
                val backup = File(f.parentFile, "outbox.bad.${System.currentTimeMillis()}.json")
                f.copyTo(backup, overwrite = true)
                f.delete()
            }
            return State(version = 1, items = emptyList())
        }
    }


    private fun writeState(items: List<ExportOutboxItem>) {
        val root = JSONObject()
        root.put("version", 1)

        val arr = JSONArray()
        items.forEach {
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
