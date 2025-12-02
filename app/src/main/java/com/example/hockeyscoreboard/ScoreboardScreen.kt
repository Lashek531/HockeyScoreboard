package com.example.hockeyscoreboard

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.example.hockeyscoreboard.data.*
import com.example.hockeyscoreboard.data.db.GameDatabase
import com.example.hockeyscoreboard.data.db.GameEntry
import com.example.hockeyscoreboard.model.*
import com.example.hockeyscoreboard.ui.theme.HockeyScoreboardTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.hockeyscoreboard.data.getSeasonFinishedDir
import com.example.hockeyscoreboard.data.getCurrentSeason
import com.example.hockeyscoreboard.data.setCurrentSeason

import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

import com.example.hockeyscoreboard.data.SettingsRepositoryImpl
import com.example.hockeyscoreboard.data.SyncRepository
import com.example.hockeyscoreboard.data.SyncResult


// --- Цвета для всплывающих окон в общем стиле ---

private val DialogBackground = Color(0xFF10202B)
private val DialogTitleColor = Color(0xFFECEFF1)
private val DialogTextColor = Color(0xFFCFD8DC)

// Единые цвета для текста кнопок диалогов
@Composable
private fun dialogButtonColors() = ButtonDefaults.textButtonColors(
    contentColor = Color(0xFF81D4FA)
)

@Composable
private fun dialogDangerButtonColors() = ButtonDefaults.textButtonColors(
    contentColor = Color(0xFFFF8A80)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreboardScreen(
    onGameSaved: (File) -> Unit = {},
    onGameJsonUpdated: (File) -> Unit = {},
    onNewGameStarted: () -> Unit = {},
    onGameDeleted: (gameId: String, file: File?) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("hockey_prefs", Context.MODE_PRIVATE)
    }

    // Текущий сезон
    var currentSeason by remember {
        mutableStateOf(getCurrentSeason(context))
    }

    // Локальная БД для индекса игр
    val gameDb = remember { GameDatabase.getInstance(context) }
    val gameDao = remember { gameDb.gameDao() }

    // БАЗОВЫЙ СПИСОК ИГРОКОВ
    var basePlayers by remember {
        mutableStateOf(loadBasePlayers(prefs))
    }
    var newPlayerName by remember { mutableStateOf("") }

    // СОСТАВЫ КОМАНД
    var playersRedText by rememberSaveable { mutableStateOf("") }
    var playersWhiteText by rememberSaveable { mutableStateOf("") }

    val playersRed: List<String> = remember(playersRedText) {
        playersRedText.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }
    val playersWhite: List<String> = remember(playersWhiteText) {
        playersWhiteText.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    // доступные для распределения игроки
    val availablePlayers: List<String> = remember(basePlayers, playersRed, playersWhite) {
        basePlayers.map { it.name }
            .filter { it !in playersRed && it !in playersWhite }
            .sorted()
    }

    // ФЛАГИ ДИАЛОГОВ / МЕНЮ
    var showBasePlayersDialog by remember { mutableStateOf(false) }
    var showLineupsDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showHistoryDetailsDialog by remember { mutableStateOf(false) }
    var showFinishConfirm by remember { mutableStateOf(false) }
    var showNewGameConfirm by remember { mutableStateOf(false) }
    var showActionsMenu by remember { mutableStateOf(false) }
    var showNoTeamsDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Текущая выбранная игра (для истории)
    var historySelectedEntry by remember { mutableStateOf<GameEntry?>(null) }
    var historySelectedFile by remember { mutableStateOf<File?>(null) }
    var historyDetailsText by remember { mutableStateOf("") }

    // подтверждение удаления сохранённой игры
    var showDeleteGameConfirm by remember { mutableStateOf(false) }

    // ИГРА / ГОЛЫ / ПРОТОКОЛ
    var goals by rememberSaveable(stateSaver = GoalEventListSaver) {
        mutableStateOf(listOf<GoalEvent>())
    }

    var rosterChanges by rememberSaveable(stateSaver = RosterChangeEventListSaver) {
        mutableStateOf(listOf<RosterChangeEvent>())
    }

    var nextGoalId by rememberSaveable { mutableStateOf(1L) }
    var nextRosterChangeId by rememberSaveable { mutableStateOf(1L) }
    var nextEventOrder by rememberSaveable { mutableStateOf(1L) }

    // Время старта текущей игры (для стабильного gameId / имени файла)
    var gameStartMillis by rememberSaveable { mutableStateOf<Long?>(null) }

    val redScore = goals.count { it.team == Team.RED }
    val whiteScore = goals.count { it.team == Team.WHITE }

    // Снапшоты составов на момент открытия диалога "Составы команд"
    var lastLineupsRedSnapshot by remember { mutableStateOf<List<String>>(emptyList()) }
    var lastLineupsWhiteSnapshot by remember { mutableStateOf<List<String>>(emptyList()) }
    var hasBaselineLineups by rememberSaveable { mutableStateOf(false) }

    var goalInputTeam by remember { mutableStateOf<Team?>(null) }
    var editingGoalId by remember { mutableStateOf<Long?>(null) }
    var tempScorer by remember { mutableStateOf<String?>(null) }
    var tempAssist1 by remember { mutableStateOf<String?>(null) }
    var tempAssist2 by remember { mutableStateOf<String?>(null) }

    var goalOptionsFor by remember { mutableStateOf<GoalEvent?>(null) }

    var gameFinished by rememberSaveable { mutableStateOf(false) }

    // --- УТИЛИТЫ ---

    fun resetGoalInput() {
        goalInputTeam = null
        editingGoalId = null
        tempScorer = null
        tempAssist1 = null
        tempAssist2 = null
    }

    fun resetGameState() {
        // сбрасываем голы и протокол
        goals = emptyList()
        rosterChanges = emptyList()
        nextGoalId = 1L
        nextRosterChangeId = 1L
        nextEventOrder = 1L
        gameFinished = false
        gameStartMillis = null

        // сбрасываем составы команд (текстовые поля → пустые строки)
        playersRedText = ""
        playersWhiteText = ""

        // очищаем снапшоты составов
        lastLineupsRedSnapshot = emptyList()
        lastLineupsWhiteSnapshot = emptyList()
        hasBaselineLineups = false

        resetGoalInput()
        goalOptionsFor = null
    }


    fun logRosterChangesFromDialog() {
        // Первый вызов: фиксируем базовый состав, но без событий
        if (!hasBaselineLineups) {
            lastLineupsRedSnapshot = playersRed
            lastLineupsWhiteSnapshot = playersWhite
            hasBaselineLineups = true
            return
        }

        val baseNames = basePlayers.map { it.name }.toSet()

        val beforeRed = lastLineupsRedSnapshot.toSet()
        val beforeWhite = lastLineupsWhiteSnapshot.toSet()
        val beforeNone = baseNames - beforeRed - beforeWhite

        val afterRed = playersRed.toSet()
        val afterWhite = playersWhite.toSet()
        val afterNone = baseNames - afterRed - afterWhite

        val allNames = (beforeRed + beforeWhite + beforeNone +
                afterRed + afterWhite + afterNone).toSet()

        val newEvents = mutableListOf<RosterChangeEvent>()

        for (name in allNames) {
            val fromTeam = when {
                name in beforeRed -> Team.RED
                name in beforeWhite -> Team.WHITE
                else -> null
            }
            val toTeam = when {
                name in afterRed -> Team.RED
                name in afterWhite -> Team.WHITE
                else -> null
            }

            if (fromTeam != toTeam) {
                newEvents += RosterChangeEvent(
                    id = nextRosterChangeId++,
                    player = name,
                    fromTeam = fromTeam,
                    toTeam = toTeam,
                    eventOrder = nextEventOrder++
                )
            }
        }

        // Не добавляем переходы в протокол, пока в игре нет ни одного гола
        if (goals.isNotEmpty() && newEvents.isNotEmpty()) {
            rosterChanges = rosterChanges + newEvents
        }

        lastLineupsRedSnapshot = playersRed
        lastLineupsWhiteSnapshot = playersWhite
    }

    fun startNewGoal(team: Team) {
        if (gameFinished) return

        val teamEmpty =
            (team == Team.RED && playersRed.isEmpty()) ||
                    (team == Team.WHITE && playersWhite.isEmpty())

        if (teamEmpty) {
            showNoTeamsDialog = true
            return
        }

        goalInputTeam = team
        editingGoalId = null
        tempScorer = null
        tempAssist1 = null
        tempAssist2 = null
    }

    fun startEditGoal(event: GoalEvent) {
        if (gameFinished) return
        goalInputTeam = event.team
        editingGoalId = event.id
        tempScorer = null
        tempAssist1 = null
        tempAssist2 = null
    }

    fun buildGameJson(isFinal: Boolean = false): Pair<String, String> {
        val fileFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())

        // Старт игры фиксируем один раз
        val startMillis = gameStartMillis ?: System.currentTimeMillis().also { gameStartMillis = it }
        val startDate = Date(startMillis)

        val baseId = fileFormat.format(startDate) + "_pestovo"   // без .json
        val fileName = "$baseId.json"
        val dateIso = isoFormat.format(startDate)

        val season = currentSeason

        val root = org.json.JSONObject()

        // Два идентификатора, чтобы всем было хорошо:
        root.put("id", baseId)          // то, что ждёт Raspi для finished/<season>/<id>.json
        root.put("gameId", baseId)      // как и раньше внутри приложения

        root.put("arena", "Пестово Арена")
        root.put("date", dateIso)
        root.put("season", season)
        root.put("finished", isFinal)

        val teamsObj = org.json.JSONObject()
        val redObj = org.json.JSONObject()
        val whiteObj = org.json.JSONObject()

        redObj.put("name", "Красные")
        whiteObj.put("name", "Белые")

        val redPlayersArray = org.json.JSONArray()
        playersRed.forEach { redPlayersArray.put(it) }
        redObj.put("players", redPlayersArray)

        val whitePlayersArray = org.json.JSONArray()
        playersWhite.forEach { whitePlayersArray.put(it) }
        whiteObj.put("players", whitePlayersArray)

        teamsObj.put("RED", redObj)
        teamsObj.put("WHITE", whiteObj)

        root.put("teams", teamsObj)

        val currentRedScore = goals.count { it.team == Team.RED }
        val currentWhiteScore = goals.count { it.team == Team.WHITE }

        val scoreObj = org.json.JSONObject()
        scoreObj.put("RED", currentRedScore)
        scoreObj.put("WHITE", currentWhiteScore)
        root.put("finalScore", scoreObj)

        val goalsArray = org.json.JSONArray()
        var runningRed = 0
        var runningWhite = 0

        goals.sortedBy { it.eventOrder }.forEach { goal ->
            if (goal.team == Team.RED) runningRed++ else runningWhite++
            val goalObj = org.json.JSONObject()
            goalObj.put("team", goal.team.name)
            goalObj.put("scoreAfter", "${runningRed}:${runningWhite}")
            goalObj.put("scorer", goal.scorer)
            goalObj.put("assist1", goal.assist1)
            goalObj.put("assist2", goal.assist2)
            goalObj.put("order", goal.eventOrder)
            goalsArray.put(goalObj)
        }

        root.put("goals", goalsArray)

        val rosterArray = org.json.JSONArray()
        rosterChanges.sortedBy { it.eventOrder }.forEach { ev ->
            val evObj = org.json.JSONObject()
            evObj.put("id", ev.id)
            evObj.put("player", ev.player)
            evObj.put("fromTeam", ev.fromTeam?.name)
            evObj.put("toTeam", ev.toTeam?.name)
            evObj.put("order", ev.eventOrder)
            rosterArray.put(evObj)
        }
        root.put("rosterChanges", rosterArray)

        return fileName to root.toString(2)
    }

    fun saveGameJsonToFile(isFinal: Boolean = false): File {
        val (fileName, json) = buildGameJson(isFinal)

        val seasonLocal = currentSeason
        val dir = getSeasonFinishedDir(context, seasonLocal)
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, fileName)
        file.writeText(json, Charsets.UTF_8)
        return file
    }

    /**
     * Любое обновление игры.
     * 1) сохраняем JSON,
     * 2) обновляем запись в Room,
     * 3) уведомляем наружу (MainActivity решает, что делать дальше).
     */
    fun notifyGameJsonUpdated(isFinal: Boolean = false) {
        val file = saveGameJsonToFile(isFinal)

        val now = System.currentTimeMillis()
        val finishedAt = if (isFinal) now else null
        val startedAt = gameStartMillis ?: now
        val gameId = file.name.removeSuffix(".json")
        val seasonLocal = currentSeason

        val entry = GameEntry(
            gameId = gameId,
            fileName = file.name,
            season = seasonLocal,
            localPath = file.absolutePath,
            startedAt = startedAt,
            finishedAt = finishedAt,
            redScore = redScore,
            whiteScore = whiteScore
        )
        gameDao.upsertGame(entry)

        if (isFinal) {
            onGameSaved(file)
        } else {
            onGameJsonUpdated(file)
        }
    }

    fun commitGoalIfPossible() {
        if (gameFinished) return
        val team = goalInputTeam ?: return
        val scorer = tempScorer ?: return

        val id = editingGoalId ?: nextGoalId++

        val existingOrder = goals.find { it.id == id }?.eventOrder
        val order = existingOrder ?: nextEventOrder++

        val newEvent = GoalEvent(
            id = id,
            team = team,
            scorer = scorer,
            assist1 = tempAssist1,
            assist2 = tempAssist2,
            eventOrder = order
        )

        goals = if (editingGoalId == null) {
            goals + newEvent
        } else {
            goals.map { if (it.id == editingGoalId) newEvent else it }
        }

        notifyGameJsonUpdated(isFinal = false)
        resetGoalInput()
    }

    fun handlePlayerClick(player: String) {
        if (gameFinished) return
        when {
            tempScorer == null -> tempScorer = player
            tempAssist1 == null -> tempAssist1 = player
            tempAssist2 == null -> {
                tempAssist2 = player
                commitGoalIfPossible()
            }
        }
    }

    fun exportGameFile(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )

            val pm = context.packageManager

            fun makeSendIntent(mimeType: String): Intent =
                Intent(Intent.ACTION_SEND).apply {
                    type = mimeType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_TITLE, file.name)
                    putExtra(Intent.EXTRA_SUBJECT, file.name)
                    putExtra(Intent.EXTRA_TEXT, "Файл протокола: ${file.name}")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

            val jsonIntent = makeSendIntent("application/json")

            val finalIntent =
                if (jsonIntent.resolveActivity(pm) != null) {
                    jsonIntent
                } else {
                    makeSendIntent("*/*")
                }

            if (finalIntent.resolveActivity(pm) != null) {
                context.startActivity(
                    Intent.createChooser(finalIntent, "Экспортировать игру")
                )
            } else {
                Toast.makeText(
                    context,
                    "Нет приложений, которые могут принять этот файл",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                context,
                "Ошибка экспорта: ${e.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // --- ОСНОВНОЙ ЭКРАН ---

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Пестово Арена",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF10202B),
                    titleContentColor = Color(0xFFECEFF1)
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showActionsMenu = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "Меню"
                )
            }
        },
        floatingActionButtonPosition = FabPosition.End,
        containerColor = Color(0xFF071422)
    ) { innerPadding ->
        ScoreboardContentView(
            redScore = redScore,
            whiteScore = whiteScore,
            goals = goals,
            gameFinished = gameFinished,
            onTeamClick = { team -> startNewGoal(team) },
            onGoalClick = { goal -> goalOptionsFor = goal },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }

    // --- ДИАЛОГ: МЕНЮ ДЕЙСТВИЙ ---

    if (showActionsMenu) {
        AlertDialog(
            onDismissRequest = { showActionsMenu = false },
            title = { Text("Меню", fontSize = 20.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    TextButton(
                        onClick = {
                            showActionsMenu = false
                            if (!gameFinished) {
                                lastLineupsRedSnapshot = playersRed
                                lastLineupsWhiteSnapshot = playersWhite
                                showLineupsDialog = true
                            }
                        },
                        enabled = !gameFinished,
                        colors = dialogButtonColors()
                    ) {
                        Text("Составы команд", fontSize = 16.sp)
                    }

                    TextButton(
                        onClick = {
                            showActionsMenu = false
                            historyDetailsText = ""
                            historySelectedEntry = null
                            historySelectedFile = null
                            showHistoryDetailsDialog = false
                            showHistoryDialog = true
                        },
                        colors = dialogButtonColors()
                    ) {
                        Text("Завершённые игры", fontSize = 16.sp)
                    }

                    TextButton(
                        onClick = {
                            showActionsMenu = false
                            showNewGameConfirm = true
                        },
                        colors = dialogButtonColors()
                    ) {
                        Text("Новая игра", fontSize = 16.sp)
                    }

                    TextButton(
                        onClick = {
                            showActionsMenu = false
                            if (!gameFinished) {
                                showFinishConfirm = true
                            }
                        },
                        enabled = !gameFinished,
                        colors = dialogButtonColors()
                    ) {
                        Text("Завершить игру и сохранить", fontSize = 16.sp)
                    }

                    TextButton(
                        onClick = {
                            showActionsMenu = false
                            showSettingsDialog = true
                        },
                        colors = dialogButtonColors()
                    ) {
                        Text("Настройки", fontSize = 16.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showActionsMenu = false },
                    colors = dialogButtonColors()
                ) {
                    Text("Закрыть", fontSize = 16.sp)
                }
            },
            containerColor = DialogBackground,
            titleContentColor = DialogTitleColor,
            textContentColor = DialogTextColor
        )
    }

    // --- ДИАЛОГ: БАЗОВЫЙ СПИСОК ИГРОКОВ ---

    if (showBasePlayersDialog && !gameFinished) {
        AlertDialog(
            onDismissRequest = { showBasePlayersDialog = false },
            title = { Text("Базовый список игроков", fontSize = 20.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 500.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newPlayerName,
                            onValueChange = { newPlayerName = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("Фамилия Имя", fontSize = 14.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = DialogTitleColor,
                                unfocusedTextColor = DialogTitleColor,
                                cursorColor = DialogTitleColor,
                                focusedBorderColor = Color(0xFF546E7A),
                                unfocusedBorderColor = Color(0xFF455A64)
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = {
                                val name = newPlayerName.trim()
                                if (name.isNotEmpty() &&
                                    basePlayers.none { it.name.equals(name, ignoreCase = true) }
                                ) {
                                    basePlayers =
                                        (basePlayers + PlayerInfo(name = name)).sortedBy { it.name }
                                    newPlayerName = ""
                                }
                            },
                            colors = dialogButtonColors()
                        ) {
                            Text("Добавить", fontSize = 14.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    basePlayers
                        .sortedBy { it.name }
                        .forEach { player ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = player.name,
                                    modifier = Modifier.weight(1f),
                                    color = DialogTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 16.sp
                                )

                                val roleSymbol = when (player.role) {
                                    PlayerRole.DEFENDER -> "🛡"
                                    PlayerRole.FORWARD -> "🎯"
                                    PlayerRole.UNIVERSAL -> "♻"
                                }

                                TextButton(
                                    onClick = {
                                        val nextRole = when (player.role) {
                                            PlayerRole.DEFENDER -> PlayerRole.FORWARD
                                            PlayerRole.FORWARD -> PlayerRole.UNIVERSAL
                                            PlayerRole.UNIVERSAL -> PlayerRole.DEFENDER
                                        }
                                        basePlayers = basePlayers.map {
                                            if (it.name == player.name) it.copy(role = nextRole)
                                            else it
                                        }.sortedBy { it.name }
                                    },
                                    modifier = Modifier
                                        .width(40.dp)
                                        .height(32.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    colors = ButtonDefaults.textButtonColors(
                                        contentColor = DialogTitleColor
                                    )
                                ) {
                                    Text(roleSymbol, fontSize = 14.sp)
                                }

                                var ratingText by remember(player.name) {
                                    mutableStateOf(
                                        if (player.rating == 0) "" else player.rating.toString()
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .width(40.dp)
                                        .height(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    BasicTextField(
                                        value = ratingText,
                                        onValueChange = { text ->
                                            val digits = text.filter { it.isDigit() }
                                            ratingText = digits

                                            val value = digits.toIntOrNull()?.coerceIn(0, 999) ?: 0

                                            basePlayers = basePlayers
                                                .map { p ->
                                                    if (p.name == player.name) p.copy(rating = value) else p
                                                }
                                                .sortedBy { it.name }
                                        },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Number
                                        ),
                                        textStyle = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 14.sp,
                                            color = DialogTitleColor,
                                            textAlign = TextAlign.Center
                                        ),
                                        modifier = Modifier.fillMaxSize(),
                                        decorationBox = { innerTextField ->
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                innerTextField()
                                            }
                                        }
                                    )
                                }

                                Spacer(modifier = Modifier.width(4.dp))

                                IconButton(
                                    onClick = {
                                        val nameToRemove = player.name
                                        basePlayers =
                                            basePlayers.filterNot { it.name == nameToRemove }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = "Удалить",
                                        tint = Color(0xFFFF8A80)
                                    )
                                }
                            }
                        }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        saveBasePlayers(prefs, basePlayers)
                        showBasePlayersDialog = false
                    },
                    colors = dialogButtonColors()
                ) {
                    Text("Сохранить", fontSize = 16.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showBasePlayersDialog = false },
                    colors = dialogButtonColors()
                ) {
                    Text("Отмена", fontSize = 16.sp)
                }
            },
            containerColor = DialogBackground,
            titleContentColor = DialogTitleColor,
            textContentColor = DialogTextColor
        )
    }

    // --- ДИАЛОГ: НАСТРОЙКИ ---

    if (showSettingsDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Настройки", fontSize = 20.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = currentSeason,
                        onValueChange = { value ->
                            currentSeason = value.trim()
                        },
                        label = { Text("Текущий сезон") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = DialogTitleColor,
                            unfocusedTextColor = DialogTitleColor,
                            cursorColor = DialogTitleColor,
                            focusedBorderColor = Color(0xFF546E7A),
                            unfocusedBorderColor = Color(0xFF455A64)
                        )
                    )

                    TextButton(
                        onClick = {
                            showSettingsDialog = false
                            if (!gameFinished) {
                                showBasePlayersDialog = true
                            }
                        },
                        enabled = !gameFinished,
                        colors = dialogButtonColors()
                    ) {
                        Text("Базовый список игроков", fontSize = 16.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        setCurrentSeason(context, currentSeason)
                        showSettingsDialog = false
                    },
                    colors = dialogButtonColors()
                ) {
                    Text("Закрыть", fontSize = 16.sp)
                }
            },
            containerColor = DialogBackground,
            titleContentColor = DialogTitleColor,
            textContentColor = DialogTextColor
        )
    }

    // --- ДИАЛОГ: СОСТАВЫ КОМАНД ---

    if (showLineupsDialog && !gameFinished) {
        val sortedRed = playersRed.sorted()
        val sortedWhite = playersWhite.sorted()

        AlertDialog(
            onDismissRequest = { showLineupsDialog = false },
            title = { Text("Составы команд", fontSize = 20.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Красные:",
                        fontWeight = FontWeight.SemiBold,
                        color = DialogTitleColor,
                        fontSize = 16.sp
                    )
                    if (sortedRed.isEmpty()) {
                        Text(
                            text = "Состав пуст",
                            fontSize = 14.sp,
                            color = DialogTextColor
                        )
                    } else {
                        sortedRed.forEachIndexed { index, playerName ->
                            if (index > 0) {
                                Divider(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    color = Color(0xFF37474F)
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${index + 1}. $playerName",
                                    modifier = Modifier.weight(1f),
                                    color = DialogTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 16.sp
                                )
                                TextButton(
                                    onClick = {
                                        val newRed = playersRed.filterNot { it == playerName }
                                        playersRedText = newRed.joinToString("\n")
                                    },
                                    colors = dialogButtonColors()
                                ) {
                                    Text("Убрать", fontSize = 14.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Белые:",
                        fontWeight = FontWeight.SemiBold,
                        color = DialogTitleColor,
                        fontSize = 16.sp
                    )
                    if (sortedWhite.isEmpty()) {
                        Text(
                            text = "Состав пуст",
                            fontSize = 14.sp,
                            color = DialogTextColor
                        )
                    } else {
                        sortedWhite.forEachIndexed { index, playerName ->
                            if (index > 0) {
                                Divider(
                                    modifier = Modifier.padding(vertical = 2.dp),
                                    color = Color(0xFF37474F)
                                )
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "${index + 1}. $playerName",
                                    modifier = Modifier.weight(1f),
                                    color = DialogTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 16.sp
                                )
                                TextButton(
                                    onClick = {
                                        val newWhite = playersWhite.filterNot { it == playerName }
                                        playersWhiteText = newWhite.joinToString("\n")
                                    },
                                    colors = dialogButtonColors()
                                ) {
                                    Text("Убрать", fontSize = 14.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    if (availablePlayers.isNotEmpty()) {
                        Text(
                            text = "Базовый список игроков:",
                            fontWeight = FontWeight.SemiBold,
                            color = DialogTitleColor,
                            fontSize = 16.sp
                        )
                        Text(
                            text = "Нажмите, чтобы добавить игрока в команду",
                            fontSize = 14.sp,
                            color = DialogTextColor
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        availablePlayers.forEach { playerName ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = playerName,
                                    modifier = Modifier.weight(1f),
                                    color = DialogTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 16.sp
                                )
                                Row(
                                    modifier = Modifier.padding(start = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = {
                                            if (!playersRed.contains(playerName)) {
                                                val newList =
                                                    (playersRed + playerName).joinToString("\n")
                                                playersRedText = newList
                                            }
                                        },
                                        modifier = Modifier
                                            .width(52.dp)
                                            .height(28.dp),
                                        colors = ButtonDefaults.textButtonColors(
                                            containerColor = Color(0xFFB71C1C),
                                            contentColor = Color.White
                                        ),
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) {
                                        Text("КР", fontSize = 12.sp)
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    TextButton(
                                        onClick = {
                                            if (!playersWhite.contains(playerName)) {
                                                val newList =
                                                    (playersWhite + playerName).joinToString("\n")
                                                playersWhiteText = newList
                                            }
                                        },
                                        modifier = Modifier
                                            .width(52.dp)
                                            .height(28.dp),
                                        colors = ButtonDefaults.textButtonColors(
                                            containerColor = Color(0xFFFAFAFA),
                                            contentColor = Color(0xFF263238)
                                        ),
                                        contentPadding = PaddingValues(horizontal = 4.dp)
                                    ) {
                                        Text("БЕЛ", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "Свободных игроков в базовом списке нет.",
                            fontSize = 14.sp,
                            color = DialogTextColor
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLineupsDialog = false
                        if (!gameFinished) {
                            logRosterChangesFromDialog()
                            notifyGameJsonUpdated(isFinal = false)
                        }
                    },
                    colors = dialogButtonColors()
                ) {
                    Text("OK", fontSize = 16.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showLineupsDialog = false },
                    colors = dialogButtonColors()
                ) {
                    Text("Отмена", fontSize = 16.sp)
                }
            },
            containerColor = DialogBackground,
            titleContentColor = DialogTitleColor,
            textContentColor = DialogTextColor
        )
    }

    // --- ДИАЛОГ: ВВОД / РЕДАКТИРОВАНИЕ ГОЛА ---

    if (goalInputTeam != null && !gameFinished) {
        val teamName = if (goalInputTeam == Team.RED) "Красные" else "Белые"
        val players = if (goalInputTeam == Team.RED) playersRed else playersWhite

        AlertDialog(
            onDismissRequest = { resetGoalInput() },
            title = {
                Text(
                    if (editingGoalId == null)
                        "Новый гол ($teamName)"
                    else
                        "Изменить гол ($teamName)",
                    fontSize = 20.sp
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.Start
                ) {
                    Text(
                        text = "Забил: ${tempScorer ?: "не выбран"}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DialogTitleColor
                    )
                    Text(
                        text = "Передача 1: ${tempAssist1 ?: "не выбрана"}",
                        fontSize = 18.sp,
                        color = DialogTextColor
                    )
                    Text(
                        text = "Передача 2: ${tempAssist2 ?: "не выбрана"}",
                        fontSize = 18.sp,
                        color = DialogTextColor
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Последовательно выберите до трёх игроков:",
                        fontSize = 16.sp,
                        color = DialogTextColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    players.forEach { player ->
                        TextButton(
                            onClick = { handlePlayerClick(player) },
                            colors = dialogButtonColors()
                        ) {
                            Text(player, fontSize = 16.sp)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { commitGoalIfPossible() },
                    enabled = tempScorer != null,
                    colors = dialogButtonColors()
                ) {
                    Text("OK", fontSize = 16.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { resetGoalInput() },
                    colors = dialogButtonColors()
                ) {
                    Text("Отмена", fontSize = 16.sp)
                }
            },
            containerColor = DialogBackground,
            titleContentColor = DialogTitleColor,
            textContentColor = DialogTextColor
        )
    }

    // --- ДИАЛОГ: ОПЦИИ КОНКРЕТНОГО ГОЛА ---

    if (goalOptionsFor != null && !gameFinished) {
        val goal = goalOptionsFor!!
        val teamName = if (goal.team == Team.RED) "Красные" else "Белые"

        AlertDialog(
            onDismissRequest = { goalOptionsFor = null },
            title = { Text("Гол $teamName", fontSize = 20.sp) },
            text = {
                Text(
                    text = formatGoalText(goal),
                    color = DialogTextColor,
                    fontSize = 16.sp
                )
            },
            confirmButton = {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(
                        onClick = {
                            goals = goals.filterNot { it.id == goal.id }
                            goalOptionsFor = null
                            notifyGameJsonUpdated(isFinal = false)
                        },
                        colors = dialogDangerButtonColors()
                    ) {
                        Text("Удалить", fontSize = 16.sp)
                    }

                    TextButton(
                        onClick = {
                            goalOptionsFor = null
                            startEditGoal(goal)
                        },
                        colors = dialogButtonColors()
                    ) {
                        Text("Изменить", fontSize = 16.sp)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { goalOptionsFor = null },
                    colors = dialogButtonColors()
                ) {
                    Text("Отмена", fontSize = 16.sp)
                }
            },
            containerColor = DialogBackground,
            titleContentColor = DialogTitleColor,
            textContentColor = DialogTextColor
        )
    }

    // --- ДИАЛОГ: "НЕТ СОСТАВОВ" ---

    if (showNoTeamsDialog) {
        AlertDialog(
            onDismissRequest = { showNoTeamsDialog = false },
            title = { Text("Составы не заданы", fontSize = 20.sp) },
            text = {
                Text(
                    text = "Сначала введите составы команд в меню «Составы команд».",
                    color = DialogTextColor,
                    fontSize = 16.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { showNoTeamsDialog = false },
                    colors = dialogButtonColors()
                ) {
                    Text("OK", fontSize = 16.sp)
                }
            },
            containerColor = DialogBackground,
            titleContentColor = DialogTitleColor,
            textContentColor = DialogTextColor
        )
    }

    // --- ДИАЛОГ: ЗАВЕРШЕНИЕ ИГРЫ ---

    if (showFinishConfirm && !gameFinished) {
        AlertDialog(
            onDismissRequest = { showFinishConfirm = false },
            title = { Text("Завершить игру?", fontSize = 20.sp) },
            text = {
                Text(
                    "После завершения игры редактирование станет невозможным, " +
                            "а результат будет сохранён в файл JSON.",
                    color = DialogTextColor,
                    fontSize = 16.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        notifyGameJsonUpdated(isFinal = true)
                        gameFinished = true
                        showFinishConfirm = false
                        showLineupsDialog = false
                        resetGoalInput()
                        goalOptionsFor = null
                    },
                    colors = dialogButtonColors()
                ) {
                    Text("Да, завершить", fontSize = 16.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showFinishConfirm = false },
                    colors = dialogButtonColors()
                ) {
                    Text("Отмена", fontSize = 16.sp)
                }
            },
            containerColor = DialogBackground,
            titleContentColor = DialogTitleColor,
            textContentColor = DialogTextColor
        )
    }

    // --- ДИАЛОГ: НОВАЯ ИГРА ---

    if (showNewGameConfirm) {
        AlertDialog(
            onDismissRequest = { showNewGameConfirm = false },
            title = { Text("Начать новую игру?", fontSize = 20.sp) },
            text = {
                Text(
                    text = "Счёт, список голов и составы команд будут сброшены.",
                    color = DialogTextColor,
                    fontSize = 16.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 1. Сбросить локальное состояние (составы, голы, счёт, флаги)
                        resetGameState()

                        // 2. Сразу же сформировать НОВЫЙ JSON и отправить как активную игру
                        // (buildGameJson установит новый gameStartMillis и сделает файл для текущего сезона)
                        notifyGameJsonUpdated(isFinal = false)

                        // 3. Закрыть диалог и дать знать наружу (если MainActivity что-то делает дополнительно)
                        showNewGameConfirm = false
                        onNewGameStarted()
                    },
                    colors = dialogButtonColors()
                ) {
                    Text("Да, новая игра", fontSize = 16.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showNewGameConfirm = false },
                    colors = dialogButtonColors()
                ) {
                    Text("Отмена", fontSize = 16.sp)
                }
            },
            containerColor = DialogBackground,
            titleContentColor = DialogTitleColor,
            textContentColor = DialogTextColor
        )
    }

    // --- ДИАЛОГ: СПИСОК ЗАВЕРШЁННЫХ ИГР ---

    if (showHistoryDialog) {
        val savedGames = remember { gameDao.getAllGames() }

        AlertDialog(
            onDismissRequest = {
                showHistoryDialog = false
                showHistoryDetailsDialog = false
                historyDetailsText = ""
                historySelectedEntry = null
                historySelectedFile = null
            },
            title = { Text("Завершённые игры", fontSize = 20.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 500.dp)
                ) {
                    if (savedGames.isEmpty()) {
                        Text(
                            text = "Сохранённых игр пока нет.",
                            fontSize = 16.sp,
                            color = DialogTextColor
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            savedGames.forEach { entry ->
                                TextButton(
                                    onClick = {
                                        val path = entry.localPath
                                        if (path.isNullOrBlank()) {
                                            Toast.makeText(
                                                context,
                                                "Файл для этой игры не найден",
                                                Toast.LENGTH_LONG
                                            ).show()
                                        } else {
                                            val file = File(path)
                                            if (!file.exists()) {
                                                Toast.makeText(
                                                    context,
                                                    "Файл ${file.name} отсутствует",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            } else {
                                                historySelectedEntry = entry
                                                historySelectedFile = file
                                                historyDetailsText = loadHistoryDetails(file)
                                                showHistoryDialog = false
                                                showHistoryDetailsDialog = true
                                            }
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = dialogButtonColors()
                                ) {
                                    Text(
                                        text = "${entry.fileName}   (${entry.redScore}:${entry.whiteScore})",
                                        textAlign = TextAlign.Start,
                                        modifier = Modifier.fillMaxWidth(),
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showHistoryDialog = false
                        showHistoryDetailsDialog = false
                        historyDetailsText = ""
                        historySelectedEntry = null
                        historySelectedFile = null
                    },
                    colors = dialogButtonColors()
                ) {
                    Text("Закрыть", fontSize = 16.sp)
                }
            },
            containerColor = DialogBackground,
            titleContentColor = DialogTitleColor,
            textContentColor = DialogTextColor
        )
    }

    // --- ДИАЛОГ: ПРОТОКОЛ МАТЧА (+ УДАЛЕНИЕ / ЭКСПОРТ) ---

    if (showHistoryDetailsDialog) {
        AlertDialog(
            onDismissRequest = {
                showHistoryDetailsDialog = false
                historyDetailsText = ""
                historySelectedEntry = null
                historySelectedFile = null
            },
            title = { Text("Протокол матча", fontSize = 20.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 500.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = historyDetailsText,
                        fontSize = 14.sp,
                        color = DialogTextColor
                    )
                }
            },
            confirmButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(
                        onClick = {
                            showDeleteGameConfirm = true
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Удалить",
                            tint = Color(0xFFFF8A80)
                        )
                    }

                    IconButton(
                        onClick = {
                            historySelectedFile?.let { file ->
                                exportGameFile(context, file)
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Share,
                            contentDescription = "Экспортировать",
                            tint = DialogTitleColor
                        )
                    }

                    IconButton(
                        onClick = {
                            showHistoryDetailsDialog = false
                            historyDetailsText = ""
                            historySelectedEntry = null
                            historySelectedFile = null
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Закрыть",
                            tint = DialogTitleColor
                        )
                    }
                }
            },
            containerColor = DialogBackground,
            titleContentColor = DialogTitleColor,
            textContentColor = DialogTextColor
        )
    }

    // --- ДИАЛОГ: ПОДТВЕРЖДЕНИЕ УДАЛЕНИЯ ИГРЫ ---

    if (showDeleteGameConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteGameConfirm = false },
            title = { Text("Удалить игру?", fontSize = 20.sp) },
            text = {
                Text(
                    text = "Вы действительно хотите удалить этот сохранённый матч? Отменить это действие будет невозможно.",
                    color = DialogTextColor,
                    fontSize = 16.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val entry = historySelectedEntry
                        val file = historySelectedFile

                        if (entry != null) {
                            onGameDeleted(entry.gameId, file)
                        }

                        historySelectedEntry = null
                        historySelectedFile = null
                        historyDetailsText = ""
                        showDeleteGameConfirm = false
                        showHistoryDetailsDialog = false
                        showHistoryDialog = true
                    },
                    colors = dialogDangerButtonColors()
                ) {
                    Text("Да, удалить", fontSize = 16.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteGameConfirm = false
                    },
                    colors = dialogButtonColors()
                ) {
                    Text("Отмена", fontSize = 16.sp)
                }
            },
            containerColor = DialogBackground,
            titleContentColor = DialogTitleColor,
            textContentColor = DialogTextColor
        )
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewScoreboard() {
    HockeyScoreboardTheme {
        ScoreboardScreen()
    }
}
