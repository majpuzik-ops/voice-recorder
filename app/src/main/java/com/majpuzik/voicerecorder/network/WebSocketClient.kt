package com.majpuzik.voicerecorder.network

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.majpuzik.voicerecorder.data.ServerMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

class WebSocketClient {

    companion object {
        private const val TAG = "WebSocketClient"
    }

    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)  // No timeout for WebSocket
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _messages = MutableSharedFlow<ServerMessage>()
    val messages: SharedFlow<ServerMessage> = _messages

    private var serverUrl: String = ""
    private var userId: String = ""
    private var recordingId: String = ""

    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED, ERROR
    }

    fun connect(url: String, userId: String, recordingId: String) {
        this.serverUrl = url
        this.userId = userId
        this.recordingId = recordingId

        _connectionState.value = ConnectionState.CONNECTING

        val request = Request.Builder()
            .url("$url?user_id=$userId&recording_id=$recordingId")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
                _connectionState.value = ConnectionState.CONNECTED

                // Send initial config
                val config = mapOf(
                    "type" to "config",
                    "user_id" to userId,
                    "recording_id" to recordingId,
                    "timestamp" to System.currentTimeMillis()
                )
                webSocket.send(gson.toJson(config))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
                try {
                    val message = gson.fromJson(text, ServerMessage::class.java)
                    scope.launch {
                        _messages.emit(message)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing message: ${e.message}")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Binary message - usually audio feedback, ignore
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closing: $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket closed: $reason")
                _connectionState.value = ConnectionState.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                _connectionState.value = ConnectionState.ERROR
                scope.launch {
                    _messages.emit(ServerMessage("error", error = t.message))
                }
            }
        })
    }

    fun sendAudioChunk(audioData: ByteArray) {
        webSocket?.let { ws ->
            if (_connectionState.value == ConnectionState.CONNECTED) {
                // Send as base64 encoded JSON
                val message = mapOf(
                    "type" to "audio",
                    "data" to Base64.encodeToString(audioData, Base64.NO_WRAP),
                    "recording_id" to recordingId,
                    "timestamp" to System.currentTimeMillis()
                )
                ws.send(gson.toJson(message))
            }
        }
    }

    fun sendCommand(command: String, data: Map<String, Any> = emptyMap()) {
        webSocket?.let { ws ->
            val message = mapOf(
                "type" to command,
                "recording_id" to recordingId,
                "user_id" to userId
            ) + data
            ws.send(gson.toJson(message))
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED
}
