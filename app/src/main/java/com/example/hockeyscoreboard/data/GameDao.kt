package com.example.hockeyscoreboard.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface GameDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertGame(entry: GameEntry)

    @Query("SELECT * FROM games ORDER BY startedAt DESC")
    fun getAllGames(): List<GameEntry>

    @Query("DELETE FROM games WHERE gameId = :gameId")
    fun deleteGameById(gameId: String)

    @Query("DELETE FROM games")
    fun deleteAll()
}
