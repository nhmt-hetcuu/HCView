package com.hetcuu.hcview.network

import android.util.Log
import com.hetcuu.hcview.model.AppState
import com.hetcuu.hcview.model.ConnectionStatus
import com.hetcuu.hcview.model.RemoteConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class RelayClient(
    private val config: RemoteConfig,
    private val onCommand: (String) -> Unit,
    private val currentFrame: () -> ByteArray?
) {
    companion object {
        private const val TAG = "RelayClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        data class DeviceItem(
            val id: String,
            val name: String
        )

        suspend fun login(baseUrl: String, user: String, pass: String): Result<String> = withContext(Dispatchers.IO) {
            runCatching {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                val jsonBody = JSONObject().apply {
                    put("username", user)
                    put("password", pass)
                }.toString()

                val baseClean = baseUrl.trimEnd('/')
                val urls = listOf(
                    "$baseClean/api/v1/auth/login",
                    "$baseClean/auth/login"
                )

                var lastEx: Exception? = null
                for (url in urls) {
                    try {
                        val req = Request.Builder()
                            .url(url)
                            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                            .build()
                        val resp = client.newCall(req).execute()
                        val body = resp.body?.string() ?: ""
                        if (resp.isSuccessful) {
                            val json = JSONObject(body)
                            val token = json.optString("access_token")
                            if (token.isNotEmpty()) return@withContext Result.success(token)
                        } else {
                            lastEx = Exception("HTTP ${resp.code}: $body")
                        }
                    } catch (e: Exception) {
                        lastEx = e
                    }
                }
                throw lastEx ?: Exception("Login failed")
            }
        }

        suspend fun fetchDevices(baseUrl: String, token: String): Result<List<DeviceItem>> = withContext(Dispatchers.IO) {
            runCatching {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()

                val baseClean = baseUrl.trimEnd('/')
                val urls = listOf(
                    "$baseClean/api/v1/devices",
                    "$baseClean/devices"
                )

                var lastEx: Exception? = null
                for (url in urls) {
                    try {
                        val req = Request.Builder()
                            .url(url)
                            .header("Authorization", "Bearer $token")
                            .get()
                            .build()
                        val resp = client.newCall(req).execute()
                        val body = resp.body?.string() ?: ""
                        if (resp.isSuccessful) {
                            val list = mutableListOf<DeviceItem>()
                            val array = JSONArray(body)
                            for (i in 0 until array.length()) {
                                val obj = array.getJSONObject(i)
                                val id = obj.optString("device_id", obj.optString("id"))
                                val name = obj.optString("name", id)
                                if (id.isNotEmpty()) list.add(DeviceItem(id, name))
                            }
                            return@withContext Result.success(list)
                        } else {
                            lastEx = Exception("HTTP ${resp.code}: $body")
                        }
                    } catch (e: Exception) {
                        lastEx = e
                    }
                }
                throw lastEx ?: Exception("Fetch devices failed")
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(0, TimeUnit.SECONDS) // We implement application ping {"type":"ping"} per spec
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var webSocket: WebSocket? = null
    private var isRunning = false
    private var isPaired = false
    private var shouldReconnect = true
    private var backoffSeconds = 1.0
    private var pingJob: Job? = null

    fun start() {
        isRunning = true
        shouldReconnect = true
        backoffSeconds = 1.0
        connect()
    }

    fun stop() {
        isRunning = false
        shouldReconnect = false
        pingJob?.cancel()
        webSocket?.close(1000, "User stopped")
        webSocket = null
        isPaired = false
        AppState.updateConnectedClients(emptyList())
        AppState.updateStatus(ConnectionStatus.STOPPED, "Đã dừng")
    }

    private fun connect() {
        if (!isRunning) return

        AppState.updateStatus(ConnectionStatus.CONNECTING, "Đang kết nối tới Relay Server…")

        var base = config.relayServerUrl.trim()
        if (base.startsWith("http://")) {
            base = "ws://" + base.substring("http://".length)
        } else if (base.startsWith("https://")) {
            base = "wss://" + base.substring("https://".length)
        } else if (!base.startsWith("ws://") && !base.startsWith("wss://")) {
            base = "ws://$base"
        }
        base = base.trimEnd('/')

        val deviceIdEnc = URLEncoder.encode(config.relayDeviceId, "UTF-8")
        val tokenEnc = URLEncoder.encode(config.relayToken, "UTF-8")
        val wsUrl = "$base/ws/remote?device_id=$deviceIdEnc&token=$tokenEnc&vai_tro=pc"

        Log.i(TAG, "Connecting to Relay: $wsUrl")

        val req = Request.Builder().url(wsUrl).build()
        webSocket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket opened with Relay Server")
                backoffSeconds = 1.0
                AppState.updateStatus(ConnectionStatus.CONNECTED, "Đã vào Server Relay. Đang chờ thiết bị…")
                startPingJob()
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val s = text.trim()
                if (s.isEmpty()) return

                if (s.startsWith("{")) {
                    handleJson(s)
                    return
                }

                // Lệnh từ thiết bị được relay chuyển tiếp nguyên văn
                onCommand(s)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed: $code / $reason")
                isPaired = false
                pingJob?.cancel()
                if (shouldReconnect) scheduleReconnect()
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "WebSocket failure: ${t.message}, HTTP code: ${response?.code}")
                isPaired = false
                pingJob?.cancel()

                val code = response?.code ?: 0
                if (code == 403 || code == 4401) {
                    AppState.updateStatus(ConnectionStatus.ERROR, "Từ chối xác thực (HTTP $code). Kiểm tra lại Token/Tài khoản")
                    shouldReconnect = false
                    return
                }

                if (shouldReconnect) {
                    scheduleReconnect()
                }
            }
        })
    }

    private fun handleJson(jsonStr: String) {
        runCatching {
            val json = JSONObject(jsonStr)
            when (val type = json.optString("type")) {
                "cho_ban_kia" -> {
                    isPaired = false
                    AppState.updateStatus(ConnectionStatus.CONNECTED, "Chờ thiết bị kết nối vào Server…")
                    AppState.updateClientCount(0)
                    AppState.updateConnectedClients(emptyList())
                }
                "da_ghep_cap" -> {
                    isPaired = true
                    val clientIp = json.optString("client_ip", "").ifEmpty {
                        config.relayDeviceId.ifEmpty { "ESP32" }
                    }
                    AppState.updateStatus(ConnectionStatus.PAIRED, "Đã ghép cặp với thiết bị ($clientIp)!")
                    AppState.updateClientCount(1)
                    AppState.updateConnectedClients(listOf(clientIp))
                    // Gửi ngay khung ảnh hiện tại
                    val frame = currentFrame()
                    if (frame != null) {
                        sendFrame(frame)
                    }
                }
                "ban_kia_ngat" -> {
                    isPaired = false
                    AppState.updateStatus(ConnectionStatus.CONNECTED, "Thiết bị vừa ngắt. Chờ thiết bị nối lại…")
                    AppState.updateClientCount(0)
                    AppState.updateConnectedClients(emptyList())
                }
                "bi_thay_the" -> {
                    isPaired = false
                    shouldReconnect = false
                    AppState.updateStatus(ConnectionStatus.ERROR, "Bị phiên PC khác chiếm chỗ (bi_thay_the). Đã dừng.")
                    stop()
                }
                "error" -> {
                    val code = json.optString("code")
                    val msg = json.optString("message", code)
                    Log.e(TAG, "Relay error: $code - $msg")
                    if (code == "device_disabled") {
                        shouldReconnect = false
                        AppState.updateStatus(ConnectionStatus.ERROR, "Thiết bị đã bị vô hiệu hoá.")
                        stop()
                    } else {
                        AppState.updateStatus(ConnectionStatus.ERROR, "Lỗi server: $msg")
                    }
                }
                "pong" -> {
                    // Ignore keepalive pong
                }
                else -> {
                    Log.d(TAG, "Unknown JSON type from relay: $type")
                }
            }
        }.onFailure {
            Log.w(TAG, "Error parsing JSON: $jsonStr", it)
        }
    }

    private fun startPingJob() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive && isRunning) {
                delay(25_000)
                try {
                    webSocket?.send("""{"type":"ping"}""")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send ping", e)
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect || !isRunning) return
        AppState.updateStatus(ConnectionStatus.CONNECTING, "Mất kết nối. Thử lại sau ${backoffSeconds.toInt()}s…")
        scope.launch {
            delay((backoffSeconds * 1000).toLong())
            backoffSeconds = (backoffSeconds * 1.5).coerceAtMost(10.0)
            if (isRunning && shouldReconnect) {
                connect()
            }
        }
    }

    fun sendFrame(frame: ByteArray) {
        val ws = webSocket ?: return
        if (!isPaired) return // Chỉ gửi khi đã nhận da_ghep_cap để đỡ tốn 4G

        // Hàng đợi 1 khung: tránh dồn khi mạng chậm
        if (ws.queueSize() > 3000) {
            Log.v(TAG, "Dropping frame: socket queue full (${ws.queueSize()} bytes)")
            return
        }

        val sent = ws.send(frame.toByteString())
        if (sent) {
            AppState.recordFrameSent(frame.size)
        }
    }
}
