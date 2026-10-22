package com.hetcuu.hcview.model

import android.graphics.Bitmap
import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ConnectionStatus {
    STOPPED,
    STARTING,
    LISTENING,
    CONNECTING,
    CONNECTED,
    PAIRED,
    ERROR
}

object AppState {
    private val _connectionStatus = MutableStateFlow(ConnectionStatus.STOPPED)
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _statusMessage = MutableStateFlow("Đã dừng")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _clientCount = MutableStateFlow(0)
    val clientCount: StateFlow<Int> = _clientCount.asStateFlow()

    private val _connectedClients = MutableStateFlow<List<String>>(emptyList())
    val connectedClients: StateFlow<List<String>> = _connectedClients.asStateFlow()

    private val _activeRegion = MutableStateFlow(Rect(0, 0, 1080, 350))
    val activeRegion: StateFlow<Rect> = _activeRegion.asStateFlow()

    private val _lcdPreviewBitmap = MutableStateFlow<Bitmap?>(null)
    val lcdPreviewBitmap: StateFlow<Bitmap?> = _lcdPreviewBitmap.asStateFlow()

    private val _framesSent = MutableStateFlow(0L)
    val framesSent: StateFlow<Long> = _framesSent.asStateFlow()

    private val _bytesSent = MutableStateFlow(0L)
    val bytesSent: StateFlow<Long> = _bytesSent.asStateFlow()

    private val _actualFps = MutableStateFlow(0f)
    val actualFps: StateFlow<Float> = _actualFps.asStateFlow()

    private val _scrollSpeed = MutableStateFlow(1.0f)
    val scrollSpeed: StateFlow<Float> = _scrollSpeed.asStateFlow()

    private val _isA11yRunning = MutableStateFlow(false)
    val isA11yRunning: StateFlow<Boolean> = _isA11yRunning.asStateFlow()

    var onImmediateFrameRequested: (() -> Unit)? = null

    fun updateA11yRunning(running: Boolean) {
        _isA11yRunning.value = running
    }

    fun updateStatus(status: ConnectionStatus, message: String) {
        _connectionStatus.value = status
        _statusMessage.value = message
    }

    fun updateClientCount(count: Int) {
        _clientCount.value = count
    }

    fun updateConnectedClients(clients: List<String>) {
        _connectedClients.value = clients
    }

    fun updateActiveRegion(newRegion: Rect) {
        _activeRegion.value = newRegion
    }

    fun updateLcdPreview(bitmap: Bitmap?) {
        _lcdPreviewBitmap.value = bitmap
    }

    fun recordFrameSent(bytesCount: Int) {
        _framesSent.value += 1
        _bytesSent.value += bytesCount
    }

    fun updateActualFps(fps: Float) {
        _actualFps.value = fps
    }

    fun updateScrollSpeed(speed: Float) {
        _scrollSpeed.value = speed
    }

    fun requestImmediateFrame() {
        onImmediateFrameRequested?.invoke()
    }

    fun resetStats() {
        _framesSent.value = 0L
        _bytesSent.value = 0L
        _actualFps.value = 0f
        _clientCount.value = 0
        _connectedClients.value = emptyList()
    }
}
