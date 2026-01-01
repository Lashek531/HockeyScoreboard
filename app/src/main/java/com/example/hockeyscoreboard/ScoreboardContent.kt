package com.example.hockeyscoreboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.hockeyscoreboard.model.GoalEvent
import com.example.hockeyscoreboard.model.Team

/**
 * Черновой вариант вынесенного основного экрана табло.
 * Пока НИГДЕ не используется и никак не влияет на работу приложения.
 * Имена функций другие, чтобы не конфликтовать с тем, что уже есть
 * в ScoreboardScreen.kt.
 */
@Composable
fun ScoreboardContentView(
    redScore: Int,
    whiteScore: Int,
    goals: List<GoalEvent>,
    gameFinished: Boolean,
    onTeamClick: (Team) -> Unit,
    onGoalClick: (GoalEvent) -> Unit,
    leftTeam: Team = Team.RED,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Верхнее табло – две карточки со счётом
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top
        ) {

            val leftIsRed = leftTeam == Team.RED

            val leftName = if (leftIsRed) "Красные" else "Белые"
            val leftScore = if (leftIsRed) redScore else whiteScore
            val leftBg = if (leftIsRed) Color(0xFFB71C1C) else Color(0xFFE0E0E0)
            val leftText = if (leftIsRed) Color(0xFFFFF8E1) else Color(0xFF071422)
            val leftClickTeam = if (leftIsRed) Team.RED else Team.WHITE

            val rightName = if (leftIsRed) "Белые" else "Красные"
            val rightScore = if (leftIsRed) whiteScore else redScore
            val rightBg = if (leftIsRed) Color(0xFFE0E0E0) else Color(0xFFB71C1C)
            val rightText = if (leftIsRed) Color(0xFF071422) else Color(0xFFFFF8E1)
            val rightClickTeam = if (leftIsRed) Team.WHITE else Team.RED

            TeamScoreCardView(
                teamName = leftName,
                score = leftScore,
                backgroundColor = leftBg,
                textColor = leftText,
                modifier = Modifier.weight(1f),
                onClick = { onTeamClick(leftClickTeam) }
            )

            Spacer(modifier = Modifier.width(16.dp))

            TeamScoreCardView(
                teamName = rightName,
                score = rightScore,
                backgroundColor = rightBg,
                textColor = rightText,
                modifier = Modifier.weight(1f),
                onClick = { onTeamClick(rightClickTeam) }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (!gameFinished)
                "Нажмите на счёт, чтобы добавить/изменить гол"
            else
                "Игра завершена. Редактирование отключено.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFB0BEC5)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Список всех голов по ходу матча
        if (goals.isNotEmpty()) {
            Text(
                text = "Голы по ходу матча",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFECEFF1),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            var runningRed = 0
            var runningWhite = 0

            goals.forEach { goal ->
                if (goal.team == Team.RED) runningRed++ else runningWhite++
                val scoreText = "$runningRed:$runningWhite"
                val teamName = if (goal.team == Team.RED) "Красные" else "Белые"
                val teamColor = if (goal.team == Team.RED) {
                    Color(0xFFFFCDD2)
                } else {
                    Color(0xFFCFD8DC)
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                        .clickable(
                            enabled = !gameFinished
                        ) { onGoalClick(goal) }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = scoreText,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFECEFF1),
                            modifier = Modifier.widthIn(min = 52.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = teamName,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = teamColor
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatGoalText(goal),
                        fontSize = 18.sp,
                        color = Color(0xFF81D4FA),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(64.dp))
    }
}

/**
 * Карточка с названием команды и крупным счётом.
 * Пока отдельное имя, чтобы не конфликтовать с исходной реализацией.
 */
@Composable
fun TeamScoreCardView(
    teamName: String,
    score: Int,
    backgroundColor: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .padding(8.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 16.dp, horizontal = 8.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = teamName,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = textColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = score.toString(),
                fontSize = 56.sp,
                fontWeight = FontWeight.ExtraBold,
                color = textColor
            )
        }
    }
}

/**
 * Форматирование строки "Гол" (забил + передачи).
 * Имя другое, чтобы не конфликтовало с formatGoal() в ScoreboardScreen.kt.
 */
fun formatGoalText(goal: GoalEvent): String {
    val assists = listOfNotNull(goal.assist1, goal.assist2)
    return if (assists.isEmpty()) {
        goal.scorer
    } else {
        "${goal.scorer} (${assists.joinToString(", ")})"
    }
}
