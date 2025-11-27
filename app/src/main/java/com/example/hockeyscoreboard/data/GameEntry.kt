package com.example.hockeyscoreboard.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Индекс одной игры в локальной БД.
 * JSON-файл остаётся основным источником протокола;
 * здесь только метаданные для быстрого списка.
 */
@Entity(tableName = "games")
data class GameEntry(
    @PrimaryKey
    val gameId: String,      // например "2025-11-27_16-35-12_pestovo"

    val fileName: String,    // "2025-11-27_16-35-12_pestovo.json"
    val localPath: String?,  // абсолютный путь к файлу

    val startedAt: Long,     // millis начала игры
    val finishedAt: Long?,   // millis окончания (когда нажали "Завершить")

    val redScore: Int,
    val whiteScore: Int
)
