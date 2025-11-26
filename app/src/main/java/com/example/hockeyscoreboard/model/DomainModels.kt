package com.example.hockeyscoreboard.model


import androidx.compose.runtime.saveable.Saver

// Амплуа игрока
enum class PlayerRole {
    DEFENDER,
    FORWARD,
    UNIVERSAL
}

// Информация об игроке в базовом списке
data class PlayerInfo(
    val name: String,
    val role: PlayerRole = PlayerRole.UNIVERSAL,
    val rating: Int = 0
)

// Команда
enum class Team {
    RED,
    WHITE
}

// Одно событие гола в матче
data class GoalEvent(
    val id: Long,
    val team: Team,
    val scorer: String,
    val assist1: String?,
    val assist2: String?
)

// Сводная статистика игрока по всем сохранённым играм
data class PlayerStats(
    var games: Int = 0,
    var goals: Int = 0,
    var assists: Int = 0
) {
    val points: Int
        get() = goals + assists
}

// Строка таблицы статистики
data class PlayerStatsRow(
    val rank: Int,
    val name: String,
    val games: Int,
    val goals: Int,
    val assists: Int,
    val points: Int
)

// Saver для списка голов
val GoalEventListSaver: Saver<List<GoalEvent>, List<String>> =
    Saver(
        save = { list ->
            list.map { goal ->
                listOf(
                    goal.id.toString(),
                    goal.team.name,
                    goal.scorer,
                    goal.assist1 ?: "",
                    goal.assist2 ?: ""
                ).joinToString("§")
            }
        },
        restore = { saved ->
            saved.map { line ->
                val parts = line.split("§")
                GoalEvent(
                    id = parts[0].toLong(),
                    team = Team.valueOf(parts[1]),
                    scorer = parts[2],
                    assist1 = parts[3].ifEmpty { null },
                    assist2 = parts[4].ifEmpty { null }
                )
            }
        }
    )
