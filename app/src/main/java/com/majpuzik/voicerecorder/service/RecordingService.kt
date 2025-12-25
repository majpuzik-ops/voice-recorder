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
import com.majpuzik.voicerecorder.data.ServerMessage
import com.majpuzik.voicerecorder.network.WebSocketClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO  // Two mics: left + right
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val NUM_CHANNELS = 2  // Stereo
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

    // Speaker mode detection: SELF = user speaking, EXTERNAL = other person speaking
    enum class SpeakerMode { SELF, EXTERNAL }
    private val _speakerMode = MutableStateFlow(SpeakerMode.SELF)
    val speakerMode: StateFlow<SpeakerMode> = _speakerMode

    // Track amplitude history for speaker detection
    private var leftChannelHistory = mutableListOf<Float>()
    private var rightChannelHistory = mutableListOf<Float>()
    private val HISTORY_SIZE = 10
    private var lastSpeakerSwitch = 0L
    private val SPEAKER_SWITCH_COOLDOWN = 2000L // 2 seconds cooldown

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

        // Reset transcription and translation for new recording
        _transcription.value = ""
        _translation.value = ""

        // Prepare audio file
        val recordingsDir = File(filesDir, "recordings")
        recordingsDir.mkdirs()
        audioFile = File(recordingsDir, "$currentRecordingId.wav")

        // Connect to server with LLM and transcription settings
        webSocketClient.connect(
            settings.serverUrl,
            settings.userId,
            currentRecordingId,
            settings.sourceLanguage,
            settings.targetLanguage,
            settings.llmProvider,
            settings.llmApiKey,
            settings.transcriptionProvider,
            settings.transcriptionApiKey
        )

        // Observe WebSocket messages
        webSocketClient.connectionState.onEach { state ->
            _connectionState.value = when (state) {
                WebSocketClient.ConnectionState.CONNECTED -> "connected"
                WebSocketClient.ConnectionState.CONNECTING -> "connecting"
                WebSocketClient.ConnectionState.ERROR -> "error"
                WebSocketClient.ConnectionState.DISCONNECTED -> "disconnected"
            }
        }.launchIn(scope)

        webSocketClient.messages.onEach { message ->
            when (message.type) {
                "transcription" -> {
                    val text = message.data as? String ?: ""
                    if (text.isNotEmpty()) {
                        _transcription.value = if (_transcription.value.isEmpty()) text
                                               else _transcription.value + " " + text
                    }
                    Log.d(TAG, "Transcription received: $text")
                }
                "translation" -> {
                    val text = message.data as? String ?: ""
                    if (text.isNotEmpty()) {
                        _translation.value = if (_translation.value.isEmpty()) text
                                             else _translation.value + " " + text
                    }
                    Log.d(TAG, "Translation received: $text")

                    // Update cover display with translation if in SELF mode
                    if (_speakerMode.value == SpeakerMode.SELF) {
                        CoverDisplayService.updateTranslation(this@RecordingService, text)
                    }

                    // Broadcast to SplitDisplayActivity
                    sendBroadcast(Intent("com.majpuzik.voicerecorder.SPLIT_UPDATE").apply {
                        putExtra("translation", _translation.value)
                        putExtra("is_recording", true)
                    })

                    // Broadcast to CoverDisplayActivity
                    sendBroadcast(Intent("com.majpuzik.voicerecorder.COVER_UPDATE").apply {
                        putExtra("mode", "translation")
                        putExtra("text", _translation.value)
                    })

                    // Broadcast to FullscreenTranslationActivity
                    sendBroadcast(Intent("com.majpuzik.voicerecorder.FULLSCREEN_UPDATE").apply {
                        putExtra("mode", "update")
                        putExtra("translation", _translation.value)
                        putExtra("original", _transcription.value)
                        putExtra("is_recording", true)
                    })
                }
                "tts_audio" -> {
                    val audioData = message.data as? String ?: ""
                    if (audioData.isNotEmpty()) {
                        _ttsAudio.value = audioData
                        Log.d(TAG, "TTS audio received: ${audioData.length} chars")
                    }
                }
                else -> {
                    Log.d(TAG, "Unknown message type: ${message.type}")
                }
            }
        }.launchIn(scope)

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

                        // Calculate amplitude for visualization (stereo: L-R-L-R interleaved)
                        var sumLeft = 0L
                        var sumRight = 0L
                        var sampleCount = 0
                        // Each stereo sample is 4 bytes: 2 for left, 2 for right
                        for (i in 0 until bytesRead step 4) {
                            if (i + 3 < bytesRead) {
                                val leftSample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                                val rightSample = (buffer[i + 2].toInt() and 0xFF) or (buffer[i + 3].toInt() shl 8)
                                sumLeft += kotlin.math.abs(leftSample.toShort().toInt())
                                sumRight += kotlin.math.abs(rightSample.toShort().toInt())
                                sampleCount++
                            }
                        }
                        // Use max of both channels for visualization
                        val ampLeft = if (sampleCount > 0) (sumLeft / sampleCount).toFloat() / Short.MAX_VALUE else 0f
                        val ampRight = if (sampleCount > 0) (sumRight / sampleCount).toFloat() / Short.MAX_VALUE else 0f
                        _audioAmplitude.value = maxOf(ampLeft, ampRight)

                        // Speaker mode detection based on stereo channel comparison
                        detectSpeakerMode(ampLeft, ampRight)

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
        // First stop audio capture
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        }

        // Now set state to idle (after stopping audio so buffer has all data)
        _recordingState.value = RecordingState.IDLE
        recordingJob?.cancel()

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord: ${e.message}")
        }
        audioRecord = null

        // Tell server to save recording
        webSocketClient.sendCommand("end_recording", mapOf(
            "name" to "Nahravka ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}"
        ))

        // Save audio to WAV file
        Log.d(TAG, "Audio buffer size before save: ${audioBuffer.size} chunks")
        saveToWavFile()

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun saveToWavFile() {
        if (audioFile == null) {
            Log.e(TAG, "audioFile is null, cannot save!")
            return
        }

        val file = audioFile!!

        if (audioBuffer.isEmpty()) {
            Log.e(TAG, "Audio buffer is empty, nothing to save!")
            return
        }

        try {
            val totalSize = audioBuffer.sumOf { it.size }
            Log.d(TAG, "Saving WAV: ${audioBuffer.size} chunks, total size: $totalSize bytes")

            // Ensure parent directory exists
            file.parentFile?.mkdirs()

            val wavFile = RandomAccessFile(file, "rw")

            // Write WAV header (stereo)
            val byteRate = SAMPLE_RATE * NUM_CHANNELS * 2  // sampleRate * numChannels * bytesPerSample
            val blockAlign = NUM_CHANNELS * 2  // numChannels * bytesPerSample

            wavFile.writeBytes("RIFF")
            wavFile.writeInt(Integer.reverseBytes(36 + totalSize))
            wavFile.writeBytes("WAVE")
            wavFile.writeBytes("fmt ")
            wavFile.writeInt(Integer.reverseBytes(16)) // Subchunk1Size
            wavFile.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt()) // AudioFormat (PCM)
            wavFile.writeShort(java.lang.Short.reverseBytes(NUM_CHANNELS.toShort()).toInt()) // NumChannels (stereo)
            wavFile.writeInt(Integer.reverseBytes(SAMPLE_RATE)) // SampleRate
            wavFile.writeInt(Integer.reverseBytes(byteRate)) // ByteRate
            wavFile.writeShort(java.lang.Short.reverseBytes(blockAlign.toShort()).toInt()) // BlockAlign
            wavFile.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt()) // BitsPerSample
            wavFile.writeBytes("data")
            wavFile.writeInt(Integer.reverseBytes(totalSize))

            // Write audio data
            for (chunk in audioBuffer) {
                wavFile.write(chunk)
            }

            wavFile.close()

            // Verify file was created
            if (file.exists()) {
                Log.d(TAG, "Audio saved successfully: ${file.absolutePath} (${file.length()} bytes)")
            } else {
                Log.e(TAG, "File was not created: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving audio: ${e.message}", e)
        }
    }

    fun requestTTS(text: String) {
        if (webSocketClient.isConnected() && text.isNotEmpty()) {
            val voice = if (settings.targetLanguage == "cs") "cs" else settings.targetLanguage
            webSocketClient.requestTTS(text, voice)
            Log.d(TAG, "TTS requested for: ${text.take(50)}...")
        }
    }

    fun notifyLanguageSwap(sourceLanguage: String, targetLanguage: String) {
        if (webSocketClient.isConnected()) {
            webSocketClient.sendCommand("language_swap", mapOf(
                "source_language" to sourceLanguage,
                "target_language" to targetLanguage,
                "segment_id" to System.currentTimeMillis().toString()
            ))
            Log.d(TAG, "Language swap: $sourceLanguage -> $targetLanguage")

            // Save current segment
            saveCurrentSegment()
        }
    }

    private fun saveCurrentSegment() {
        if (audioBuffer.isNotEmpty()) {
            val segmentId = System.currentTimeMillis()
            val segmentFile = File(
                audioFile?.parentFile,
                "${currentRecordingId}_segment_$segmentId.wav"
            )
            try {
                saveBufferToWav(segmentFile, audioBuffer.toList())
                audioBuffer.clear()
                Log.d(TAG, "Segment saved: ${segmentFile.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Error saving segment: ${e.message}")
            }
        }
    }

    private fun saveBufferToWav(file: File, buffer: List<ByteArray>) {
        val totalSize = buffer.sumOf { it.size }
        val wavFile = java.io.RandomAccessFile(file, "rw")

        // Write WAV header (stereo)
        val byteRate = SAMPLE_RATE * NUM_CHANNELS * 2
        val blockAlign = NUM_CHANNELS * 2

        wavFile.writeBytes("RIFF")
        wavFile.writeInt(Integer.reverseBytes(36 + totalSize))
        wavFile.writeBytes("WAVE")
        wavFile.writeBytes("fmt ")
        wavFile.writeInt(Integer.reverseBytes(16))
        wavFile.writeShort(java.lang.Short.reverseBytes(1.toShort()).toInt())
        wavFile.writeShort(java.lang.Short.reverseBytes(NUM_CHANNELS.toShort()).toInt())
        wavFile.writeInt(Integer.reverseBytes(SAMPLE_RATE))
        wavFile.writeInt(Integer.reverseBytes(byteRate))
        wavFile.writeShort(java.lang.Short.reverseBytes(blockAlign.toShort()).toInt())
        wavFile.writeShort(java.lang.Short.reverseBytes(16.toShort()).toInt())
        wavFile.writeBytes("data")
        wavFile.writeInt(Integer.reverseBytes(totalSize))

        for (chunk in buffer) {
            wavFile.write(chunk)
        }
        wavFile.close()
    }

    fun clearTTSAudio() {
        _ttsAudio.value = null
    }

    /**
     * Detect speaker mode based on stereo channel comparison
     * Left channel = internal mic (self), Right channel = external mic (other person)
     * This is a heuristic - actual behavior depends on device and mic configuration
     */
    private fun detectSpeakerMode(ampLeft: Float, ampRight: Float) {
        // Add to history
        leftChannelHistory.add(ampLeft)
        rightChannelHistory.add(ampRight)

        // Keep history bounded
        if (leftChannelHistory.size > HISTORY_SIZE) {
            leftChannelHistory.removeAt(0)
            rightChannelHistory.removeAt(0)
        }

        // Need enough history to make decision
        if (leftChannelHistory.size < HISTORY_SIZE) return

        // Calculate average amplitudes
        val avgLeft = leftChannelHistory.average().toFloat()
        val avgRight = rightChannelHistory.average().toFloat()

        // Minimum threshold for speech detection
        val speechThreshold = 0.02f
        val dominanceRatio = 1.5f // One channel needs to be 1.5x louder

        // Only switch if enough time has passed (cooldown)
        val now = System.currentTimeMillis()
        if (now - lastSpeakerSwitch < SPEAKER_SWITCH_COOLDOWN) return

        // Detect who is speaking based on channel dominance
        val newMode = when {
            avgLeft < speechThreshold && avgRight < speechThreshold -> {
                // Silence - keep current mode
                _speakerMode.value
            }
            avgLeft > avgRight * dominanceRatio -> {
                // Left channel dominant = SELF speaking
                SpeakerMode.SELF
            }
            avgRight > avgLeft * dominanceRatio -> {
                // Right channel dominant = EXTERNAL person speaking
                SpeakerMode.EXTERNAL
            }
            else -> {
                // Similar amplitude - keep current mode
                _speakerMode.value
            }
        }

        // Update mode if changed
        if (newMode != _speakerMode.value) {
            _speakerMode.value = newMode
            lastSpeakerSwitch = now
            Log.d(TAG, "Speaker mode changed to: $newMode (L:$avgLeft, R:$avgRight)")

            // Update cover display
            updateCoverDisplayForSpeakerMode(newMode)
        }
    }

    /**
     * Update cover display based on speaker mode
     */
    private fun updateCoverDisplayForSpeakerMode(mode: SpeakerMode) {
        when (mode) {
            SpeakerMode.SELF -> {
                // Show translation on cover display
                CoverDisplayService.setTranslationMode(this)
            }
            SpeakerMode.EXTERNAL -> {
                // Show "please speak in X language" with LED blinking
                CoverDisplayService.setSpeakRequestMode(
                    this,
                    settings.targetLanguage,
                    blink = true
                )
            }
        }
    }

    /**
     * Manually set speaker mode (for UI toggle)
     */
    fun setSpeakerMode(mode: SpeakerMode) {
        _speakerMode.value = mode
        lastSpeakerSwitch = System.currentTimeMillis()
        updateCoverDisplayForSpeakerMode(mode)
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
