package com.example.hockeyscoreboard.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [GameEntry::class],
    version = 2,
    exportSchema = false
)
abstract class GameDatabase : RoomDatabase() {

    abstract fun gameDao(): GameDao

    companion object {
        @Volatile
        private var INSTANCE: GameDatabase? = null

        fun getInstance(context: Context): GameDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    GameDatabase::class.java,
                    "hockey_games.db"
                )
                    // нам тут только маленький индекс, поэтому упрощаем жизнь:
                    .fallbackToDestructiveMigration()
                    .allowMainThreadQueries()   // можно дергать DAO прямо из UI
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
