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
    val assist2: String?,
    val eventOrder: Long = 0L      // общий порядок события в матче
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
                    goal.assist2 ?: "",
                    goal.eventOrder.toString()
                ).joinToString("§")
            }
        },
        restore = { saved ->
            saved.mapIndexed { index, line ->
                val parts = line.split("§")
                val eventOrder = parts.getOrNull(5)?.toLongOrNull() ?: index.toLong()

                GoalEvent(
                    id = parts[0].toLong(),
                    team = Team.valueOf(parts[1]),
                    scorer = parts[2],
                    assist1 = parts.getOrNull(3)?.ifEmpty { null },
                    assist2 = parts.getOrNull(4)?.ifEmpty { null },
                    eventOrder = eventOrder
                )
            }
        }
    )

// Изменение состава по ходу матча
data class RosterChangeEvent(
    val id: Long,
    val player: String,
    val fromTeam: Team?,   // null = был вне команд
    val toTeam: Team?,     // null = стал вне команд
    val eventOrder: Long = 0L   // общий порядок события в матче
)

// Saver для списка изменений составов
val RosterChangeEventListSaver: Saver<List<RosterChangeEvent>, List<String>> =
    Saver(
        save = { list ->
            list.map { ev ->
                listOf(
                    ev.id.toString(),
                    ev.player,
                    ev.fromTeam?.name ?: "",
                    ev.toTeam?.name ?: "",
                    ev.eventOrder.toString()
                ).joinToString("§")
            }
        },
        restore = { saved ->
            saved.mapIndexed { index, line ->
                val parts = line.split("§")
                val eventOrder = parts.getOrNull(4)?.toLongOrNull() ?: index.toLong()

                RosterChangeEvent(
                    id = parts[0].toLong(),
                    player = parts[1],
                    fromTeam = parts[2].ifEmpty { null }?.let { Team.valueOf(it) },
                    toTeam = parts[3].ifEmpty { null }?.let { Team.valueOf(it) },
                    eventOrder = eventOrder
                )
            }
        }
    )
