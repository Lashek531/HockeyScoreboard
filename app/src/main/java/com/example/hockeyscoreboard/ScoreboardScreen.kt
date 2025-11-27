package com.example.hockeyscoreboard

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import com.example.hockeyscoreboard.model.*
import com.example.hockeyscoreboard.ui.theme.HockeyScoreboardTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.hockeyscoreboard.data.db.GameDatabase
import com.example.hockeyscoreboard.data.db.GameEntry


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
    gameRepository: GameRepository,
    driveAccountEmail: String? = null,
    onConnectDrive: () -> Unit = {},
    onGameSaved: (File) -> Unit = {},
    onGameJsonUpdated: (File) -> Unit = {},
    onNewGameStarted: () -> Unit = {}          // <- НОВЫЙ параметр
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("hockey_prefs", Context.MODE_PRIVATE)
    }
    // --- Локальная БД для индекса игр ---
    val gameDb = remember { GameDatabase.getInstance(context) }
    val gameDao = remember { gameDb.gameDao() }


    // --- БАЗОВЫЙ СПИСОК ИГРОКОВ (имена + роль + рейтинг) ---

    var basePlayers by remember {
        mutableStateOf(loadBasePlayers(prefs))
    }

    // Имя для добавления нового игрока
    var newPlayerName by remember { mutableStateOf("") }

    // --- СОСТАВЫ КОМАНД ---

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

    // --- ФЛАГИ ДИАЛОГОВ / МЕНЮ ---

    var showBasePlayersDialog by remember { mutableStateOf(false) }
    var showLineupsDialog by remember { mutableStateOf(false) }
    var showTransferDialog by remember { mutableStateOf(false) }
    var showHistoryDialog by remember { mutableStateOf(false) }
    var showHistoryDetailsDialog by remember { mutableStateOf(false) }
    var showFinishConfirm by remember { mutableStateOf(false) }
    var showNewGameConfirm by remember { mutableStateOf(false) }
    var showActionsMenu by remember { mutableStateOf(false) }
    var showNoTeamsDialog by remember { mutableStateOf(false) }

    // новые окна статистики
    var showTopScorersDialog by remember { mutableStateOf(false) }
    var showTopBombersDialog by remember { mutableStateOf(false) }
    var topScorersRows by remember { mutableStateOf<List<PlayerStatsRow>>(emptyList()) }
    var topBombersRows by remember { mutableStateOf<List<PlayerStatsRow>>(emptyList()) }

    // текущая выбранная игра (файл) для просмотра протокола
    var historySelectedEntry by remember { mutableStateOf<GameEntry?>(null) }
    var historySelectedFile by remember { mutableStateOf<File?>(null) }
    var historyDetailsText by remember { mutableStateOf("") }

    // подтверждение удаления сохранённой игры
    var showDeleteGameConfirm by remember { mutableStateOf(false) }
    // --- ИГРА / ГОЛЫ ---

    var goals by rememberSaveable(stateSaver = GoalEventListSaver) {
        mutableStateOf(listOf<GoalEvent>())
    }
    // Время старта текущей игры (для стабильного gameId / имени файла)
    var gameStartMillis by rememberSaveable { mutableStateOf<Long?>(null) }

    val redScore = goals.count { it.team == Team.RED }
    val whiteScore = goals.count { it.team == Team.WHITE }

    var goalInputTeam by remember { mutableStateOf<Team?>(null) }
    var editingGoalId by remember { mutableStateOf<Long?>(null) }
    var tempScorer by remember { mutableStateOf<String?>(null) }
    var tempAssist1 by remember { mutableStateOf<String?>(null) }
    var tempAssist2 by remember { mutableStateOf<String?>(null) }

    var nextGoalId by rememberSaveable { mutableStateOf(1L) }

    var goalOptionsFor by remember { mutableStateOf<GoalEvent?>(null) }

    var gameFinished by rememberSaveable { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    // --- УТИЛИТЫ ---

    fun resetGoalInput() {
        goalInputTeam = null
        editingGoalId = null
        tempScorer = null
        tempAssist1 = null
        tempAssist2 = null
    }

    fun resetGameState() {
        goals = emptyList()
        nextGoalId = 1L
        gameFinished = false
        gameStartMillis = null       // новая игра → будет новый id
        resetGoalInput()
        goalOptionsFor = null
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

        // Старт игры: фиксируем один раз и используем всегда
        val startMillis = gameStartMillis ?: System.currentTimeMillis().also { gameStartMillis = it }
        val startDate = Date(startMillis)

        val fileName = fileFormat.format(startDate) + "_pestovo.json"
        val dateIso = isoFormat.format(startDate)

        val root = JSONObject()

        root.put("gameId", fileFormat.format(startDate) + "_pestovo")
        root.put("arena", "Пестово Арена")
        root.put("date", dateIso)
        root.put("finished", isFinal)   // флаг завершения игры

        // --- Команды и составы ---
        val teamsObj = JSONObject()
        val redObj = JSONObject()
        val whiteObj = JSONObject()

        redObj.put("name", "Красные")
        whiteObj.put("name", "Белые")

        val redPlayersArray = JSONArray()
        playersRed.forEach { redPlayersArray.put(it) }
        redObj.put("players", redPlayersArray)

        val whitePlayersArray = JSONArray()
        playersWhite.forEach { whitePlayersArray.put(it) }
        whiteObj.put("players", whitePlayersArray)

        teamsObj.put("RED", redObj)
        teamsObj.put("WHITE", whiteObj)

        root.put("teams", teamsObj)

        // --- Итоговый счёт на момент сохранения ---
        val currentRedScore = goals.count { it.team == Team.RED }
        val currentWhiteScore = goals.count { it.team == Team.WHITE }

        val scoreObj = JSONObject()
        scoreObj.put("RED", currentRedScore)
        scoreObj.put("WHITE", currentWhiteScore)
        root.put("finalScore", scoreObj)

        // --- Список голов по ходу матча ---
        val goalsArray = JSONArray()
        var runningRed = 0
        var runningWhite = 0

        goals.forEachIndexed { index, goal ->
            if (goal.team == Team.RED) runningRed++ else runningWhite++

            val goalObj = JSONObject()
            goalObj.put("index", index + 1)
            goalObj.put("team", goal.team.name)
            goalObj.put("scoreAfter", "${runningRed}:${runningWhite}")
            goalObj.put("scorer", goal.scorer)
            goalObj.put("assist1", goal.assist1)
            goalObj.put("assist2", goal.assist2)

            goalsArray.put(goalObj)
        }

        // ВАЖНО: раньше этой строки не было — из-за этого в JSON не было голов
        root.put("goals", goalsArray)

        return fileName to root.toString(2)
    }



    /**
     * Реально сохраняем JSON в файл в каталоге games (через GameRepository) и возвращаем File.
     * Тот же файл используем и для Drive, и для локальной истории.
     */
    fun saveGameJsonToFile(isFinal: Boolean = false): File {
        val (fileName, json) = buildGameJson(isFinal)
        return gameRepository.saveGameJson(fileName, json)
    }

    /**
     * Автообновление JSON при любом изменении голов.
     */
    fun notifyGameJsonUpdated(isFinal: Boolean = false) {
        // 1. сохраняем JSON на диск
        val file = saveGameJsonToFile(isFinal)

        // 2. обновляем индекс игры в локальной БД
        val now = System.currentTimeMillis()
        val finishedAt = if (isFinal) now else null
        val startedAt = gameStartMillis ?: now
        val gameId = file.name.removeSuffix(".json")

        val entry = GameEntry(
            gameId = gameId,
            fileName = file.name,
            localPath = file.absolutePath,
            startedAt = startedAt,
            finishedAt = finishedAt,
            redScore = redScore,
            whiteScore = whiteScore
        )
        gameDao.upsertGame(entry)

        // 3. уведомляем наружу – что делать с этим файлом
        if (isFinal) {
            // финальный файл → MainActivity отправит его в архив на Drive
            onGameSaved(file)
        } else {
            // обычное обновление по ходу игры
            onGameJsonUpdated(file)
        }
    }


    fun commitGoalIfPossible() {
        if (gameFinished) return
        val team = goalInputTeam ?: return
        val scorer = tempScorer ?: return

        val newEvent = GoalEvent(
            id = editingGoalId ?: nextGoalId++,
            team = team,
            scorer = scorer,
            assist1 = tempAssist1,
            assist2 = tempAssist2
        )

        goals = if (editingGoalId == null) {
            goals + newEvent
        } else {
            goals.map { if (it.id == editingGoalId) newEvent else it }
        }

        // после любого изменения списка голов шлём обновлённый JSON
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

    // Экспорт JSON-файла сохранённого матча через системный Share Sheet
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

    // --- ОСНОВНОЙ ЭКРАН: Scaffold + AppBar + FAB ---

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = "Пестово Арена",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    // Индикация статуса Google Drive
                    Icon(
                        imageVector = if (driveAccountEmail != null)
                            Icons.Filled.Share
                        else
                            Icons.Filled.Close,
                        contentDescription = if (driveAccountEmail != null)
                            "Google Drive подключён"
                        else
                            "Google Drive не подключён",
                        tint = if (driveAccountEmail != null)
                            Color(0xFF81C784)   // зелёный, подключено
                        else
                            Color(0xFFB0BEC5),  // серый, не подключено
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(24.dp)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
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

                TeamScoreCard(
                    teamName = "Красные",
                    score = redScore,
                    backgroundColor = Color(0xFFB71C1C),
                    textColor = Color(0xFFFFF8E1),
                    modifier = Modifier.weight(1f),
                    onClick = { startNewGoal(Team.RED) }
                )

                Spacer(modifier = Modifier.width(16.dp))

                TeamScoreCard(
                    teamName = "Белые",
                    score = whiteScore,
                    backgroundColor = Color(0xFFCFD8DC),
                    textColor = Color(0xFF263238),
                    modifier = Modifier.weight(1f),
                    onClick = { startNewGoal(Team.WHITE) }
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
                            ) { goalOptionsFor = goal }
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
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = teamColor
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = formatGoal(goal),
                            fontSize = 14.sp,
                            color = Color(0xFF81D4FA)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(64.dp))
        }
    }

    // --- Дальше все диалоги ---

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
                    // Статус Google Drive
                    Text(
                        text = if (driveAccountEmail != null)
                            "Google Drive: подключено (${driveAccountEmail})"
                        else
                            "Google Drive: не подключено",
                        fontSize = 14.sp,
                        color = DialogTextColor,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    TextButton(
                        onClick = {
                            showActionsMenu = false
                            if (!gameFinished) showBasePlayersDialog = true
                        },
                        enabled = !gameFinished,
                        colors = dialogButtonColors()
                    ) {
                        Text("Базовый список игроков", fontSize = 16.sp)
                    }

                    TextButton(
                        onClick = {
                            showActionsMenu = false
                            if (!gameFinished) showLineupsDialog = true
                        },
                        enabled = !gameFinished,
                        colors = dialogButtonColors()
                    ) {
                        Text("Составы команд", fontSize = 16.sp)
                    }

                    TextButton(
                        onClick = {
                            showActionsMenu = false
                            if (!gameFinished) showTransferDialog = true
                        },
                        enabled = !gameFinished,
                        colors = dialogButtonColors()
                    ) {
                        Text("Перенос игроков", fontSize = 16.sp)
                    }

                    TextButton(
                        onClick = {
                            showActionsMenu = false
                            historyDetailsText = ""
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
                            val stats = gameRepository.collectPlayerStats()
                            topScorersRows = gameRepository.buildTopScorersRows(stats)
                            showTopScorersDialog = true
                        },
                        colors = dialogButtonColors()
                    ) {
                        Text("Лучшие снайперы", fontSize = 16.sp)
                    }

                    TextButton(
                        onClick = {
                            showActionsMenu = false
                            val stats = gameRepository.collectPlayerStats()
                            topBombersRows = gameRepository.buildTopBombersRows(stats)
                            showTopBombersDialog = true
                        },
                        colors = dialogButtonColors()
                    ) {
                        Text("Лучшие бомбардиры", fontSize = 16.sp)
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
                            onConnectDrive()
                        },
                        colors = dialogButtonColors()
                    ) {
                        Text("Подключить Google Drive", fontSize = 16.sp)
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
                    // Добавление нового игрока
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

                    // Список игроков
                    basePlayers
                        .sortedBy { it.name }
                        .forEach { player ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Имя
                                Text(
                                    text = player.name,
                                    modifier = Modifier.weight(1f),
                                    color = DialogTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 16.sp
                                )

                                // Иконка амплуа (эмодзи)
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

                                // Рейтинг – маленькое поле без рамки
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
                    // Красные
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

                    // Белые
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

                    // Базовый список: только свободные игроки
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

                        // если игра уже начата или мы хотим фиксировать текущую конфигурацию,
                        // обновляем JSON/Room/Drive с новым составом
                        if (!gameFinished) {
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

    // --- ДИАЛОГ: БЫСТРЫЙ ПЕРЕНОС ИГРОКОВ ---

    if (showTransferDialog && !gameFinished) {
        val sortedRed = playersRed.sorted()
        val sortedWhite = playersWhite.sorted()

        AlertDialog(
            onDismissRequest = { showTransferDialog = false },
            title = { Text("Перенос игроков между командами", fontSize = 20.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (sortedRed.isEmpty() && sortedWhite.isEmpty()) {
                        Text(
                            text = "Обе команды пусты.",
                            fontSize = 16.sp,
                            color = DialogTextColor
                        )
                    } else {
                        if (sortedRed.isNotEmpty()) {
                            Text(
                                text = "Из Красных в Белые:",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = DialogTitleColor
                            )
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
                                        text = playerName,
                                        modifier = Modifier.weight(1f),
                                        color = DialogTextColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 16.sp
                                    )
                                    TextButton(
                                        onClick = {
                                            val newRed = playersRed.filterNot { it == playerName }
                                            val newWhite = (playersWhite + playerName)
                                            playersRedText = newRed.joinToString("\n")
                                            playersWhiteText = newWhite.joinToString("\n")
                                        },
                                        colors = dialogButtonColors()
                                    ) {
                                        Text("→ БЕЛ", fontSize = 14.sp)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (sortedWhite.isNotEmpty()) {
                            Text(
                                text = "Из Белых в Красные:",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = DialogTitleColor
                            )
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
                                        text = playerName,
                                        modifier = Modifier.weight(1f),
                                        color = DialogTextColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = 16.sp
                                    )
                                    TextButton(
                                        onClick = {
                                            val newWhite = playersWhite.filterNot { it == playerName }
                                            val newRed = (playersRed + playerName)
                                            playersWhiteText = newWhite.joinToString("\n")
                                            playersRedText = newRed.joinToString("\n")
                                        },
                                        colors = dialogButtonColors()
                                    ) {
                                        Text("→ КР", fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTransferDialog = false

                        if (!gameFinished) {
                            notifyGameJsonUpdated(isFinal = false)
                        }
                    },
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

    // --- ДИАЛОГ: ОПЦИИ ДЛЯ КОНКРЕТНОГО ГОЛА ---

    if (goalOptionsFor != null && !gameFinished) {
        val goal = goalOptionsFor!!
        val teamName = if (goal.team == Team.RED) "Красные" else "Белые"

        AlertDialog(
            onDismissRequest = { goalOptionsFor = null },
            title = { Text("Гол $teamName", fontSize = 20.sp) },
            text = {
                Text(
                    text = formatGoal(goal),
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

    // --- ДИАЛОГ: ПРЕДУПРЕЖДЕНИЕ "НЕТ СОСТАВОВ" ---

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

    // --- ДИАЛОГ: ПОДТВЕРЖДЕНИЕ ЗАВЕРШЕНИЯ ИГРЫ ---

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
                        // единая точка: JSON + Room + Drive (финально)
                        notifyGameJsonUpdated(isFinal = true)

                        gameFinished = true
                        showFinishConfirm = false
                        showLineupsDialog = false
                        showTransferDialog = false
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
                    text = "Счёт и список голов будут обнулены. Составы команд и сохранённые завершённые игры останутся.",
                    color = DialogTextColor,
                    fontSize = 16.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // 1) локально обнуляем состояние
                        resetGameState()
                        showNewGameConfirm = false

                        // 2) сообщаем наружу: новая игра началась
                        // (MainActivity очистит онлайн-папку и сбросит currentFileId)
                        onNewGameStarted()

                        // 3) сразу создаём JSON с текущим счётом 0:0 и шлём наружу
                        //    → MainActivity загрузит этот файл в активную папку на Drive
                        notifyGameJsonUpdated(isFinal = false)
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
        // Берём список игр из локальной БД (индекс)
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


    // --- ДИАЛОГ: ПРОСМОТР ПРОТОКОЛА (+ УДАЛЕНИЕ / ЭКСПОРТ ИГРЫ) ---

    if (showHistoryDetailsDialog) {
        AlertDialog(
            onDismissRequest = {
                showHistoryDetailsDialog = false
                historyDetailsText = ""
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
            // три маленькие иконки вместо длинных надписей
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
                        // сначала удаляем запись в БД (если есть)
                        historySelectedEntry?.let { entry ->
                            gameDao.deleteGameById(entry.gameId)
                        }

                        // затем удаляем сам файл
                        historySelectedFile?.let { file ->
                            if (file.exists()) {
                                file.delete()
                            }
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

    // --- ДИАЛОГ: ЛУЧШИЕ СНАЙПЕРЫ (ТАБЛИЦА) ---

    if (showTopScorersDialog) {
        AlertDialog(
            onDismissRequest = {
                showTopScorersDialog = false
                topScorersRows = emptyList()
            },
            title = { Text("Лучшие снайперы", fontSize = 20.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 500.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (topScorersRows.isEmpty()) {
                        Text(
                            text = "Нет данных: в сохранённых матчах пока нет заброшенных шайб.",
                            fontSize = 14.sp,
                            color = DialogTextColor
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "#",
                                modifier = Modifier.width(28.dp),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DialogTitleColor
                            )
                            Text(
                                "Игрок",
                                modifier = Modifier.weight(1f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DialogTitleColor
                            )
                            Text(
                                "И",
                                modifier = Modifier.width(24.dp),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DialogTitleColor,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "Г",
                                modifier = Modifier.width(24.dp),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DialogTitleColor,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "П",
                                modifier = Modifier.width(24.dp),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DialogTitleColor,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "О",
                                modifier = Modifier.width(24.dp),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DialogTitleColor,
                                textAlign = TextAlign.Center
                            )
                        }

                        Divider(color = Color(0xFF37474F))

                        topScorersRows.forEach { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = row.rank.toString(),
                                    modifier = Modifier.width(28.dp),
                                    fontSize = 14.sp,
                                    color = DialogTextColor
                                )
                                Text(
                                    text = row.name,
                                    modifier = Modifier.weight(1f),
                                    fontSize = 14.sp,
                                    color = DialogTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = row.games.toString(),
                                    modifier = Modifier.width(24.dp),
                                    fontSize = 14.sp,
                                    color = DialogTextColor,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = row.goals.toString(),
                                    modifier = Modifier.width(24.dp),
                                    fontSize = 14.sp,
                                    color = DialogTextColor,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = row.assists.toString(),
                                    modifier = Modifier.width(24.dp),
                                    fontSize = 14.sp,
                                    color = DialogTextColor,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = row.points.toString(),
                                    modifier = Modifier.width(24.dp),
                                    fontSize = 14.sp,
                                    color = DialogTextColor,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTopScorersDialog = false
                        topScorersRows = emptyList()
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

    // --- ДИАЛОГ: ЛУЧШИЕ БОМБАРДИРЫ (ТАБЛИЦА) ---

    if (showTopBombersDialog) {
        AlertDialog(
            onDismissRequest = {
                showTopBombersDialog = false
                topBombersRows = emptyList()
            },
            title = { Text("Лучшие бомбардиры", fontSize = 20.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 500.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (topBombersRows.isEmpty()) {
                        Text(
                            text = "Нет данных: в сохранённых матчах пока нет набранных очков (голы + пасы).",
                            fontSize = 14.sp,
                            color = DialogTextColor
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "#",
                                modifier = Modifier.width(28.dp),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DialogTitleColor
                            )
                            Text(
                                "Игрок",
                                modifier = Modifier.weight(1f),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DialogTitleColor
                            )
                            Text(
                                "И",
                                modifier = Modifier.width(24.dp),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DialogTitleColor,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "Г",
                                modifier = Modifier.width(24.dp),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DialogTitleColor,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "П",
                                modifier = Modifier.width(24.dp),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DialogTitleColor,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "О",
                                modifier = Modifier.width(24.dp),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = DialogTitleColor,
                                textAlign = TextAlign.Center
                            )
                        }

                        Divider(color = Color(0xFF37474F))

                        topBombersRows.forEach { row ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = row.rank.toString(),
                                    modifier = Modifier.width(28.dp),
                                    fontSize = 14.sp,
                                    color = DialogTextColor
                                )
                                Text(
                                    text = row.name,
                                    modifier = Modifier.weight(1f),
                                    fontSize = 14.sp,
                                    color = DialogTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = row.games.toString(),
                                    modifier = Modifier.width(24.dp),
                                    fontSize = 14.sp,
                                    color = DialogTextColor,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = row.goals.toString(),
                                    modifier = Modifier.width(24.dp),
                                    fontSize = 14.sp,
                                    color = DialogTextColor,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = row.assists.toString(),
                                    modifier = Modifier.width(24.dp),
                                    fontSize = 14.sp,
                                    color = DialogTextColor,
                                    textAlign = TextAlign.Center
                                )
                                Text(
                                    text = row.points.toString(),
                                    modifier = Modifier.width(24.dp),
                                    fontSize = 14.sp,
                                    color = DialogTextColor,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showTopBombersDialog = false
                        topBombersRows = emptyList()
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
}

@Composable
private fun TeamScoreCard(
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

private fun formatGoal(goal: GoalEvent): String {
    val assists = listOfNotNull(goal.assist1, goal.assist2)
    return if (assists.isEmpty()) {
        goal.scorer
    } else {
        "${goal.scorer} (${assists.joinToString(", ")})"
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewScoreboard() {
    val context = LocalContext.current
    val gameRepo = remember { GameRepository(context) }

    HockeyScoreboardTheme {
        ScoreboardScreen(
            gameRepository = gameRepo
        )
    }
}
