package com.example.hockeyscoreboard.esp

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import android.net.wifi.WifiManager


data class EspEndpoint(val host: InetAddress, val port: Int)

class EspTabloController(
    appContext: Context
) {
    companion object {
        private const val SERVICE_TYPE = "_hockeytablo._udp."
        private const val DEFAULT_PORT = 4210
        private const val ACK_TIMEOUT_MS = 250
        private const val RETRIES = 3
        private const val RETRY_DELAY_MS = 120L
    }

    private val nsdManager: NsdManager =
        appContext.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val wifiManager: WifiManager =
        appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null


    private val _endpoint = MutableStateFlow<EspEndpoint?>(null)
    val endpoint: StateFlow<EspEndpoint?> = _endpoint

    private val _status = MutableStateFlow("ESP: не найдена")
    val status: StateFlow<String> = _status

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var resolveInProgress = false

    // Очередь команд: строго 1 активная команда
    private val sendMutex = Mutex()
    private val idGen = AtomicInteger(1)

    fun startDiscovery() {
        if (discoveryListener != null) return

        _status.value = "ESP: поиск…"

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
                if (resolveInProgress) return
                resolveInProgress = true

                nsdManager.resolveService(
                    serviceInfo,
                    object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            resolveInProgress = false
                            _status.value = "ESP: resolve ошибка ($errorCode)"
                        }

                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            resolveInProgress = false
                            val host = resolved.host ?: run {
                                _status.value = "ESP: resolve без host"
                                return
                            }
                            val port = if (resolved.port > 0) resolved.port else DEFAULT_PORT
                            _endpoint.value = EspEndpoint(host, port)
                            _status.value = "ESP: найдена ${host.hostAddress}:$port"
                        }
                    }
                )
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                _endpoint.value = null
                _status.value = "ESP: потеряна, поиск…"
            }

            override fun onDiscoveryStopped(serviceType: String) {
                _status.value = "ESP: поиск остановлен"
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _status.value = "ESP: старт поиска ошибка ($errorCode)"
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                _status.value = "ESP: stop ошибка ($errorCode)"
                stopDiscovery()
            }
        }

        discoveryListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (t: Throwable) {
            _status.value = "ESP: discovery недоступен (${t.javaClass.simpleName})"
            discoveryListener = null
        }
    }

    fun stopDiscovery() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
        resolveInProgress = false
        multicastLock?.let {
            runCatching { if (it.isHeld) it.release() }
        }
        multicastLock = null

    }

    suspend fun press(btn: String): Boolean = sendPresses(btn, 1)

    /**
     * Переводит физическое табло из режима часов в режим хоккейного табло.
     *
     * По ТЗ это тройное нажатие кнопки «выход».
     */
    suspend fun switchToScoreboardMode(): Boolean = sendPresses("выход", 3)

    suspend fun sendPresses(btn: String, count: Int): Boolean {
        if (count <= 0) return true

        return sendMutex.withLock {
            val ep = endpoint.value
            if (ep == null) {
                _status.value = "ESP: не найдена (повторный поиск)"
                startDiscovery()
                return@withLock false
            }

            var okAll = true
            for (i in 0 until count) {
                val ok = sendOnceWithRetries(ep, btn)
                okAll = okAll && ok
                if (!ok) break
            }
            okAll
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
            val ep = endpoint.value
            if (ep == null) {
                _status.value = "ESP: не найдена (повторный поиск)"
                startDiscovery()
                return@withLock false
            }

            sendSirenOnceWithRetries(ep, onMs, offMs)
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
            socket.soTimeout = ACK_TIMEOUT_MS

            for (attempt in 0 until RETRIES) {
                try {
                    val out = DatagramPacket(data, data.size, ep.host, ep.port)
                    socket.send(out)

                    val buf = ByteArray(256)
                    val inp = DatagramPacket(buf, buf.size)
                    socket.receive(inp)

                    val resp = String(inp.data, 0, inp.length, Charsets.UTF_8)
                    val json = JSONObject(resp)
                    val ack = json.optInt("ack", -1)
                    val ok = json.optInt("ok", 0) == 1

                    if (ack == msgId && ok) return@withContext true
                    if (ack == msgId && !ok) {
                        val err = json.optString("err", "ERR")
                        _status.value = "ESP: ошибка $err"
                        return@withContext false
                    }
                    // иначе: это не наш ACK — идём на ретрай
                } catch (_: java.net.SocketTimeoutException) {
                    // timeout -> ретрай
                } catch (t: Throwable) {
                    _status.value = "ESP: UDP ошибка (${t.javaClass.simpleName})"
                    break
                }

                if (attempt < RETRIES - 1) delay(RETRY_DELAY_MS)
            }
        }

        _status.value = "ESP: нет ACK (переобнаружение)"
        _endpoint.value = null
        startDiscovery()
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
                socket.soTimeout = ACK_TIMEOUT_MS

                for (attempt in 0 until RETRIES) {
                    try {
                        val out = DatagramPacket(data, data.size, ep.host, ep.port)
                        socket.send(out)

                        val buf = ByteArray(256)
                        val inp = DatagramPacket(buf, buf.size)
                        socket.receive(inp)

                        val resp = String(inp.data, 0, inp.length, Charsets.UTF_8)
                        val json = JSONObject(resp)
                        val ack = json.optInt("ack", -1)
                        val ok = json.optInt("ok", 0) == 1

                        if (ack == msgId && ok) return@withContext true
                        if (ack == msgId && !ok) {
                            val err = json.optString("err", "ERR")
                            _status.value = "ESP: ошибка $err"
                            return@withContext false
                        }
                        // иначе: это не наш ACK — идём на ретрай
                    } catch (_: java.net.SocketTimeoutException) {
                        // timeout -> ретрай
                    } catch (t: Throwable) {
                        _status.value = "ESP: UDP ошибка (${t.javaClass.simpleName})"
                        break
                    }

                    if (attempt < RETRIES - 1) delay(RETRY_DELAY_MS)
                }
            }

            _status.value = "ESP: нет ACK (переобнаружение)"
            _endpoint.value = null
            startDiscovery()
            false
        }
}
