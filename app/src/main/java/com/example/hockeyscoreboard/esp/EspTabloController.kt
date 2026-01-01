package com.example.hockeyscoreboard.esp

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class EspEndpoint(val host: InetAddress, val port: Int)

class EspTabloController(
    appContext: Context
) {
    companion object {
        private const val SERVICE_TYPE = "_hockeytablo._udp."
        private const val DEFAULT_PORT = 4210

        // --- ЛОГ ---
        private const val MAX_LOG_LINES = 5000
        private const val TRIM_EVERY_N_LINES = 100
        private const val TRIM_MIN_INTERVAL_MS = 30_000L

        // --- HEALTH (PING/PONG) ---
        private const val PINGPONG_TIMEOUT_MS = 1500
        private const val PINGPONG_INTERVAL_MS = 5000L
        private const val PINGPONG_FAILS_TO_REDISCOVER = 5

        // --- UDP ACK для управляющих команд (PRESS/SIREN) ---
        // 500ms слишком впритык (у вас уже были 503ms/ретраи)
        private const val CMD_ACK_TIMEOUT_MS = 800
        private const val RETRIES = 3
        private const val RETRY_DELAY_MS = 120L
    }

    private val appCtx: Context = appContext.applicationContext

    private val nsdManager: NsdManager =
        appCtx.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val wifiManager: WifiManager =
        appCtx.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null

    private val _endpoint = MutableStateFlow<EspEndpoint?>(null)
    val endpoint: StateFlow<EspEndpoint?> = _endpoint

    private val _status = MutableStateFlow("ESP: не найдена")
    val status: StateFlow<String> = _status

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveInProgress = false
    private val resolveLock = Any()

    // Очередь команд: строго 1 активная команда
    private val sendMutex = Mutex()

    // Отдельный флаг, чтобы health-check не конкурировал с отправкой команд и не грузил ESP
    private val busySending = AtomicBoolean(false)

    private val idGen = AtomicInteger(1)

    // -------------------- ЛОГИ --------------------

    // Файл: /storage/emulated/0/Android/data/<pkg>/files/esp_health.log
    private val logFile: File = run {
        val baseDir = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        File(baseDir, "esp_health.log")
    }

    private val logTsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private var logLinesSinceTrim = 0
    private var lastTrimAtMs = 0L

    private fun trimLogFileIfNeeded(nowMs: Long) {
        runCatching {
            if (!logFile.exists()) return
            val lines = logFile.readLines(Charsets.UTF_8)
            if (lines.size <= MAX_LOG_LINES) return

            val tail = lines.takeLast(MAX_LOG_LINES).joinToString(separator = "\n") + "\n"
            logFile.writeText(tail, Charsets.UTF_8)
        }.onSuccess {
            lastTrimAtMs = nowMs
        }
    }

    private fun logLine(message: String) {
        runCatching {
            val ts = logTsFmt.format(Date())
            logFile.appendText("$ts  $message\n", Charsets.UTF_8)

            logLinesSinceTrim++

            val nowMs = System.currentTimeMillis()
            val canTrimByCount = logLinesSinceTrim >= TRIM_EVERY_N_LINES
            val canTrimByTime = (nowMs - lastTrimAtMs) >= TRIM_MIN_INTERVAL_MS

            if (canTrimByCount && canTrimByTime) {
                logLinesSinceTrim = 0
                trimLogFileIfNeeded(nowMs)
            }
        }
    }

    fun getLogFilePath(): String = logFile.absolutePath

    suspend fun readLogLinesNewestFirst(): List<String> = withContext(Dispatchers.IO) {
        if (!logFile.exists()) return@withContext emptyList()
        logFile.readLines(Charsets.UTF_8).asReversed()
    }

    // -------------------- HEALTH MONITOR (PING/PONG) --------------------

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var healthJob: Job? = null
    private var consecutivePingFails: Int = 0

    /**
     * Ping/Pong без JSON:
     * send: "PING"
     * recv: "PONG"
     *
     * Минимальная нагрузка на ESP и на Android.
     */
    private suspend fun pingPongOnce(ep: EspEndpoint): Pair<Boolean, Long> = withContext(Dispatchers.IO) {
        val data = "PING".toByteArray(Charsets.UTF_8)

        DatagramSocket().use { socket ->
            socket.soTimeout = PINGPONG_TIMEOUT_MS
            val start = System.currentTimeMillis()
            try {
                val out = DatagramPacket(data, data.size, ep.host, ep.port)
                socket.send(out)

                val buf = ByteArray(32)
                val inp = DatagramPacket(buf, buf.size)
                socket.receive(inp)

                val rtt = System.currentTimeMillis() - start
                val resp = String(inp.data, 0, inp.length, Charsets.UTF_8).trim()
                Pair(resp == "PONG", rtt)
            } catch (t: Throwable) {
                val rtt = System.currentTimeMillis() - start
                logLine("PING EX ${t.javaClass.simpleName} msg=${t.message} rtt=${rtt}ms ep=${ep.host.hostAddress}:${ep.port}")
                Pair(false, rtt)
            }
        }
    }

    /**
     * Мониторинг доступности ESP каждые PINGPONG_INTERVAL_MS.
     * Повторный вызов не создаёт второй job.
     */
    fun startHealthMonitor() {
        if (healthJob != null) return

        logLine("HEALTH start (file=${logFile.absolutePath})")

        healthJob = ioScope.launch {
            while (true) {
                val ep = endpoint.value
                if (ep == null) {
                    if (discoveryListener == null) {
                        logLine("HEALTH no-endpoint -> startDiscovery()")
                        withContext(Dispatchers.Main) { startDiscovery() }
                    } else {
                        logLine("HEALTH no-endpoint (discovery running)")
                    }
                } else {
                    // Если в данный момент активно шлём команды — не дёргаем ESP параллельными PING
                    if (busySending.get()) {
                        logLine("PING skip (busy sending)")
                    } else {
                        val (ok, rtt) = pingPongOnce(ep)
                        if (ok) {
                            consecutivePingFails = 0
                            _status.value = "ESP: OK ${ep.host.hostAddress}:${ep.port} (rtt=${rtt}ms)"
                            logLine("PING ok rtt=${rtt}ms ep=${ep.host.hostAddress}:${ep.port}")
                        } else {
                            consecutivePingFails++
                            _status.value = "ESP: ping fail #$consecutivePingFails (rtt=${rtt}ms)"
                            logLine("PING FAIL #$consecutivePingFails rtt=${rtt}ms ep=${ep.host.hostAddress}:${ep.port}")

                            if (consecutivePingFails >= PINGPONG_FAILS_TO_REDISCOVER) {
                                _status.value = "ESP: ping fail (re-discovery)"
                                withContext(Dispatchers.Main) { startDiscovery() }
                            }
                        }
                    }
                }

                delay(PINGPONG_INTERVAL_MS)
            }
        }
    }

    fun stopHealthMonitor() {
        healthJob?.cancel()
        healthJob = null
        consecutivePingFails = 0
        logLine("HEALTH stop")
    }

    // -------------------- NSD DISCOVERY --------------------

    fun startDiscovery() {
        if (discoveryListener != null) return

        _status.value = "ESP: поиск…"
        logLine("DISCOVERY start")

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _status.value = "ESP: поиск…"

                // Для некоторых устройств mDNS/DNS-SD без MulticastLock не работает
                if (multicastLock == null) {
                    multicastLock = wifiManager.createMulticastLock("hockeytablo-mdns").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) return

                synchronized(resolveLock) {
                    if (resolveInProgress) return
                    resolveInProgress = true
                }

                logLine("DISCOVERY found name=${serviceInfo.serviceName}")

                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            synchronized(resolveLock) { resolveInProgress = false }
                            logLine("DISCOVERY resolve FAILED code=$errorCode name=${serviceInfo.serviceName}")
                            _status.value = "ESP: resolve ошибка ($errorCode)"
                        }

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            synchronized(resolveLock) { resolveInProgress = false }

                            val host = resolved.host ?: run {
                                _status.value = "ESP: resolve без host"
                                logLine("DISCOVERY resolve NO_HOST name=${resolved.serviceName}")
                                return
                            }
                            val port = if (resolved.port > 0) resolved.port else DEFAULT_PORT

                            _endpoint.value = EspEndpoint(host, port)
                            logLine("DISCOVERY resolved ep=${host.hostAddress}:$port name=${resolved.serviceName}")

                            _status.value = "ESP: найдена ${host.hostAddress}:$port"
                        }
                    }
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // В реальности mDNS может “флапать”. Не сносим endpoint мгновенно — иначе UI начинает дёргаться.
                logLine("DISCOVERY lost name=${serviceInfo.serviceName}")
                _status.value = "ESP: mDNS lost (keep last endpoint)"
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _status.value = "ESP: поиск остановлен"
                logLine("DISCOVERY stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _status.value = "ESP: старт поиска ошибка ($errorCode)"
                logLine("DISCOVERY start FAILED code=$errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                _status.value = "ESP: stop ошибка ($errorCode)"
                logLine("DISCOVERY stop FAILED code=$errorCode")
                stopDiscovery()
            }
        }

        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            _status.value = "ESP: discovery недоступен (${t.javaClass.simpleName})"
            logLine("DISCOVERY exception ${t.javaClass.simpleName}")
            discoveryListener = null
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
        resolveInProgress = false

        multicastLock?.let { runCatching { if (it.isHeld) it.release() } }
        multicastLock = null

        logLine("DISCOVERY stop")
    }

    // -------------------- UDP COMMANDS --------------------

    suspend fun press(btn: String): Boolean = sendPresses(btn, 1)

    /**
     * Переводит физическое табло из режима часов в режим хоккейного табло.
     * По ТЗ это тройное нажатие кнопки «выход».
     */
    suspend fun switchToScoreboardMode(): Boolean = sendPresses("выход", 3)

    suspend fun sendPresses(btn: String, count: Int): Boolean {
        if (count <= 0) return true

        return sendMutex.withLock {
            busySending.set(true)
            try {
                val ep = endpoint.value
                if (ep == null) {
                    _status.value = "ESP: не найдена (повторный поиск)"
                    withContext(Dispatchers.Main) { startDiscovery() }
                    return@withLock false
                }

                var okAll = true
                for (i in 0 until count) {
                    val ok = sendOnceWithRetries(ep, btn)
                    okAll = okAll && ok
                    if (!ok) break
                }
                okAll
            } finally {
                busySending.set(false)
            }
        }
    }

    /**
     * Управление сиреной через расширение протокола cmd:"SIREN".
     *
     * @param onMs  длительности звучания, 1..5 элементов
     * @param offMs паузы после каждого звучания, 1..5 элементов (последний может быть 0)
     */
    suspend fun sendSiren(onMs: List<Int>, offMs: List<Int>): Boolean {
        val count = onMs.size
        if (count !in 1..5) return false
        if (offMs.size != count) return false

        return sendMutex.withLock {
            busySending.set(true)
            try {
                val ep = endpoint.value
                if (ep == null) {
                    _status.value = "ESP: не найдена (повторный поиск)"
                    withContext(Dispatchers.Main) { startDiscovery() }
                    return@withLock false
                }

                sendSirenOnceWithRetries(ep, onMs, offMs)
            } finally {
                busySending.set(false)
            }
        }
    }

    private suspend fun sendSirenOnceWithRetries(
        ep: EspEndpoint,
        onMs: List<Int>,
        offMs: List<Int>
    ): Boolean = withContext(Dispatchers.IO) {
        val count = onMs.size
        val msgId = idGen.getAndIncrement()

        val payload = JSONObject()
            .put("v", 1)
            .put("id", msgId)
            .put("cmd", "SIREN")
            .put("count", count)
            .put("on_ms", org.json.JSONArray(onMs))
            .put("off_ms", org.json.JSONArray(offMs))
            .toString()

        val data = payload.toByteArray(Charsets.UTF_8)

        DatagramSocket().use { socket ->
            socket.soTimeout = CMD_ACK_TIMEOUT_MS

            for (attempt in 0 until RETRIES) {
                val attemptNo = attempt + 1
                val sendStart = System.currentTimeMillis()
                try {
                    logLine("UDP SIREN send id=$msgId attempt=$attemptNo ep=${ep.host.hostAddress}:${ep.port} payload_len=${data.size}")

                    val out = DatagramPacket(data, data.size, ep.host, ep.port)
                    socket.send(out)

                    val buf = ByteArray(256)
                    val inp = DatagramPacket(buf, buf.size)
                    socket.receive(inp)

                    val rtt = System.currentTimeMillis() - sendStart
                    val resp = String(inp.data, 0, inp.length, Charsets.UTF_8)
                    val json = JSONObject(resp)
                    val ack = json.optInt("ack", -1)
                    val ok = json.optInt("ok", 0) == 1

                    if (ack == msgId && ok) {
                        logLine("UDP SIREN ack OK id=$msgId rtt=${rtt}ms")
                        return@withContext true
                    }
                    if (ack == msgId && !ok) {
                        val err = json.optString("err", "ERR")
                        _status.value = "ESP: ошибка $err"
                        logLine("UDP SIREN ack FAIL id=$msgId rtt=${rtt}ms err=$err")
                        return@withContext false
                    }

                    logLine("UDP SIREN ack OTHER id=$msgId got_ack=$ack rtt=${rtt}ms")
                } catch (_: java.net.SocketTimeoutException) {
                    val rtt = System.currentTimeMillis() - sendStart
                    logLine("UDP SIREN timeout id=$msgId attempt=$attemptNo rtt=${rtt}ms")
                } catch (t: Throwable) {
                    _status.value = "ESP: UDP ошибка (${t.javaClass.simpleName})"
                    logLine("UDP SIREN EX ${t.javaClass.simpleName} id=$msgId attempt=$attemptNo")
                    break
                }

                if (attempt < RETRIES - 1) delay(RETRY_DELAY_MS)
            }
        }

        _status.value = "ESP: нет ACK (переобнаружение)"
        _endpoint.value = null
        withContext(Dispatchers.Main) { startDiscovery() }
        logLine("UDP SIREN no-ack -> rediscovery")
        false
    }

    private suspend fun sendOnceWithRetries(ep: EspEndpoint, btn: String): Boolean =
        withContext(Dispatchers.IO) {
            val msgId = idGen.getAndIncrement()
            val payload = JSONObject()
                .put("v", 1)
                .put("id", msgId)
                .put("btn", btn)
                .put("evt", "PRESS")
                .toString()

            val data = payload.toByteArray(Charsets.UTF_8)

            DatagramSocket().use { socket ->
                socket.soTimeout = CMD_ACK_TIMEOUT_MS

                for (attempt in 0 until RETRIES) {
                    val attemptNo = attempt + 1
                    val sendStart = System.currentTimeMillis()
                    try {
                        logLine("UDP PRESS send btn=$btn id=$msgId attempt=$attemptNo ep=${ep.host.hostAddress}:${ep.port}")

                        val out = DatagramPacket(data, data.size, ep.host, ep.port)
                        socket.send(out)

                        val buf = ByteArray(256)
                        val inp = DatagramPacket(buf, buf.size)
                        socket.receive(inp)

                        val rtt = System.currentTimeMillis() - sendStart
                        val resp = String(inp.data, 0, inp.length, Charsets.UTF_8)
                        val json = JSONObject(resp)
                        val ack = json.optInt("ack", -1)
                        val ok = json.optInt("ok", 0) == 1

                        if (ack == msgId && ok) {
                            logLine("UDP PRESS ack OK btn=$btn id=$msgId rtt=${rtt}ms")
                            return@withContext true
                        }
                        if (ack == msgId && !ok) {
                            val err = json.optString("err", "ERR")
                            _status.value = "ESP: ошибка $err"
                            logLine("UDP PRESS ack FAIL btn=$btn id=$msgId rtt=${rtt}ms err=$err")
                            return@withContext false
                        }

                        logLine("UDP PRESS ack OTHER btn=$btn id=$msgId got_ack=$ack rtt=${rtt}ms")
                    } catch (_: java.net.SocketTimeoutException) {
                        val rtt = System.currentTimeMillis() - sendStart
                        logLine("UDP PRESS timeout btn=$btn id=$msgId attempt=$attemptNo rtt=${rtt}ms")
                    } catch (t: Throwable) {
                        _status.value = "ESP: UDP ошибка (${t.javaClass.simpleName})"
                        logLine("UDP PRESS EX ${t.javaClass.simpleName} btn=$btn id=$msgId attempt=$attemptNo")
                        break
                    }

                    if (attempt < RETRIES - 1) delay(RETRY_DELAY_MS)
                }
            }

            _status.value = "ESP: нет ACK (переобнаружение)"
            _endpoint.value = null
            withContext(Dispatchers.Main) { startDiscovery() }
            logLine("UDP PRESS no-ack btn=$btn id=$msgId -> rediscovery")
            false
        }
}
