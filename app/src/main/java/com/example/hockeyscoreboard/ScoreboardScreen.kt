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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import androidx.compose.runtime.mutableStateMapOf
import org.json.JSONObject
import org.json.JSONArray






// Описание одной записи из roster.json
data class ImportedRosterItem(
    val fullName: String,
    val team: String,   // "red" / "white"
    val role: String?,
    val line: Int?,
    val userId: String?,   // внешний UserID в виде строки
    val eventId: String?   // внешний EventID (игровое событие)
)

// Нормализация имени для поиска по базовому списку
private fun normalizeName(name: String): String =
    name.trim()
        .replace("\\s+".toRegex(), " ")
        .lowercase(Locale.getDefault())



// --- Снапшот активной незавершённой игры из active_game.json ---
data class ActiveGameSnapshot(
    val season: String,
    val playersRed: List<String>,
    val playersWhite: List<String>,
    val goals: List<GoalEvent>,
    val rosterChanges: List<RosterChangeEvent>,
    val gameStartMillis: Long,      // всегда НЕ null
    val finished: Boolean,
    val externalEventId: String?
)


// Загрузка снапшота активной незавершённой игры из active_game.json
fun loadActiveGameSnapshotOrNull(context: Context): ActiveGameSnapshot? {
    val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
    val dbRoot = File(baseDir, "hockey-json")
    val activeFile = File(dbRoot, "active_game.json")

    if (!activeFile.exists() || !activeFile.isFile) return null

    return try {
        val text = activeFile.readText(Charsets.UTF_8)
        val root = org.json.JSONObject(text)

        val finished = root.optBoolean("finished", false)
        // Восстанавливаем только незавершённую игру
        if (finished) return null

        val season = root.optString("season", getCurrentSeason(context))

        // date → gameStartMillis (если не удалось – берём время файла)
        val dateStr = root.optString("date", "")
        val gameStartMillis: Long = if (dateStr.isNotBlank()) {
            try {
                val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                fmt.parse(dateStr)?.time ?: activeFile.lastModified()
            } catch (_: Exception) {
                activeFile.lastModified()
            }
        } else {
            activeFile.lastModified()
        }

        val externalEventId = if (root.has("externalEventId")) {
            root.optString("externalEventId").takeIf { it.isNotBlank() }
        } else null

        // --- Составы команд ---
        val teamsObj = root.optJSONObject("teams") ?: return null

        fun readTeamPlayers(key: String): List<String> {
            val teamObj = teamsObj.optJSONObject(key) ?: return emptyList()
            val arr = teamObj.optJSONArray("players") ?: return emptyList()
            val result = mutableListOf<String>()
            for (i in 0 until arr.length()) {
                val name = arr.optString(i).trim()
                if (name.isNotEmpty()) result += name
            }
            return result
        }

        val playersRed = readTeamPlayers("RED")
        val playersWhite = readTeamPlayers("WHITE")

// --- Голы ---
        val goalsArray = root.optJSONArray("goals") ?: org.json.JSONArray()
        val goals = mutableListOf<GoalEvent>()

        for (i in 0 until goalsArray.length()) {
            val obj = goalsArray.optJSONObject(i) ?: continue

            val teamStr = obj.optString("team", "")
                .trim()
                .uppercase(Locale.getDefault())

            val team = try {
                Team.valueOf(teamStr)
            } catch (_: Exception) {
                continue
            }

            val scorer = obj.optString("scorer", "").trim()
            if (scorer.isEmpty()) continue

            val assist1 = obj.optString("assist1", "").trim().ifEmpty { null }
            val assist2 = obj.optString("assist2", "").trim().ifEmpty { null }

            val order = obj.optLong("order", (i + 1).toLong())
            val id = (i + 1).toLong()

            goals += GoalEvent(
                id = id,
                team = team,
                scorer = scorer,
                assist1 = assist1,
                assist2 = assist2,
                eventOrder = order,
                // 0L = "время гола неизвестно" (из active_game.json старого формата)
                timestampMillis = 0L
            )

        }


        // --- Переходы в составах ---
        val rosterArray = root.optJSONArray("rosterChanges") ?: org.json.JSONArray()
        val rosterChanges = mutableListOf<RosterChangeEvent>()

        for (i in 0 until rosterArray.length()) {
            val obj = rosterArray.optJSONObject(i) ?: continue

            val id = obj.optLong("id", (i + 1).toLong())
            val player = obj.optString("player", "").trim()
            if (player.isEmpty()) continue

            val fromTeamStr = obj.optString("fromTeam", "").trim()
            val toTeamStr = obj.optString("toTeam", "").trim()

            val fromTeam = fromTeamStr.takeIf { it.isNotEmpty() }?.let { value ->
                try { Team.valueOf(value) } catch (_: Exception) { null }
            }

            val toTeam = toTeamStr.takeIf { it.isNotEmpty() }?.let { value ->
                try { Team.valueOf(value) } catch (_: Exception) { null }
            }

            val order = obj.optLong("order", (i + goals.size + 1).toLong())

            rosterChanges += RosterChangeEvent(
                id = id,
                player = player,
                fromTeam = fromTeam,
                toTeam = toTeam,
                eventOrder = order
            )
        }

        ActiveGameSnapshot(
            season = season,
            playersRed = playersRed,
            playersWhite = playersWhite,
            goals = goals,
            rosterChanges = rosterChanges,
            gameStartMillis = gameStartMillis,
            finished = finished,
            externalEventId = externalEventId
        )
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}


// --- Корень локальной базы hockey-json на устройстве ---
fun getLocalDbRoot(context: Context): File {
    val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
    return File(baseDir, "hockey-json")
}



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
    onGameDeleted: (gameId: String, file: File?, onResult: (Boolean) -> Unit) -> Unit = { _, _, onResult ->
        onResult(false)
    },

    onFinalScreenshotRequested: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settingsRepository = remember { SettingsRepositoryImpl(context) }
    val syncRepository = remember { SyncRepository(context, settingsRepository) }
    val raspiRepository = remember { RaspiRepository(settingsRepository) }


    var isSyncing by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var telegramBotToken by remember { mutableStateOf("") }
    var telegramChatId by remember { mutableStateOf("") }
    var telegramBotChatId by remember { mutableStateOf("") }

    // Снапшот значений настроек на момент открытия диалога
    var initialSeason by remember { mutableStateOf("") }
    var initialServerUrl by remember { mutableStateOf("") }
    var initialApiKey by remember { mutableStateOf("") }
    var initialTelegramBotToken by remember { mutableStateOf("") }
    var initialTelegramChatId by remember { mutableStateOf("") }
    var initialTelegramBotChatId by remember { mutableStateOf("") }

    // Диалог подтверждения изменения настроек
    var showSettingsConfirmDialog by remember { mutableStateOf(false) }









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

    fun parsePlayers(text: String): List<String> =
        text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }


    val playersRed: List<String> = remember(playersRedText) {
        parsePlayers(playersRedText)
    }
    val playersWhite: List<String> = remember(playersWhiteText) {
        parsePlayers(playersWhiteText)
    }


    // доступные для распределения игроки
    val availablePlayers: List<String> = remember(basePlayers, playersRed, playersWhite) {
        basePlayers.map { it.name }
            .filter { it !in playersRed && it !in playersWhite }
            .sorted()
    }

    // Внешний EventID, пришедший из roster.json (оставляем для выгрузки вовне)
    var externalEventId by rememberSaveable {
        mutableStateOf(getActiveEventId(prefs))
    }


    // Временное хранилище результатов импорта состава (только имена)
    var importedRedFromRoster by remember { mutableStateOf<List<String>>(emptyList()) }
    var importedWhiteFromRoster by remember { mutableStateOf<List<String>>(emptyList()) }

    // Неизвестные игроки из roster.json (их нет в базовом списке)
    var unknownRosterItems by remember { mutableStateOf<List<ImportedRosterItem>>(emptyList()) }
    var showUnknownPlayersDialog by remember { mutableStateOf(false) }


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
    // Ключ, который будем увеличивать после удаления игры,
    // чтобы диалог "Завершённые игры" перечитал список из Room
    var historyRefreshKey by remember { mutableStateOf(0L) }
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
    LaunchedEffect(Unit) {
        serverUrl = settingsRepository.getServerUrl()
        apiKey = settingsRepository.getApiKey()
        telegramBotToken = (settingsRepository as SettingsRepositoryImpl).getTelegramBotToken()
        telegramChatId = (settingsRepository as SettingsRepositoryImpl).getTelegramChatId()
        telegramBotChatId = (settingsRepository as SettingsRepositoryImpl).getTelegramBotChatId()

        // При первом запуске можно сразу считать эти значения как "исходные"
        initialSeason = currentSeason
        initialServerUrl = serverUrl
        initialApiKey = apiKey
        initialTelegramBotToken = telegramBotToken
        initialTelegramChatId = telegramChatId
        initialTelegramBotChatId = telegramBotChatId
    }



    // --- УТИЛИТЫ ---


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

    // Обновление внешнего EventID + запись в SharedPreferences,
    // чтобы он переживал перезапуск приложения
    fun updateExternalEventId(newId: String?) {
        externalEventId = newId
        setActiveEventId(prefs, newId)
    }

    // Применение снапшота активной игры, загруженного из active_game.json
    fun applyActiveSnapshot(snapshot: ActiveGameSnapshot) {
        // Сезон из снапшота
        currentSeason = snapshot.season

        // Составы в текстовые поля (они дальше сами распарсятся в playersRed/playersWhite)
        playersRedText = snapshot.playersRed.joinToString("\n")
        playersWhiteText = snapshot.playersWhite.joinToString("\n")

        // Голы и переходы в протокол
        goals = snapshot.goals
        rosterChanges = snapshot.rosterChanges

        // Время старта и флаг завершённости (на практике finished тут всегда false)
        gameStartMillis = snapshot.gameStartMillis
        gameFinished = snapshot.finished

        // Восстанавливаем внешний EventID (и в prefs тоже)
        updateExternalEventId(snapshot.externalEventId)

        // Пересчитываем следующие идентификаторы и порядок событий
        nextGoalId = (goals.maxOfOrNull { it.id } ?: 0L) + 1L
        nextRosterChangeId = (rosterChanges.maxOfOrNull { it.id } ?: 0L) + 1L

        val maxOrderFromGoals = goals.maxOfOrNull { it.eventOrder } ?: 0L
        val maxOrderFromRoster = rosterChanges.maxOfOrNull { it.eventOrder } ?: 0L
        nextEventOrder = maxOf(maxOrderFromGoals, maxOrderFromRoster) + 1L

        // Зафиксируем снапшоты составов, чтобы дальнейшие изменения через диалог
        // "Составы команд" корректно порождали события rosterChanges
        lastLineupsRedSnapshot = snapshot.playersRed
        lastLineupsWhiteSnapshot = snapshot.playersWhite
        hasBaselineLineups = true
    }

    // При первом запуске экрана пробуем восстановить незавершённую игру из active_game.json
    LaunchedEffect(Unit) {
        val snapshot = loadActiveGameSnapshotOrNull(context)
        if (snapshot != null) {
            applyActiveSnapshot(snapshot)
        }
    }




    fun rebuildGamesIndexFromFilesystem() {
        // база там же, где мы только что синкнули ZIP
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dbRoot = File(baseDir, "hockey-json")
        val finishedRoot = File(dbRoot, "finished")

        // если базы нет вообще — просто чистим индекс
        if (!finishedRoot.exists() || !finishedRoot.isDirectory) {
            gameDao.deleteAll()
            return
        }

        // сначала полностью очищаем индекс
        gameDao.deleteAll()

        // проходим по всем сезонам: finished/<season>/*.json
        finishedRoot.listFiles()?.forEach { seasonDir ->
            if (!seasonDir.isDirectory) return@forEach
            val season = seasonDir.name

            seasonDir.listFiles { f ->
                f.isFile &&
                        f.name.endsWith(".json") &&
                        f.name != "index.json"
            }?.forEach { file ->
                try {
                    val text = file.readText(Charsets.UTF_8)
                    val json = org.json.JSONObject(text)

                    val baseId = json.optString(
                        "id",
                        file.name.removeSuffix(".json")
                    )

                    val dateStr = json.optString("date", "")
                    val finalScoreObj = json.optJSONObject("finalScore")

                    val redScore = finalScoreObj?.optInt("RED", 0) ?: 0
                    val whiteScore = finalScoreObj?.optInt("WHITE", 0) ?: 0

                    // пытаемся вытащить timestamp из поля date
                    val startedAt = try {
                        if (dateStr.isNotBlank()) {
                            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
                            fmt.parse(dateStr)?.time ?: file.lastModified()
                        } else {
                            file.lastModified()
                        }
                    } catch (_: Exception) {
                        file.lastModified()
                    }

                    val entry = GameEntry(
                        gameId = baseId,
                        season = season,
                        fileName = file.name,
                        localPath = file.absolutePath,
                        startedAt = startedAt,
                        finishedAt = startedAt,
                        redScore = redScore,
                        whiteScore = whiteScore
                    )

                    gameDao.upsertGame(entry)
                } catch (e: Exception) {
                    e.printStackTrace()
                    // на один кривой файл не падаем, просто пропускаем
                }
            }
        }
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

        val startMillis = gameStartMillis ?: System.currentTimeMillis().also { gameStartMillis = it }
        val startDate = Date(startMillis)

        val baseId = fileFormat.format(startDate) + "_pestovo"
        val fileName = "$baseId.json"
        val dateIso = isoFormat.format(startDate)

        val season = currentSeason

        val root = org.json.JSONObject()

        root.put("id", baseId)
        root.put("gameId", baseId)
        root.put("arena", "Пестово Арена")
        root.put("date", dateIso)
        root.put("season", season)
        root.put("finished", isFinal)
        // Если есть внешний EventID от сервера — пишем его в JSON активной/завершённой игры
        externalEventId
            ?.takeIf { it.isNotBlank() }
            ?.let { root.put("externalEventId", it) }

        val playersRedCurrent = parsePlayers(playersRedText)
        val playersWhiteCurrent = parsePlayers(playersWhiteText)

        val teamsObj = org.json.JSONObject()
        val redObj = org.json.JSONObject()
        val whiteObj = org.json.JSONObject()

        redObj.put("name", "Красные")
        whiteObj.put("name", "Белые")

        val redPlayersArray = org.json.JSONArray()
        playersRedCurrent.forEach { redPlayersArray.put(it) }
        redObj.put("players", redPlayersArray)

        val whitePlayersArray = org.json.JSONArray()
        playersWhiteCurrent.forEach { whitePlayersArray.put(it) }
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

    /**
     * Формируем JSON настроек приложения в формате app_settings.json
     * для выгрузки на Raspberry Pi (/settings/app_settings.json).
     *
     * Всё, что есть в окне настроек, уходит в файл:
     *  - currentSeason       — текущий сезон
     *  - serverUrl           — URL сервера
     *  - apiKey              — API-ключ
     *  - telegramBotToken    — токен бота
     *  - telegramHockeyChatId (PokeChat)
     *  - telegramBotChatId    (ExternalBot)
     */
    fun buildAppSettingsJson(): String {
        val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
        val nowIso = isoFormat.format(Date())

        val root = org.json.JSONObject().apply {
            put("version", 1)
            put("updatedAt", nowIso)

            // Базовые игровые настройки — пока константы
            put("periodDurationMinutes", 20)
            put("intermissionMinutes", 5)
            put("language", "ru")
            put("theme", "dark")
            put("soundEnabled", true)

            // Основные настройки подключения
            put("currentSeason", currentSeason.trim())
            put("serverUrl", serverUrl.trim())
            put("apiKey", apiKey.trim())

            // Telegram-настройки
            put("telegramBotToken", telegramBotToken.trim())
            // PokeChat — хоккейный чат
            put("telegramHockeyChatId", telegramChatId.trim())
            // ExternalBot — чат бота
            put("telegramBotChatId", telegramBotChatId.trim())
        }

        return root.toString(2)
    }

    // Сохранить настройки локально и выгрузить их на Raspberry
    fun performSettingsSaveAndUpload() {
        scope.launch {
            // 1. Сохраняем в SharedPreferences
            setCurrentSeason(context, currentSeason)
            (settingsRepository as SettingsRepositoryImpl).setServerUrl(serverUrl)
            (settingsRepository as SettingsRepositoryImpl).setApiKey(apiKey)
            (settingsRepository as SettingsRepositoryImpl).setTelegramBotToken(telegramBotToken)
            (settingsRepository as SettingsRepositoryImpl).setTelegramChatId(telegramChatId)
            (settingsRepository as SettingsRepositoryImpl).setTelegramBotChatId(telegramBotChatId)

            // 2. Формируем JSON настроек
            val json = buildAppSettingsJson()

            // 3. Отправляем на сервер
            val result = withContext(Dispatchers.IO) {
                raspiRepository.uploadSettings(json)
            }

            // 4. Закрываем диалоги
            showSettingsConfirmDialog = false
            showSettingsDialog = false

            // 5. Сообщение пользователю
            val message = if (result.success) {
                "Настройки сохранены и выгружены"
            } else {
                "Ошибка выгрузки настроек: ${result.errorMessage ?: "неизвестна"}"
            }

            Toast.makeText(
                context,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }


    fun saveExternalEventJson(gameId: String) {
        // База hockey-json
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dbRoot = File(baseDir, "hockey-json")
        if (!dbRoot.exists()) dbRoot.mkdirs()

        // Подпапка для экспортируемых событий
        val exportDir = File(dbRoot, "external-events")
        if (!exportDir.exists()) exportDir.mkdirs()

        // --- агрегируем статистику игроков ---

        data class PlayerStats(
            var team: String,
            var goalsCount: Int,
            var assistsCount: Int
        )

        val statsByName = mutableMapOf<String, PlayerStats>()

        fun ensurePlayer(name: String, teamFallback: String): PlayerStats {
            val existing = statsByName[name]
            if (existing != null) return existing

            val resolvedTeam = when {
                playersRed.contains(name) -> "red"
                playersWhite.contains(name) -> "white"
                else -> teamFallback
            }

            return PlayerStats(
                team = resolvedTeam,
                goalsCount = 0,
                assistsCount = 0
            ).also { statsByName[name] = it }
        }

        // --- считаем голы/передачи ---
        goals.sortedBy { it.eventOrder }.forEach { goal ->
            val teamStr = if (goal.team == Team.RED) "red" else "white"

            ensurePlayer(goal.scorer, teamStr).apply { goalsCount += 1 }

            goal.assist1?.let { name ->
                ensurePlayer(name, teamStr).apply { assistsCount += 1 }
            }
            goal.assist2?.let { name ->
                ensurePlayer(name, teamStr).apply { assistsCount += 1 }
            }
        }

        // --- players[] ---
        val playersArray = org.json.JSONArray()

        statsByName.entries
            .sortedBy { it.key }
            .forEach { (name, ps) ->
                val obj = org.json.JSONObject()
                obj.put("user_id", name)           // ФИО → идентификатор
                obj.put("team", ps.team)
                obj.put("goals", ps.goalsCount)
                obj.put("assists", ps.assistsCount)
                playersArray.put(obj)
            }

// --- goals[] ---
        val goalsArray = org.json.JSONArray()
        val start = gameStartMillis ?: System.currentTimeMillis()

        goals.sortedBy { it.eventOrder }.forEach { goal ->
            val obj = org.json.JSONObject()

            obj.put("team", if (goal.team == Team.RED) "red" else "white")

            obj.put("scorer_id", goal.scorer)
            obj.put("assist1_id", goal.assist1 ?: org.json.JSONObject.NULL)
            obj.put("assist2_id", goal.assist2 ?: org.json.JSONObject.NULL)

            // minute = разница между временем гола и стартом, либо 0, если время гола неизвестно
            val minute: Long =
                if (goal.timestampMillis > 0L) {
                    ((goal.timestampMillis - start) / 60000L).coerceAtLeast(0)
                } else {
                    0L
                }
            obj.put("minute", minute)

            goalsArray.put(obj)
        }


        // --- итоговый JSON ---
        // --- итоговый JSON ---
        val root = org.json.JSONObject().apply {
            // Если есть внешний EventID — используем его, иначе падаем обратно на локальный gameId
            val eventIdForExport = externalEventId?.takeIf { it.isNotBlank() } ?: gameId

            put("event_id", eventIdForExport)
            put("score_white", whiteScore)
            put("score_red", redScore)
            put("players", playersArray)
            put("goals", goalsArray)
        }


        val outFile = File(exportDir, "$gameId.json")
        outFile.writeText(root.toString(2), Charsets.UTF_8)
    }

    fun saveExternalEventJsonForServer(gameId: String) {
        // База hockey-json
        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dbRoot = File(baseDir, "hockey-json")
        if (!dbRoot.exists()) dbRoot.mkdirs()

        // Папка под формат внешнего API
        val exportDir = File(dbRoot, "external-events-api")
        if (!exportDir.exists()) exportDir.mkdirs()

        // Карта "ФИО -> внешний UserID"
        val nameToExternalId: Map<String, String> =
            basePlayers
                .filter { !it.userId.isNullOrBlank() }
                .associate { it.name to it.userId!!.trim() }

        // Внутренняя структура
        data class PlayerStatsExt(
            val userId: String,
            val name: String,
            var team: String,
            var goalsCount: Int,
            var assistsCount: Int
        )

        val statsByName = mutableMapOf<String, PlayerStatsExt>()

        fun ensurePlayer(name: String, teamFallback: String): PlayerStatsExt {
            val existing = statsByName[name]
            if (existing != null) return existing

            val externalId = nameToExternalId[name] ?: name
            val resolvedTeam = when {
                playersRed.contains(name) -> "red"
                playersWhite.contains(name) -> "white"
                else -> teamFallback
            }

            return PlayerStatsExt(
                userId = externalId,
                name = name,
                team = resolvedTeam,
                goalsCount = 0,
                assistsCount = 0
            ).also { statsByName[name] = it }
        }

        // --- Заполняем игроков из составов ---
        playersRed.forEach { ensurePlayer(it, "red") }
        playersWhite.forEach { ensurePlayer(it, "white") }

        // --- Считаем голы ---
        goals.sortedBy { it.eventOrder }.forEach { g ->
            val t = if (g.team == Team.RED) "red" else "white"
            ensurePlayer(g.scorer, t).goalsCount += 1
            g.assist1?.let { ensurePlayer(it, t).assistsCount += 1 }
            g.assist2?.let { ensurePlayer(it, t).assistsCount += 1 }
        }

        // --- players[] ---
        val playersArray = org.json.JSONArray()
        statsByName.values.sortedBy { it.name }.forEach { ps ->
            val o = org.json.JSONObject()
            o.put("user_id", ps.userId)
            o.put("name", ps.name)
            o.put("team", ps.team)
            o.put("goals", ps.goalsCount)
            o.put("assists", ps.assistsCount)
            playersArray.put(o)
        }

        // --- Голы ---
        val start = gameStartMillis ?: System.currentTimeMillis()

        fun toUserIdOrZero(s: String?): Long = s?.toLongOrNull() ?: 0L

        val goalsArray = org.json.JSONArray()

        goals.sortedBy { it.eventOrder }.forEachIndexed { idx, g ->
            val teamStr = if (g.team == Team.RED) "red" else "white"

            val scorerRaw = nameToExternalId[g.scorer]
            val assist1Raw = g.assist1?.let { nameToExternalId[it] }
            val assist2Raw = g.assist2?.let { nameToExternalId[it] }

            val scorerId = toUserIdOrZero(scorerRaw)
            val assist1Id = assist1Raw?.toLongOrNull() ?: if (g.assist1 == null) null else 0L
            val assist2Id = assist2Raw?.toLongOrNull() ?: if (g.assist2 == null) null else 0L

            val minuteJson: Any =
                if (g.timestampMillis > 0L) {
                    ((g.timestampMillis - start) / 60000L).coerceAtLeast(0)
                } else {
                    org.json.JSONObject.NULL   // для внешнего API: "нет данных"
                }

            val o = org.json.JSONObject()
            o.put("idx", idx + 1)
            o.put("team", teamStr)
            o.put("minute", minuteJson)

            // IDs для бота
            o.put("scorer_user_id", scorerId)
            if (assist1Id == null) o.put("assist1_user_id", org.json.JSONObject.NULL)
            else o.put("assist1_user_id", assist1Id)

            if (assist2Id == null) o.put("assist2_user_id", org.json.JSONObject.NULL)
            else o.put("assist2_user_id", assist2Id)

            // Читаемые фамилии
            o.put("scorer_name", g.scorer)
            o.put("assist1_name", g.assist1 ?: org.json.JSONObject.NULL)
            o.put("assist2_name", g.assist2 ?: org.json.JSONObject.NULL)

            goalsArray.put(o)
        }

        // --- event_id должен быть INT (или 0 при ошибке) ---
        val eventIdStr = externalEventId?.takeIf { it.isNotBlank() } ?: gameId
        val eventIdInt = eventIdStr.toIntOrNull() ?: 0

        // --- Итоговый JSON ---
        val root = org.json.JSONObject().apply {
            put("event_id", eventIdInt)        // теперь INT
            put("score_white", whiteScore)
            put("score_red", redScore)
            put("players", playersArray)
            put("goals", goalsArray)
        }

        // Имя файла
        val outFile = File(exportDir, "result_${eventIdInt}.json")
        outFile.writeText(root.toString(2), Charsets.UTF_8)
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

    fun getCurrentIsoTimestamp(): String {
        return java.time.LocalDateTime.now().toString()
    }


    fun buildBaseRosterJson(): String {
        val root = JSONObject()

        root.put("version", 1)
        root.put("updatedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).format(Date()))

        val playersArr = JSONArray()

        basePlayers.sortedBy { it.name }.forEach { p ->
            val obj = JSONObject()

            // Требуемый сервером формат
            obj.put("user_id", p.userId?.toLongOrNull() ?: JSONObject.NULL)
            obj.put("full_name", p.name)

            obj.put("role", when (p.role) {
                PlayerRole.DEFENDER -> "def"
                PlayerRole.FORWARD  -> "fwd"
                PlayerRole.UNIVERSAL -> "uni"
            })

            // Команда всегда null — сервер так требует
            obj.put("team", JSONObject.NULL)

            obj.put("rating", p.rating)

            playersArr.put(obj)
        }

        root.put("players", playersArr)
        return root.toString(2)
    }


    fun saveBaseRosterJsonToFile(json: String): File {
        val dbRoot = getLocalDbRoot(context)
        if (!dbRoot.exists()) dbRoot.mkdirs()

        val baseRosterDir = File(dbRoot, "base_roster")
        if (!baseRosterDir.exists()) baseRosterDir.mkdirs()

        val file = File(baseRosterDir, "base_players.json")
        file.writeText(json, Charsets.UTF_8)
        return file
    }




    suspend fun applyAppSettingsFromServer() {
        val dbRoot = getLocalDbRoot(context)
        val settingsDir = File(dbRoot, "settings")
        val settingsFile = File(settingsDir, "app_settings.json")
        if (!settingsFile.exists()) return

        val text = withContext(Dispatchers.IO) {
            settingsFile.readText(Charsets.UTF_8)
        }

        try {
            val root = org.json.JSONObject(text)

            val tokenFromServer = root.optString("telegramBotToken", "").trim()
            val hockeyChatFromServer = root.optString("telegramHockeyChatId", "").trim()
            val botChatFromServer = root.optString("telegramBotChatId", "").trim()

            // Обновляем репозиторий и локальное состояние там, где значения не пустые
            val impl = settingsRepository as SettingsRepositoryImpl

            if (tokenFromServer.isNotEmpty()) {
                impl.setTelegramBotToken(tokenFromServer)
                telegramBotToken = tokenFromServer
            }

            if (hockeyChatFromServer.isNotEmpty()) {
                impl.setTelegramChatId(hockeyChatFromServer)
                telegramChatId = hockeyChatFromServer
            }

            if (botChatFromServer.isNotEmpty()) {
                impl.setTelegramBotChatId(botChatFromServer)
                telegramBotChatId = botChatFromServer
            }

            // При желании сюда же позже добавим импорт периода / перерыва / языка / темы

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun applyBaseRosterFromServer() {
        val dbRoot = getLocalDbRoot(context)
        val baseRosterDir = File(dbRoot, "base_roster")
        val baseFile = File(baseRosterDir, "base_players.json")

        if (!baseFile.exists()) return

        val text = withContext(Dispatchers.IO) {
            baseFile.readText(Charsets.UTF_8)
        }

        try {
            val root = JSONObject(text)
            val playersArr = root.optJSONArray("players") ?: JSONArray()

            val newBase = mutableListOf<PlayerInfo>()

            for (i in 0 until playersArr.length()) {
                val obj = playersArr.optJSONObject(i) ?: continue

                val fullName = obj.optString("full_name").trim()
                if (fullName.isEmpty()) continue

                // role: "def" / "fwd" / "uni"
                val roleStr = obj.optString("role", "").trim().lowercase(Locale.getDefault())
                val role = when (roleStr) {
                    "def" -> PlayerRole.DEFENDER
                    "fwd" -> PlayerRole.FORWARD
                    "uni", "univ", "universal", "" -> PlayerRole.UNIVERSAL
                    else -> PlayerRole.UNIVERSAL
                }

                val rating = obj.optInt("rating", 0).coerceIn(0, 999)

                val userIdAny = obj.opt("user_id")
                val userIdStr = when {
                    userIdAny == null || userIdAny == JSONObject.NULL -> null
                    else -> userIdAny.toString().trim().ifEmpty { null }
                }

                newBase += PlayerInfo(
                    name = fullName,
                    role = role,
                    rating = rating,
                    userId = userIdStr
                )
            }

            if (newBase.isNotEmpty()) {
                // Обновляем состояние и SharedPreferences
                basePlayers = newBase.sortedBy { it.name }
                saveBasePlayers(prefs, basePlayers)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }






    fun saveAppSettingsJsonToFile(json: String): File {
        val dbRoot = getLocalDbRoot(context)
        if (!dbRoot.exists()) dbRoot.mkdirs()

        val settingsDir = File(dbRoot, "settings")
        if (!settingsDir.exists()) settingsDir.mkdirs()

        val file = File(settingsDir, "app_settings.json")
        file.writeText(json, Charsets.UTF_8)
        return file
    }


    suspend fun uploadAppSettingsToServer() {
        val json = buildAppSettingsJson()
        saveAppSettingsJsonToFile(json)

        val result = raspiRepository.uploadSettings(json)

        if (!result.success) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    "Ошибка выгрузки настроек приложения: ${result.errorMessage ?: "неизвестно"}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }


    // Сохранение active_game.json (может быть как незавершённой, так и завершённой)
    fun saveActiveGameJsonToFile(isFinal: Boolean): File {
        val (_, json) = buildGameJson(isFinal)

        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dbRoot = File(baseDir, "hockey-json")
        if (!dbRoot.exists()) dbRoot.mkdirs()

        val file = File(dbRoot, "active_game.json")
        file.writeText(json, Charsets.UTF_8)
        return file
    }

    fun sendExternalEventToTelegramIfConfigured(gameId: String) {
        val token = telegramBotToken.trim()
        val botChat = telegramBotChatId.trim()         // ExternalTelegramBot.id

        // Если токен пустой или не задан чат бота – ничего не отправляем
        if (token.isEmpty() || botChat.isEmpty()) return

        val baseDir = context.getExternalFilesDir(null) ?: context.filesDir
        val dbRoot = File(baseDir, "hockey-json")
        if (!dbRoot.exists()) dbRoot.mkdirs()

        // Папка под формат внешнего API
        val exportDirApi = File(dbRoot, "external-events-api")
        if (!exportDirApi.exists()) exportDirApi.mkdirs()

        // event_id как INT (ровно так же, как в saveExternalEventJsonForServer)
        val eventIdStr = externalEventId?.takeIf { it.isNotBlank() } ?: gameId
        val eventIdInt = eventIdStr.toIntOrNull() ?: 0

        // Ищем файл по схеме result_<event_id>.json
        val apiFile = File(exportDirApi, "result_${eventIdInt}.json")

        if (!apiFile.exists()) {
            Toast.makeText(
                context,
                "Файл статистики для Telegram не найден (result_${eventIdInt}.json)",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    fun sendFileToTelegram(chatId: String, file: File) {
                        val url = URL("https://api.telegram.org/bot$token/sendDocument")
                        val boundary = "HSB-${System.currentTimeMillis()}-${file.name}-$chatId"
                        val lineEnd = "\r\n"
                        val twoHyphens = "--"

                        val connection = (url.openConnection() as HttpURLConnection).apply {
                            requestMethod = "POST"
                            doInput = true
                            doOutput = true
                            useCaches = false
                            setRequestProperty(
                                "Content-Type",
                                "multipart/form-data; boundary=$boundary"
                            )
                        }

                        DataOutputStream(connection.outputStream).use { output ->
                            // chat_id
                            output.writeBytes(twoHyphens + boundary + lineEnd)
                            output.writeBytes(
                                "Content-Disposition: form-data; name=\"chat_id\"$lineEnd$lineEnd"
                            )
                            output.writeBytes(chatId + lineEnd)

                            // document (наш JSON)
                            output.writeBytes(twoHyphens + boundary + lineEnd)
                            output.writeBytes(
                                "Content-Disposition: form-data; name=\"document\"; filename=\"${file.name}\"$lineEnd"
                            )
                            output.writeBytes("Content-Type: application/json$lineEnd$lineEnd")

                            file.inputStream().use { input ->
                                val buffer = ByteArray(4096)
                                var bytesRead: Int
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    output.write(buffer, 0, bytesRead)
                                }
                            }
                            output.writeBytes(lineEnd)

                            // закрываем multipart
                            output.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd)
                            output.flush()
                        }

                        val code = connection.responseCode
                        if (code != HttpURLConnection.HTTP_OK) {
                            throw RuntimeException("HTTP $code (${file.name}) chat=$chatId")
                        }
                    }

                    // Отправляем файл ТОЛЬКО в чат ExternalTelegramBot.id
                    sendFileToTelegram(botChat, apiFile)
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Статистика отправлена в Telegram (ExternalTelegramBot)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        context,
                        "Ошибка отправки в Telegram: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }


    fun notifyGameJsonUpdated(isFinal: Boolean = false) {
        if (isFinal) {
            // 1. Пишем окончательный JSON в finished/<season>
            val finishedFile = saveGameJsonToFile(isFinal = true)

            val now = System.currentTimeMillis()
            val startedAt = gameStartMillis ?: now
            val finishedAt = now
            val gameId = finishedFile.name.removeSuffix(".json")
            val seasonLocal = currentSeason

            // 2. Обновляем индекс в Room (только для завершённых игр)
            val entry = GameEntry(
                gameId = gameId,
                fileName = finishedFile.name,
                season = seasonLocal,
                localPath = finishedFile.absolutePath,
                startedAt = startedAt,
                finishedAt = finishedAt,
                redScore = redScore,
                whiteScore = whiteScore
            )
            gameDao.upsertGame(entry)

            // 3. JSON для внешней системы (наш внутренний формат)
            saveExternalEventJson(gameId)

            // 3b. JSON для внешнего API (формат event_id + user_id)
            saveExternalEventJsonForServer(gameId)

            // 3c. Автоматическая отправка файла external-events в Telegram (если настроено)
            sendExternalEventToTelegramIfConfigured(gameId)


            // 4. Уведомляем о сохранённой игре (MainActivity шлёт finished-файл на RasPi)
            onGameSaved(finishedFile)

            // 5. И ДОПОЛНИТЕЛЬНО отправляем обновлённый active_game.json
            //    уже со статусом finished = true, чтобы онлайн-табло переключилось
            val activeFile = saveActiveGameJsonToFile(isFinal = true)
            onGameJsonUpdated(activeFile)

        } else {
            // Обычное обновление: только активная игра
            val activeFile = saveActiveGameJsonToFile(isFinal = false)
            onGameJsonUpdated(activeFile)
        }
    }

    // Выгрузка базового списка игроков на Raspberry Pi
    suspend fun uploadBaseRosterToServer() {
        val json =
            buildBaseRosterJson()

        // Локальная запись (опционально — но полезно)
        saveBaseRosterJsonToFile(json)

        // POST /api/upload-base-roster
        val result = withContext(Dispatchers.IO) {
            raspiRepository.uploadBaseRoster(json)
        }

        val msg = if (result.success) {
            "Базовый список игроков выгружен"
        } else {
            "Ошибка выгрузки базового списка игроков: ${result.errorMessage ?: "неизвестна"}"
        }

        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }

    fun commitGoalIfPossible() {
        if (gameFinished) return

        val team = goalInputTeam ?: return
        val scorer = tempScorer ?: return
        val id = editingGoalId ?: nextGoalId++

        val existingOrder = goals.find { it.id == id }?.eventOrder
        val order = existingOrder ?: nextEventOrder++

        // старт игры фиксируем, если ещё не зафиксирован
        val startMillis = gameStartMillis ?: System.currentTimeMillis().also {
            gameStartMillis = it
        }

        val timestamp = System.currentTimeMillis()

        val newEvent = GoalEvent(
            id = id,
            team = team,
            scorer = scorer,
            assist1 = tempAssist1,
            assist2 = tempAssist2,
            eventOrder = order,
            timestampMillis = timestamp
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

                            // Зафиксируем исходные значения настроек на момент открытия
                            initialSeason = currentSeason
                            initialServerUrl = serverUrl
                            initialApiKey = apiKey
                            initialTelegramBotToken = telegramBotToken
                            initialTelegramChatId = telegramChatId
                            initialTelegramBotChatId = telegramBotChatId

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
                    onClick = {
                        // Меню просто закрываем, без какой-либо логики настроек
                        showActionsMenu = false
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

    // --- ДИАЛОГ: ПОДТВЕРЖДЕНИЕ ИЗМЕНЕНИЯ НАСТРОЕК ---

    if (showSettingsConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showSettingsConfirmDialog = false },
            title = { Text("Подтверждение", fontSize = 20.sp) },
            text = {
                Text(
                    text = "Вы изменили настройки, вы уверены в их правильности?",
                    color = DialogTextColor,
                    fontSize = 16.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Сохраняем и выгружаем
                        performSettingsSaveAndUpload()
                    },
                    colors = dialogButtonColors()
                ) {
                    Text("Да", fontSize = 16.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        // Просто закрываем диалог подтверждения, настройки остаются открыты
                        showSettingsConfirmDialog = false
                    },
                    colors = dialogButtonColors()
                ) {
                    Text("Нет", fontSize = 16.sp)
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
                                // Имя
                                Text(
                                    text = player.name,
                                    modifier = Modifier.weight(1f),
                                    color = DialogTextColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 16.sp
                                )

                                // Роль (циклический переключатель)
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

                                // Рейтинг
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

                                // UserID (внешний ID игрока)
                                var userIdText by remember(player.name + "_uid") {
                                    mutableStateOf(player.userId ?: "")
                                }

                                Box(
                                    modifier = Modifier
                                        .width(90.dp)
                                        .height(24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    BasicTextField(
                                        value = userIdText,
                                        onValueChange = { text ->
                                            userIdText = text

                                            val cleaned = text.trim().ifEmpty { null }

                                            basePlayers = basePlayers
                                                .map { p ->
                                                    if (p.name == player.name) p.copy(userId = cleaned) else p
                                                }
                                                .sortedBy { it.name }
                                        },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Number
                                        ),
                                        textStyle = MaterialTheme.typography.bodySmall.copy(
                                            fontSize = 12.sp,
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

                                // Удалить игрока
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
                        // 1. Локально сохраняем в SharedPreferences
                        saveBasePlayers(prefs, basePlayers)
                        showBasePlayersDialog = false

                        // 2. Выгружаем base_players.json на сервер (в фоне)
                        scope.launch {
                            uploadBaseRosterToServer()
                        }

                    },
                    colors = dialogButtonColors()
                ) {
                    Text("Сохранить", fontSize = 16.sp)
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

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("URL сервера") },
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

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text("API ключ") },
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

                    OutlinedTextField(
                        value = telegramBotToken,
                        onValueChange = { telegramBotToken = it },
                        label = { Text("Telegram Bot Token") },
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
                    // НОВОЕ поле: чат для бота (Chat)
                    OutlinedTextField(
                        value = telegramBotChatId,
                        onValueChange = { telegramBotChatId = it },
                        label = { Text("External Telegram Bot ID") },
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

                    OutlinedTextField(
                        value = telegramChatId,
                        onValueChange = { telegramChatId = it },
                        label = { Text("Hockey Chat ID") },
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
                            // Если уже идёт синхронизация — игнорируем повторный клик
                            if (isSyncing) return@TextButton

                            scope.launch {
                                isSyncing = true
                                try {
                                    val result = syncRepository.syncDatabase()

                                    if (result is SyncResult.Success) {
                                        // 1. Файлы обновлены – пересобираем индекс игр
                                        rebuildGamesIndexFromFilesystem()

                                        // 2. Импортируем настройки приложения из settings/app_settings.json
                                        applyAppSettingsFromServer()
                                        // 3. Импортируем базовый список игроков из base_roster/base_players.json
                                        applyBaseRosterFromServer()
                                    }

                                    val message = when (result) {
                                        is SyncResult.Success -> "Синхронизация выполнена"
                                        is SyncResult.Error   -> "Ошибка синхронизации: ${result.message}"
                                    }

                                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                                } finally {
                                    // В ЛЮБОМ случае отпускаем кнопку
                                    isSyncing = false
                                }
                            }
                        },
                        colors = dialogButtonColors(),
                        enabled = !isSyncing
                    ) {
                        Text(
                            if (isSyncing) "Синхронизация..." else "Синхронизировать базу",
                            fontSize = 16.sp
                        )
                    }




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
                        // Проверяем, есть ли реальные изменения
                        val changed =
                            currentSeason != initialSeason ||
                                    serverUrl != initialServerUrl ||
                                    apiKey != initialApiKey ||
                                    telegramBotToken != initialTelegramBotToken ||
                                    telegramChatId != initialTelegramChatId ||
                                    telegramBotChatId != initialTelegramBotChatId

                        if (changed) {
                            // Показываем диалог подтверждения изменений
                            showSettingsConfirmDialog = true
                        } else {
                            // Ничего не менялось — просто закрываем настройки без выгрузки и тоста
                            showSettingsDialog = false
                        }
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
                    TextButton(
                        onClick = {
                            scope.launch {
                                val result = raspiRepository.downloadRoster()

                                if (!result.success || result.json == null) {
                                    val msg = result.error?.let { "Ошибка загрузки состава: $it" }
                                        ?: "Ошибка загрузки состава (пустой ответ)"
                                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                    return@launch
                                }

                                try {
                                    val jsonText = result.json
                                    val array = org.json.JSONArray(jsonText)

                                    val importedItems = mutableListOf<ImportedRosterItem>()
                                    var detectedEventId: String? = null

                                    for (i in 0 until array.length()) {
                                        val obj = array.optJSONObject(i) ?: continue

                                        val fullName = obj.optString("full_name").trim()
                                        if (fullName.isEmpty()) continue

                                        val teamRaw = obj.optString("team").trim().lowercase(Locale.getDefault())
                                        if (teamRaw != "red" && teamRaw != "white") continue

                                        val role = obj.optString("role", "").trim().ifEmpty { null }

                                        val lineAny = obj.opt("line")
                                        val line = lineAny?.toString()?.toIntOrNull()

                                        val userId = obj.opt("user_id")
                                            ?.toString()
                                            ?.trim()
                                            ?.ifEmpty { null }

                                        val eventId = obj.opt("event_id")
                                            ?.toString()
                                            ?.trim()
                                            ?.ifEmpty { null }

                                        if (detectedEventId == null && eventId != null) {
                                            detectedEventId = eventId
                                        }

                                        importedItems += ImportedRosterItem(
                                            fullName = fullName,
                                            team = teamRaw,
                                            role = role,
                                            line = line,
                                            userId = userId,
                                            eventId = eventId
                                        )
                                    }

                                    if (importedItems.isEmpty()) {
                                        Toast.makeText(
                                            context,
                                            "Файл состава пуст или не содержит корректных записей",
                                            Toast.LENGTH_LONG
                                        ).show()
                                        return@launch
                                    }

                                    // Проставляем внешний EventID (если он прилетел) и сохраняем в prefs
                                    updateExternalEventId(detectedEventId)


                                    // Строим карту базовых игроков по нормализованному имени
                                    val baseByKey = basePlayers.associateBy { normalizeName(it.name) }.toMutableMap()
                                    val updatedBase = basePlayers.toMutableList()

                                    val redNames = mutableListOf<String>()
                                    val whiteNames = mutableListOf<String>()
                                    val unknown = mutableListOf<ImportedRosterItem>()

                                    for (item in importedItems) {
                                        val key = normalizeName(item.fullName)
                                        val existing = baseByKey[key]

                                        if (existing != null) {
                                            var updated = existing

                                            // СТАЛО: обновляем, если с сервера пришёл userId и он отличается от локального
                                            if (item.userId != null && item.userId != updated.userId) {
                                                updated = updated.copy(userId = item.userId)
                                            }


                                            // TODO: при желании можно в будущем обновлять role/line

                                            if (updated !== existing) {
                                                val idx = updatedBase.indexOfFirst {
                                                    normalizeName(it.name) == key
                                                }
                                                if (idx >= 0) {
                                                    updatedBase[idx] = updated
                                                }
                                                baseByKey[key] = updated
                                            }

                                            // В состав кладём имя из базы (чтобы не разводить варианты написания)
                                            when (item.team) {
                                                "red" -> redNames += existing.name
                                                "white" -> whiteNames += existing.name
                                            }
                                        } else {
                                            // Такого игрока нет в базовом списке – будем спрашивать, что делать
                                            unknown += item
                                        }
                                    }

                                    // Обновляем базовый список (с уже проставленными userId) и сохраняем в prefs
                                    basePlayers = updatedBase.sortedBy { it.name }
                                    saveBasePlayers(prefs, basePlayers)

                                    importedRedFromRoster = redNames.distinct()
                                    importedWhiteFromRoster = whiteNames.distinct()

                                    if (unknown.isEmpty()) {
                                        // Все игроки известны – просто применяем состав
                                        playersRedText = importedRedFromRoster.joinToString("\n")
                                        playersWhiteText = importedWhiteFromRoster.joinToString("\n")

                                        Toast.makeText(
                                            context,
                                            "Состав загружен: красные ${importedRedFromRoster.size}, белые ${importedWhiteFromRoster.size}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } else {
                                        // Есть новые игроки – откроем отдельный диалог для решения
                                        unknownRosterItems = unknown
                                        showUnknownPlayersDialog = true

                                        Toast.makeText(
                                            context,
                                            "Состав загружен, новые игроки: ${unknown.size}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                } catch (e: Exception) {
                                    Toast.makeText(
                                        context,
                                        "Ошибка разбора состава: ${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        },


                        colors = dialogButtonColors(),
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(bottom = 8.dp)
                    ) {
                        Text("Загрузить состав с сервера", fontSize = 14.sp)
                    }

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

    // --- ДИАЛОГ: НОВЫЕ ИГРОКИ ИЗ СОСТАВА ---

    if (showUnknownPlayersDialog && unknownRosterItems.isNotEmpty()) {
        // Для каждого игрока храним решение: true = добавить, false = игнорировать
        val decisions = remember(unknownRosterItems) {
            mutableStateMapOf<String, Boolean>().apply {
                unknownRosterItems.forEach { item ->
                    val key = item.userId ?: item.fullName
                    this[key] = true   // по умолчанию всех добавляем
                }
            }
        }

        AlertDialog(
            onDismissRequest = {
                showUnknownPlayersDialog = false
                unknownRosterItems = emptyList()
            },
            title = { Text("Новые игроки из состава", fontSize = 20.sp) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 500.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = "Некоторые игроки из файла состава не найдены в базовой базе. Отметьте, кого добавить.",
                        fontSize = 14.sp,
                        color = DialogTextColor,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    unknownRosterItems.forEach { item ->
                        val key = item.userId ?: item.fullName
                        val checked = decisions[key] ?: true

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    decisions[key] = isChecked
                                }
                            )

                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = item.fullName,
                                    color = DialogTextColor,
                                    fontSize = 16.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = buildString {
                                        append("Команда: ")
                                        append(if (item.team == "red") "красные" else "белые")
                                        item.userId?.let { id ->
                                            append(", UserID: ")
                                            append(id)
                                        }
                                    },
                                    color = DialogTextColor,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        var newBase = basePlayers.toMutableList()

                        // Кого решили добавить
                        val toAdd = unknownRosterItems.filter { item ->
                            val key = item.userId ?: item.fullName
                            decisions[key] != false
                        }

                        toAdd.forEach { item ->
                            val name = item.fullName.trim()

                            // Добавляем в базовый список, если такого имени ещё нет
                            if (newBase.none { it.name.equals(name, ignoreCase = true) }) {
                                val playerInfo = PlayerInfo(
                                    name = name,
                                    role = PlayerRole.UNIVERSAL, // позже можно маппить из item.role
                                    rating = 0,
                                    userId = item.userId
                                )
                                newBase.add(playerInfo)
                            }

                            // И сразу добавляем в соответствующую команду
                            when (item.team) {
                                "red" -> {
                                    importedRedFromRoster =
                                        (importedRedFromRoster + name).distinct()
                                }
                                "white" -> {
                                    importedWhiteFromRoster =
                                        (importedWhiteFromRoster + name).distinct()
                                }
                            }
                        }

                        basePlayers = newBase.sortedBy { it.name }
                        saveBasePlayers(prefs, basePlayers)

                        // Обновляем текстовые составы целиком из импортированных списков
                        playersRedText = importedRedFromRoster.joinToString("\n")
                        playersWhiteText = importedWhiteFromRoster.joinToString("\n")

                        showUnknownPlayersDialog = false
                        unknownRosterItems = emptyList()

                        Toast.makeText(
                            context,
                            "Состав загружен: красные ${importedRedFromRoster.size}, белые ${importedWhiteFromRoster.size}",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    colors = dialogButtonColors()
                ) {
                    Text("Применить", fontSize = 16.sp)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUnknownPlayersDialog = false
                        unknownRosterItems = emptyList()
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
                        onFinalScreenshotRequested()
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

                        // 1a. Очистить внешний EventID, чтобы не тянуть его в новую игру
                        updateExternalEventId(null)

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

    // --- ДИАЛОГ: СПИСОК ЗАВЕРШЁННЫХ ИГР ---

    if (showHistoryDialog) {
        // при каждом изменении historyRefreshKey список игр перечитывается
        val savedGames by remember(historyRefreshKey) {
            mutableStateOf(gameDao.getAllGames())
        }

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

                        // 1. Закрываем окна "Удалить?" и "Протокол"
                        showDeleteGameConfirm = false
                        showHistoryDetailsDialog = false

                        // 2. Если есть что удалять — зовём Activity
                        if (entry != null) {
                            onGameDeleted(entry.gameId, file) { success ->
                                if (success) {
                                    // после успешного удаления дергаем ключ,
                                    // диалог "Завершённые игры" останется открыт,
                                    // но перечитает список из базы
                                    historyRefreshKey++
                                }
                            }
                        }

                        // 3. Чистим выбранную игру
                        historySelectedEntry = null
                        historySelectedFile = null
                        historyDetailsText = ""
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




