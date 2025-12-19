package com.majpuzik.voicerecorder.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.majpuzik.voicerecorder.MainActivity
import com.majpuzik.voicerecorder.R
import com.majpuzik.voicerecorder.data.AppSettings
import com.majpuzik.voicerecorder.data.Recording
import com.majpuzik.voicerecorder.network.WebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "recording_channel"

        const val ACTION_START = "start_recording"
        const val ACTION_STOP = "stop_recording"
        const val ACTION_PAUSE = "pause_recording"
        const val EXTRA_RECORDING_ID = "recording_id"

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    enum class RecordingState {
        IDLE, RECORDING, PAUSED
    }

    private val binder = LocalBinder()
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState

    private val _recordingTime = MutableStateFlow(0L)
    val recordingTime: StateFlow<Long> = _recordingTime

    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude

    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription

    private val _translation = MutableStateFlow("")
    val translation: StateFlow<String> = _translation

    private val _connectionState = MutableStateFlow("disconnected")
    val connectionState: StateFlow<String> = _connectionState

    private val _ttsAudio = MutableStateFlow<String?>(null)
    val ttsAudio: StateFlow<String?> = _ttsAudio

    private lateinit var settings: AppSettings
    private lateinit var webSocketClient: WebSocketClient

    private var currentRecordingId: String = ""
    private var audioFile: File? = null
    private var audioOutputStream: FileOutputStream? = null
    private var startTime: Long = 0
    private var pausedTime: Long = 0
    private var totalPausedDuration: Long = 0
    private val audioBuffer = mutableListOf<ByteArray>()

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        webSocketClient = WebSocketClient()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentRecordingId = intent.getStringExtra(EXTRA_RECORDING_ID) ?: return START_NOT_STICKY
                startRecording()
            }
            ACTION_STOP -> stopRecording()
            ACTION_PAUSE -> togglePause()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nahravani",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikace behem nahravani"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nahravani...")
            .setContentText("Klepnete pro otevreni")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    fun startRecording() {
        if (_recordingState.value != RecordingState.IDLE) return

        // Prepare audio file
        val recordingsDir = File(filesDir, "recordings")
        recordingsDir.mkdirs()
        audioFile = File(recordingsDir, "$currentRecordingId.wav")

        // Connect to server with LLM settings
        webSocketClient.connect(
            settings.serverUrl,
            settings.userId,
            currentRecordingId,
            settings.targetLanguage,
            settings.llmProvider,
            settings.llmApiKey
        )

        // Observe WebSocket messages
        scope.launch {
            webSocketClient.connectionState.collect { state ->
                _connectionState.value = when (state) {
                    WebSocketClient.ConnectionState.CONNECTED -> "connected"
                    WebSocketClient.ConnectionState.CONNECTING -> "connecting"
                    WebSocketClient.ConnectionState.ERROR -> "error"
                    else -> "disconnected"
                }
            }
        }

        scope.launch {
            webSocketClient.messages.collect { message ->
                when (message.type) {
                    "transcription" -> {
                        val text = message.data as? String ?: ""
                        _transcription.value = text
                        Log.d(TAG, "Transcription received: $text")
                    }
                    "translation" -> {
                        val text = message.data as? String ?: ""
                        _translation.value = text
                        Log.d(TAG, "Translation received: $text")
                    }
                    "tts_audio" -> {
                        val audioData = message.data as? String ?: ""
                        if (audioData.isNotEmpty()) {
                            _ttsAudio.value = audioData
                            Log.d(TAG, "TTS audio received: ${audioData.length} chars")
                        }
                    }
                }
            }
        }

        // Start foreground
        startForeground(NOTIFICATION_ID, createNotification())

        // Initialize AudioRecord with selected audio source
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val audioSource = settings.getAudioSourceInt()
        audioRecord = AudioRecord(
            audioSource,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "Failed to initialize AudioRecord")
            return
        }

        audioRecord?.startRecording()
        _recordingState.value = RecordingState.RECORDING
        startTime = System.currentTimeMillis()
        totalPausedDuration = 0
        audioBuffer.clear()

        // Start recording loop
        recordingJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            var timerJob: Job? = null

            // Timer coroutine
            timerJob = launch {
                while (isActive) {
                    if (_recordingState.value == RecordingState.RECORDING) {
                        _recordingTime.value = System.currentTimeMillis() - startTime - totalPausedDuration
                    }
                    delay(100)
                }
            }

            // Recording loop
            while (isActive && _recordingState.value != RecordingState.IDLE) {
                if (_recordingState.value == RecordingState.RECORDING) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        val audioData = buffer.copyOf(bytesRead)
                        audioBuffer.add(audioData)

                        // Calculate amplitude for visualization
                        var sum = 0L
                        for (i in 0 until bytesRead step 2) {
                            if (i + 1 < bytesRead) {
                                val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                                sum += kotlin.math.abs(sample.toShort().toInt())
                            }
                        }
                        val amplitude = (sum / (bytesRead / 2)).toFloat() / Short.MAX_VALUE
                        _audioAmplitude.value = amplitude

                        // Stream to server
                        if (webSocketClient.isConnected()) {
                            webSocketClient.sendAudioChunk(audioData)
                        }
                    }
                } else {
                    _audioAmplitude.value = 0f
                    delay(100)
                }
            }

            timerJob.cancel()
        }
    }

    fun togglePause() {
        when (_recordingState.value) {
            RecordingState.RECORDING -> {
                pausedTime = System.currentTimeMillis()
                _recordingState.value = RecordingState.PAUSED
                audioRecord?.stop()
                webSocketClient.sendCommand("pause")
            }
            RecordingState.PAUSED -> {
                totalPausedDuration += System.currentTimeMillis() - pausedTime
                _recordingState.value = RecordingState.RECORDING
                audioRecord?.startRecording()
                webSocketClient.sendCommand("resume")
            }
            else -> {}
        }
    }

    fun stopRecording() {
        _recordingState.value = RecordingState.IDLE

        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        // Tell server to save recording
        webSocketClient.sendCommand("end_recording", mapOf(
            "name" to "Nahravka ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
        ))

        // Save audio to WAV file
        saveToWavFile()

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun saveToWavFile() {
        audioFile?.let { file ->
            try {
                val totalSize = audioBuffer.sumOf { it.size }
                val wavFile = RandomAccessFile(file, "rw")

                // Write WAV header
                wavFile.writeBytes("RIFF")
                wavFile.writeInt(Integer.reverseBytes(36 + totalSize))
                wavFile.writeBytes("WAVE")
                wavFile.writeBytes("fmt ")
                wavFile.writeInt(Integer.reverseBytes(16)) // Subchunk1Size
                wavFile.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // AudioFormat (PCM)
                wavFile.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // NumChannels
                wavFile.writeInt(Integer.reverseBytes(SAMPLE_RATE)) // SampleRate
                wavFile.writeInt(Integer.reverseBytes(SAMPLE_RATE * 2)) // ByteRate
                wavFile.writeShort(java.lang.Short.reverseBytes(2.toShort()).toInt()) // BlockAlign
                wavFile.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt()) // BitsPerSample
                wavFile.writeBytes("data")
                wavFile.writeInt(Integer.reverseBytes(totalSize))

                // Write audio data
                for (chunk in audioBuffer) {
                    wavFile.write(chunk)
                }

                wavFile.close()
                Log.d(TAG, "Audio saved to ${file.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving audio: ${e.message}")
            }
        }
    }

    fun requestTTS(text: String) {
        if (webSocketClient.isConnected() && text.isNotEmpty()) {
            val voice = if (settings.targetLanguage == "cs") "cs" else settings.targetLanguage
            webSocketClient.requestTTS(text, voice)
            Log.d(TAG, "TTS requested for: ${text.take(50)}...")
        }
    }

    fun clearTTSAudio() {
        _ttsAudio.value = null
    }

    fun saveRecording(name: String, originalText: String, translatedText: String) {
        audioFile?.let { file ->
            val recording = Recording(
                id = currentRecordingId,
                name = name,
                filePath = file.absolutePath,
                duration = _recordingTime.value,
                originalText = originalText,
                translatedText = translatedText,
                targetLanguage = settings.targetLanguage,
                userId = settings.userId
            )

            // Save to shared preferences (simple storage)
            val prefs = getSharedPreferences("recordings", MODE_PRIVATE)
            val recordings = prefs.getStringSet("recording_ids", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
            recordings.add(currentRecordingId)
            prefs.edit()
                .putStringSet("recording_ids", recordings)
                .putString("${currentRecordingId}_name", name)
                .putString("${currentRecordingId}_path", file.absolutePath)
                .putLong("${currentRecordingId}_duration", _recordingTime.value)
                .putLong("${currentRecordingId}_timestamp", System.currentTimeMillis())
                .putString("${currentRecordingId}_original", originalText)
                .putString("${currentRecordingId}_translated", translatedText)
                .putString("${currentRecordingId}_language", settings.targetLanguage)
                .apply()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recordingJob?.cancel()
        audioRecord?.release()
        webSocketClient.disconnect()
        scope.cancel()
    }
}
