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

        // Server fallback list - MacBook primary
        val SERVERS = listOf(
            "ws://100.90.154.98:8765",   // MacBook Pro - primary
            "ws://100.96.204.120:8765"   // DGX - backup
        )
    }

    private var webSocket: WebSocket? = null
    private var currentServerIndex = 0
    private var connectAttempts = 0
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

    private var sourceLanguage: String = "cs"
    private var targetLanguage: String = "en"
    private var llmProvider: String = "ollama"
    private var llmApiKey: String = ""
    private var transcriptionProvider: String = "local"
    private var transcriptionApiKey: String = ""

    fun connect(
        url: String,
        userId: String,
        recordingId: String,
        sourceLang: String = "cs",
        targetLang: String = "en",
        provider: String = "ollama",
        apiKey: String = "",
        sttProvider: String = "local",
        sttApiKey: String = ""
    ) {
        this.serverUrl = url
        this.userId = userId
        this.recordingId = recordingId
        this.sourceLanguage = sourceLang
        this.targetLanguage = targetLang
        this.llmProvider = provider
        this.llmApiKey = apiKey
        this.transcriptionProvider = sttProvider
        this.transcriptionApiKey = sttApiKey

        // Reset and try primary server first
        currentServerIndex = 0
        connectAttempts = 0
        connectToServer()
    }

    private fun connectToServer() {
        if (currentServerIndex >= SERVERS.size) {
            Log.e(TAG, "All servers failed")
            _connectionState.value = ConnectionState.ERROR
            scope.launch {
                _messages.emit(ServerMessage("error", error = "Vsechny servery nedostupne"))
            }
            return
        }

        val serverToTry = SERVERS[currentServerIndex]
        Log.d(TAG, "Connecting to server $currentServerIndex: $serverToTry")
        _connectionState.value = ConnectionState.CONNECTING

        val request = Request.Builder()
            .url("$serverToTry?user_id=$userId&recording_id=$recordingId")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected to $serverToTry")
                serverUrl = serverToTry
                connectAttempts = 0
                _connectionState.value = ConnectionState.CONNECTED

                // Send initial config with source/target language and LLM settings
                val config = mapOf(
                    "type" to "config",
                    "user_id" to userId,
                    "recording_id" to recordingId,
                    "source_language" to sourceLanguage,
                    "target_language" to targetLanguage,
                    "llm_provider" to llmProvider,
                    "llm_api_key" to llmApiKey,
                    "transcription_provider" to transcriptionProvider,
                    "transcription_api_key" to transcriptionApiKey,
                    "timestamp" to System.currentTimeMillis()
                )
                Log.d(TAG, "Sending config: source=$sourceLanguage, target=$targetLanguage")
                webSocket.send(gson.toJson(config))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "=== RAW WS MESSAGE: $text ===")
                try {
                    val message = gson.fromJson(text, ServerMessage::class.java)
                    Log.d(TAG, "=== PARSED: type=${message.type}, data=${message.data} ===")
                    scope.launch {
                        _messages.emit(message)
                        Log.d(TAG, "=== EMITTED TO FLOW ===")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "=== PARSE ERROR: ${e.message} ===")
                    e.printStackTrace()
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
                Log.e(TAG, "WebSocket error on ${SERVERS[currentServerIndex]}: ${t.message}")

                // Try next server
                currentServerIndex++
                if (currentServerIndex < SERVERS.size) {
                    Log.d(TAG, "Trying fallback server...")
                    scope.launch {
                        _messages.emit(ServerMessage("info", data = "Zkousim backup server..."))
                    }
                    connectToServer()
                } else {
                    _connectionState.value = ConnectionState.ERROR
                    scope.launch {
                        _messages.emit(ServerMessage("error", error = "Vsechny servery nedostupne: ${t.message}"))
                    }
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

    fun requestTTS(text: String, voice: String = "cs") {
        webSocket?.let { ws ->
            if (_connectionState.value == ConnectionState.CONNECTED) {
                val message = mapOf(
                    "type" to "tts",
                    "text" to text,
                    "voice" to voice,
                    "recording_id" to recordingId
                )
                Log.d(TAG, "Requesting TTS for: ${text.take(50)}...")
                ws.send(gson.toJson(message))
            }
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    fun isConnected(): Boolean = _connectionState.value == ConnectionState.CONNECTED
}
