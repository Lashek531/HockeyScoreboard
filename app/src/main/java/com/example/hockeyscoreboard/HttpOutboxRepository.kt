package com.example.hockeyscoreboard

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

enum class HttpOutboxStatus { PENDING, SENT, FAILED, SENDING }

data class HttpOutboxItem(
    val gameId: String,
    val season: String,
    val finishedFilePath: String,
    val status: HttpOutboxStatus,
    val attempts: Int,
    val lastError: String?,
    val updatedAt: Long
)

class HttpOutboxRepository(private val context: Context) {

    private val lock = ReentrantLock()

    private fun getDbRoot(): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    private fun outboxDir(): File =
        File(getDbRoot(), "hockey-json/outbox").apply { if (!exists()) mkdirs() }

    private fun outboxFile(): File =
        File(outboxDir(), "http_outbox.json")

    fun getAll(): List<HttpOutboxItem> = lock.withLock { readState().items }

    fun upsertPending(gameId: String, season: String, finishedFilePath: String): HttpOutboxItem = lock.withLock {
        val state = readState()
        val now = System.currentTimeMillis()

        val newItem = HttpOutboxItem(
            gameId = gameId,
            season = season,
            finishedFilePath = finishedFilePath,
            status = HttpOutboxStatus.PENDING,
            attempts = 0,
            lastError = null,
            updatedAt = now
        )

        val newItems = state.items
            .filterNot { it.gameId == gameId && it.season == season }
            .toMutableList()
            .apply { add(newItem) }

        writeState(newItems)
        newItem
    }

    fun tryMarkSending(gameId: String, season: String): Boolean = lock.withLock {
        val state = readState()
        val idx = state.items.indexOfFirst { it.gameId == gameId && it.season == season }
        if (idx < 0) return@withLock false

        val item = state.items[idx]
        if (item.status == HttpOutboxStatus.SENT || item.status == HttpOutboxStatus.SENDING) {
            return@withLock false
        }

        val newItems = state.items.toMutableList()
        newItems[idx] = item.copy(
            status = HttpOutboxStatus.SENDING,
            updatedAt = System.currentTimeMillis()
        )
        writeState(newItems)
        true
    }

    fun markSent(gameId: String, season: String) = lock.withLock {
        update(gameId, season) { it.copy(status = HttpOutboxStatus.SENT, updatedAt = System.currentTimeMillis(), lastError = null) }
    }

    fun markFailed(gameId: String, season: String, error: String) = lock.withLock {
        update(gameId, season) {
            it.copy(
                status = HttpOutboxStatus.FAILED,
                attempts = it.attempts + 1,
                lastError = error,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    private fun update(
        gameId: String,
        season: String,
        transform: (HttpOutboxItem) -> HttpOutboxItem
    ) {
        val state = readState()
        val idx = state.items.indexOfFirst { it.gameId == gameId && it.season == season }
        if (idx < 0) return

        val newItems = state.items.toMutableList()
        newItems[idx] = transform(newItems[idx])
        writeState(newItems)
    }

    private data class State(val version: Int, val items: List<HttpOutboxItem>)

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
                    // Старый формат: файл = массив items
                    version = 1
                    value
                }
                else -> {
                    return State(version = 1, items = emptyList())
                }
            }

            val items = buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue

                    val statusStr = obj.optString("status", HttpOutboxStatus.PENDING.name)
                    val status = runCatching { HttpOutboxStatus.valueOf(statusStr) }
                        .getOrElse { HttpOutboxStatus.PENDING }

                    add(
                        HttpOutboxItem(
                            gameId = obj.optString("gameId", ""),
                            season = obj.optString("season", ""),
                            finishedFilePath = obj.optString("finishedFilePath", ""),
                            status = status,
                            attempts = obj.optInt("attempts", 0),
                            lastError = obj.optString("lastError", null),
                            updatedAt = obj.optLong("updatedAt", 0L)
                        )
                    )
                }
            }.filter { it.gameId.isNotBlank() && it.season.isNotBlank() && it.finishedFilePath.isNotBlank() }

            return State(version = version, items = items)
        } catch (e: Exception) {
            // Файл повреждён/непарсится — чтобы не падать, бэкапнем и начнем заново
            runCatching {
                val backup = File(f.parentFile, "http_outbox.bad.${System.currentTimeMillis()}.json")
                f.copyTo(backup, overwrite = true)
                f.delete()
            }
            return State(version = 1, items = emptyList())
        }
    }


    private fun writeState(items: List<HttpOutboxItem>) {
        val root = JSONObject()
        root.put("version", 1)

        val arr = JSONArray()
        items.forEach {
            val obj = JSONObject()
            obj.put("gameId", it.gameId)
            obj.put("season", it.season)
            obj.put("finishedFilePath", it.finishedFilePath)
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
