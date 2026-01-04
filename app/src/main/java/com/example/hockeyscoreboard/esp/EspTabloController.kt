package com.example.hockeyscoreboard.esp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class EspTabloController(appContext: Context) {

    private val appCtx = appContext.applicationContext

    companion object {
        private const val PORT = 4210

        private const val MAGIC: Byte = 0xA5.toByte()
        private const val VERSION: Byte = 0x01.toByte()
        private const val CMD_ACK: Byte = 0x7F.toByte()

        private const val CMD_MACRO_MODE_SWITCH: Byte = 0x40.toByte()
        private const val CMD_MACRO_RESET_SCOREBOARD: Byte = 0x41.toByte()
        private const val CMD_SIREN: Byte = 0x60.toByte()


        // --- Siren timings (ms) ---
        private const val SIREN_SHORT_BEEP_MS = 300
        private const val SIREN_SHORT_PAUSE_MS = 300
        private const val SIREN_LONG_BEEP_MS = 1000

        private const val SIREN_START_BEEP_COUNT = 2   // старт игры
        private const val SIREN_PAUSE_BEEP_COUNT = 3   // пауза


        private const val ACK_LEN = 7

        private const val RETRIES = 3
        private const val ACK_TIMEOUT_MS = 800
        private const val RETRY_DELAY_MS = 120L

        private const val MAX_LOG_LINES = 5000
        private const val TRIM_TO_LINES = 3000
    }

    private val _status = MutableStateFlow("ESP: IP не задан")
    val status: StateFlow<String> = _status

    private val endpointLock = Any()
    private var endpoint: InetSocketAddress? = null

    private val sendMutex = Mutex()
    private val idGen = AtomicInteger(1)

    private val logFile: File by lazy {
        val baseDir = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        File(baseDir, "esp_health.log")
    }
    private val logTsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun getLogFilePath(): String = logFile.absolutePath

    fun readLogLinesNewestFirst(maxLines: Int = 400): List<String> {
        return runCatching {
            if (!logFile.exists()) return emptyList()
            logFile.readLines(Charsets.UTF_8).asReversed().take(maxLines)
        }.getOrElse { emptyList() }
    }

    private fun logLine(msg: String) {
        runCatching {
            val ts = logTsFmt.format(Date())
            logFile.appendText("$ts  $msg\n", Charsets.UTF_8)
            trimLogIfNeeded()
        }
    }

    private fun trimLogIfNeeded() {
        runCatching {
            if (!logFile.exists()) return
            val all = logFile.readLines(Charsets.UTF_8)
            if (all.size <= MAX_LOG_LINES) return
            logFile.writeText(all.takeLast(TRIM_TO_LINES).joinToString("\n") + "\n", Charsets.UTF_8)
        }
    }

    // --- No discovery / no ping (kept for UI compatibility) ---
    fun startDiscovery() = logLine("DISCOVERY ignored (static IPv4)")
    fun stopDiscovery() {}
    fun startHealthMonitor() = logLine("HEALTH monitor ignored (no ping)")
    fun stopHealthMonitor() {}

    fun setStaticEndpoint(ipv4: String) {
        val host = ipv4.trim()
        synchronized(endpointLock) {
            endpoint = if (host.isBlank()) null else InetSocketAddress(host, PORT)
        }
        _status.value = if (host.isBlank()) "ESP: IP не задан" else "ESP: $host:$PORT"
        logLine("Endpoint set to '${host.ifBlank { "<empty>" }}' :$PORT")
    }

    suspend fun press(btn: String): Boolean = sendPresses(btn, 1)

    suspend fun sendPresses(btn: String, count: Int): Boolean {
        val cmd = mapButtonToCmd(btn)
        if (cmd == null) {
            logLine("PRESS ignored: unknown button='$btn' (TODO mapping?)")
            _status.value = "ESP: неизвестная кнопка '$btn'"
            return false
        }
        repeat(count.coerceAtLeast(1)) {
            val ok = sendCommand(cmd, payload = null)
            if (!ok) return false
            delay(10L)
        }
        return true
    }

    suspend fun switchToScoreboardMode(): Boolean =
        sendCommand(CMD_MACRO_MODE_SWITCH, payload = null)

    suspend fun resetScoreboard(): Boolean =
        sendCommand(CMD_MACRO_RESET_SCOREBOARD, payload = null)

    /**
     * Сирена:
     * CMD=0x60, payload: COUNT(1) + (on16LE, off16LE)*COUNT
     * Ограничение в прошивке: COUNT 1..3.
     */
    suspend fun sendSirenPattern(pattern: List<Pair<Int, Int>>): Boolean {
        val count = pattern.size
        if (count !in 1..3) {
            logLine("SIREN ignored: bad count=$count (allowed 1..3)")
            return false
        }

        val payload = ByteArray(1 + count * 4)
        payload[0] = count.toByte()

        var pos = 1
        for ((onMs, offMs) in pattern) {
            val on = onMs.coerceIn(0, 60000)
            val off = offMs.coerceIn(0, 60000)

            payload[pos++] = (on and 0xFF).toByte()
            payload[pos++] = ((on ushr 8) and 0xFF).toByte()
            payload[pos++] = (off and 0xFF).toByte()
            payload[pos++] = ((off ushr 8) and 0xFF).toByte()
        }

        return sendCommand(CMD_SIREN, payload)
    }



    // --- Siren presets (no numbers in UI code) ---
    suspend fun sirenGameStart(): Boolean {
        // 2 коротких: 300/300 + 300/0
        return sendSirenPattern(
            listOf(
                SIREN_SHORT_BEEP_MS to SIREN_SHORT_PAUSE_MS,
                SIREN_SHORT_BEEP_MS to 0
            )
        )
    }

    suspend fun sirenPause(): Boolean {
        // 3 коротких: 300/300 + 300/300 + 300/0
        return sendSirenPattern(
            listOf(
                SIREN_SHORT_BEEP_MS to SIREN_SHORT_PAUSE_MS,
                SIREN_SHORT_BEEP_MS to SIREN_SHORT_PAUSE_MS,
                SIREN_SHORT_BEEP_MS to 0
            )
        )
    }

    suspend fun sirenShiftEndLong(): Boolean {
        // 1 длинный: 1000/0
        return sendSirenPattern(listOf(SIREN_LONG_BEEP_MS to 0))
    }


    suspend fun sendSiren(onMs: Int, offMs: Int): Boolean {
        return sendSirenPattern(listOf(onMs to offMs))
    }


    private suspend fun sendCommand(cmd: Byte, payload: ByteArray?): Boolean = withContext(Dispatchers.IO) {
        val ep = synchronized(endpointLock) { endpoint }
        if (ep == null) {
            _status.value = "ESP: IP не задан"
            logLine("SEND failed: endpoint is null")
            return@withContext false
        }

        sendMutex.withLock {
            val id = nextIdU16()
            val pkt = buildPacket(cmd, id, payload)
            val payloadLen = payload?.size ?: 0
            val cmdHex = "0x${"%02X".format(cmd)}"
            logLine("SEND cmd=$cmdHex id=$id len=$payloadLen to ${ep.hostString}:${ep.port}")

            repeat(RETRIES) { attempt ->
                val ok = trySendOnce(ep, pkt, id, cmdHex, attempt + 1)

                if (ok) {
                    _status.value = "ESP: ok (${ep.hostString})"

                    return@withContext true
                }
                logLine("RETRY ${(attempt + 1)}/$RETRIES cmd=0x${"%02X".format(cmd)} id=$id")
                delay(RETRY_DELAY_MS)
            }

            _status.value = "ESP: нет ACK (${ep.hostString})"
            false
        }
    }

    private fun trySendOnce(
        ep: InetSocketAddress,
        pkt: ByteArray,
        id: Int,
        cmdHex: String,
        attemptNo: Int
    ): Boolean {
        DatagramSocket().use { sock ->
            sock.soTimeout = ACK_TIMEOUT_MS
            val dp = DatagramPacket(pkt, pkt.size, ep.address ?: InetAddress.getByName(ep.hostString), ep.port)

            // логируем каждую попытку отправки
            logLine("SEND attempt=$attemptNo cmd=$cmdHex id=$id to ${ep.hostString}:${ep.port}")
            val sentAt = System.currentTimeMillis()
            sock.send(dp)

            val buf = ByteArray(ACK_LEN)
            val rcv = DatagramPacket(buf, buf.size)

            val deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                try {
                    sock.receive(rcv)
                } catch (_: SocketTimeoutException) {
                    return false
                }

                if (rcv.length != ACK_LEN) continue
                if (buf[0] != MAGIC || buf[1] != VERSION || buf[2] != CMD_ACK) continue

                val ackId = ((buf[4].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
                if (ackId != id) continue

                val status = buf[5].toInt() and 0xFF
                val rttMs = System.currentTimeMillis() - sentAt
                logLine("ACK attempt=$attemptNo cmd=$cmdHex id=$ackId status=$status rtt=${rttMs}ms")

                return status == 1  // оставляем как у вас сейчас (раз работает)
            }
        }
        return false
    }


    private fun buildPacket(cmd: Byte, idU16: Int, payload: ByteArray?): ByteArray {
        val p = payload ?: ByteArray(0)
        require(p.size <= 255) { "Payload too long: ${p.size}" }

        return ByteArray(6 + p.size).also { out ->
            out[0] = MAGIC
            out[1] = VERSION
            out[2] = cmd
            out[3] = (idU16 and 0xFF).toByte()
            out[4] = ((idU16 ushr 8) and 0xFF).toByte()
            out[5] = (p.size and 0xFF).toByte()
            if (p.isNotEmpty()) System.arraycopy(p, 0, out, 6, p.size)
        }
    }

    private fun nextIdU16(): Int {
        val next = idGen.getAndIncrement()
        val id = next % 65535
        return if (id <= 0) 1 else id
    }

    // "ТАЙМЕР" intentionally left unmapped (placeholder).
    private fun mapButtonToCmd(btn: String): Byte? = when (btn.trim()) {
        "-ярк" -> 0x01
        "+ярк" -> 0x02
        "выход" -> 0x03
        "ПрВРМ" -> 0x04
        "ВРЕМЯ" -> 0x05
        "-" -> 0x08
        "СЕК" -> 0x0A
        "РЕД" -> 0x0C
        "0" -> 0x0E
        "1" -> 0x0F
        "2" -> 0x10
        "3" -> 0x11
        "4" -> 0x13
        "5" -> 0x14
        "6" -> 0x15
        "7" -> 0x17
        "8" -> 0x18
        "9" -> 0x19
        "ПрТМП2" -> 0x1B
        else -> null
    }?.toByte()
}
