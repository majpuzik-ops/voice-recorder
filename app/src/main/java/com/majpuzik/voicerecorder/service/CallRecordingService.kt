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
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.majpuzik.voicerecorder.MainActivity
import com.majpuzik.voicerecorder.R
import com.majpuzik.voicerecorder.data.AppSettings
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.*

class CallRecordingService : Service() {

    companion object {
        private const val TAG = "CallRecordingService"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "call_recording_channel"

        const val ACTION_START = "start_call_recording"
        const val ACTION_STOP = "stop_call_recording"
        const val EXTRA_PHONE_NUMBER = "phone_number"
        const val EXTRA_IS_INCOMING = "is_incoming"

        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val NUM_CHANNELS = 2
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var settings: AppSettings

    private var currentPhoneNumber: String = ""
    private var isIncoming: Boolean = false
    private var audioFile: File? = null
    private val audioBuffer = mutableListOf<ByteArray>()
    private var startTime: Long = 0

    override fun onCreate() {
        super.onCreate()
        settings = AppSettings(this)
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                currentPhoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: "unknown"
                isIncoming = intent.getBooleanExtra(EXTRA_IS_INCOMING, false)
                startRecording()
            }
            ACTION_STOP -> {
                stopRecording()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nahravani hovoru",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifikace behem nahravani hovoru"
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

        val callType = if (isIncoming) "prichozi" else "odchozi"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Nahravani hovoru")
            .setContentText("$callType: $currentPhoneNumber")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startRecording() {
        Log.d(TAG, "Starting call recording for: $currentPhoneNumber")

        // Prepare audio file
        val recordingsDir = File(filesDir, "call_recordings")
        recordingsDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val callType = if (isIncoming) "incoming" else "outgoing"
        audioFile = File(recordingsDir, "${callType}_${currentPhoneNumber}_$timestamp.wav")

        startForeground(NOTIFICATION_ID, createNotification())

        // Try different audio sources for call recording
        val audioSourcesToTry = listOf(
            MediaRecorder.AudioSource.VOICE_CALL,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        )

        var initialized = false
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        for (source in audioSourcesToTry) {
            try {
                Log.d(TAG, "Trying audio source: $source")
                audioRecord = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize * 2
                )

                if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                    Log.d(TAG, "Successfully initialized with audio source: $source")
                    initialized = true
                    break
                } else {
                    audioRecord?.release()
                    audioRecord = null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed with source $source: ${e.message}")
                audioRecord?.release()
                audioRecord = null
            }
        }

        if (!initialized) {
            Log.e(TAG, "Could not initialize AudioRecord with any source")
            stopSelf()
            return
        }

        audioRecord?.startRecording()
        startTime = System.currentTimeMillis()
        audioBuffer.clear()

        recordingJob = scope.launch {
            val buffer = ByteArray(bufferSize)

            while (isActive) {
                val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (bytesRead > 0) {
                    audioBuffer.add(buffer.copyOf(bytesRead))
                }
            }
        }

        Log.d(TAG, "Call recording started")
    }

    private fun stopRecording() {
        Log.d(TAG, "Stopping call recording")

        recordingJob?.cancel()

        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping AudioRecord: ${e.message}")
        }

        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord: ${e.message}")
        }
        audioRecord = null

        saveToWavFile()
        saveRecordingMetadata()

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun saveToWavFile() {
        if (audioFile == null || audioBuffer.isEmpty()) {
            Log.e(TAG, "Nothing to save")
            return
        }

        val file = audioFile!!
        val totalSize = audioBuffer.sumOf { it.size }

        try {
            Log.d(TAG, "Saving call recording: ${audioBuffer.size} chunks, $totalSize bytes")

            file.parentFile?.mkdirs()
            val wavFile = RandomAccessFile(file, "rw")

            // WAV header
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

            for (chunk in audioBuffer) {
                wavFile.write(chunk)
            }

            wavFile.close()

            Log.d(TAG, "Call recording saved: ${file.absolutePath} (${file.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving call recording: ${e.message}", e)
        }
    }

    private fun saveRecordingMetadata() {
        val duration = System.currentTimeMillis() - startTime
        val id = UUID.randomUUID().toString()
        val callType = if (isIncoming) "prichozi" else "odchozi"
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        val name = "$callType hovor - $currentPhoneNumber ($timestamp)"

        val prefs = getSharedPreferences("recordings", MODE_PRIVATE)
        val recordings = prefs.getStringSet("recording_ids", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        recordings.add(id)

        prefs.edit()
            .putStringSet("recording_ids", recordings)
            .putString("${id}_name", name)
            .putString("${id}_path", audioFile?.absolutePath ?: "")
            .putLong("${id}_duration", duration)
            .putLong("${id}_timestamp", System.currentTimeMillis())
            .putString("${id}_phone_number", currentPhoneNumber)
            .putBoolean("${id}_is_incoming", isIncoming)
            .putString("${id}_type", "call")
            .apply()

        Log.d(TAG, "Recording metadata saved: $name")
    }

    override fun onDestroy() {
        super.onDestroy()
        recordingJob?.cancel()
        audioRecord?.release()
        scope.cancel()
    }
}
