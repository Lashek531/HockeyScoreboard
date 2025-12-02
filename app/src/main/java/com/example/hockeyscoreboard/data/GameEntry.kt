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
    val gameId: String,      // по сути тот же id, который мы кладём в JSON

    val season: String,      // "25-26", "26-27", ...

    val fileName: String,
    val localPath: String?,

    val startedAt: Long,
    val finishedAt: Long?,

    val redScore: Int,
    val whiteScore: Int
)

