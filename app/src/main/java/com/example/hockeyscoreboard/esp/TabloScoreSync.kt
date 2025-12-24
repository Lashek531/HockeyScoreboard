package com.example.hockeyscoreboard.esp

import com.example.hockeyscoreboard.model.Team

/**
 * Описывает, какая команда сейчас находится СЛЕВА на физическом табло.
 *
 * В проекте принят набор кнопок управления счётом:
 *  - ЛЕВЫЙ счёт: + = "1", − = "4"
 *  - ПРАВЫЙ счёт: + = "3", − = "6"
 */
data class TabloScoreLayout(
    val leftTeam: Team
) {
    val rightTeam: Team
        get() = if (leftTeam == Team.RED) Team.WHITE else Team.RED
}

/**
 * Возвращает кнопку табло для изменения счёта [team] на [delta].
 *
 * [delta] должен быть +1 или -1. Для других значений вернёт null.
 */
fun TabloScoreLayout.buttonFor(team: Team, delta: Int): String? {
    if (delta != 1 && delta != -1) return null

    val isLeft = team == leftTeam
    val isRight = team == rightTeam
    if (!isLeft && !isRight) return null

    return if (isLeft) {
        if (delta > 0) "1" else "4"
    } else {
        if (delta > 0) "3" else "6"
    }
}
