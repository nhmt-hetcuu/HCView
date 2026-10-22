package com.hetcuu.hcview.network

import android.util.Log
import com.hetcuu.hcview.model.AppState
import com.hetcuu.hcview.model.ConnectionStatus
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer

class DirectServer(
    port: Int,
    private val onCommand: (String) -> Unit,
    private val currentFrame: () -> ByteArray?
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        private const val TAG = "DirectServer"
    }

    init {
        isReuseAddr = true
    }

    override fun onStart() {
        connectionLostTimeout = 25
        Log.i(TAG, "DirectServer started on port $port")
        AppState.updateStatus(ConnectionStatus.LISTENING, "Đang lắng nghe kết nối (Cổng $port)")
        AppState.updateClientCount(0)
        AppState.updateConnectedClients(emptyList())
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val clientIp = conn.remoteSocketAddress?.address?.hostAddress ?: "Unknown"
        Log.i(TAG, "Client connected from $clientIp")

        // 1. BẮT BUỘC: Gửi ngay {"type":"da_ghep_cap"}
        conn.send("""{"type":"da_ghep_cap"}""")

        // 2. Gửi ngay khung ảnh hiện tại (không chờ nhịp FPS sau)
        val frame = currentFrame()
        if (frame != null) {
            conn.send(frame)
            AppState.recordFrameSent(frame.size)
        }

        val totalClients = connections.size
        AppState.updateClientCount(totalClients)

        val clientIps = connections.mapNotNull { it.remoteSocketAddress?.address?.hostAddress }.distinct()
        AppState.updateConnectedClients(clientIps)

        val ipSuffix = if (clientIps.isNotEmpty()) " (${clientIps.joinToString(", ")})" else ""
        AppState.updateStatus(ConnectionStatus.PAIRED, "Đã ghép cặp với $totalClients thiết bị$ipSuffix")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val s = message.trim()
        if (s.isEmpty()) return

        if (s.startsWith("{")) {
            val type = runCatching { JSONObject(s).optString("type") }.getOrNull()
            if (type == "ping") {
                conn.send("""{"type":"pong"}""")
            }
            return
        }

        // Lệnh điều khiển từ thiết bị (C, P, S, M, T, K)
        onCommand(s)
    }

    override fun onMessage(conn: WebSocket, bytes: ByteBuffer) {
        // Thiết bị không gửi tin nhắn nhị phân
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) {
        val remaining = connections.size - 1
        val actualCount = if (remaining < 0) 0 else remaining
        Log.i(TAG, "Client disconnected. Remaining: $actualCount")
        AppState.updateClientCount(actualCount)

        val clientIps = connections.filter { it != conn }.mapNotNull { it.remoteSocketAddress?.address?.hostAddress }.distinct()
        AppState.updateConnectedClients(clientIps)

        if (actualCount == 0) {
            AppState.updateStatus(ConnectionStatus.LISTENING, "Đang lắng nghe kết nối (Cổng $port)")
        } else {
            val ipSuffix = if (clientIps.isNotEmpty()) " (${clientIps.joinToString(", ")})" else ""
            AppState.updateStatus(ConnectionStatus.PAIRED, "Đã ghép cặp với $actualCount thiết bị$ipSuffix")
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "DirectServer error", ex)
        if (conn == null) {
            AppState.updateStatus(ConnectionStatus.ERROR, "Lỗi server: ${ex.message}")
        }
    }

    fun sendFrame(frame: ByteArray) {
        if (connections.isEmpty()) return
        broadcast(frame)
        AppState.recordFrameSent(frame.size * connections.size)
    }

    fun notifyStopSharing() {
        if (connections.isNotEmpty()) {
            broadcast("""{"type":"ban_kia_ngat"}""")
        }
    }
}
