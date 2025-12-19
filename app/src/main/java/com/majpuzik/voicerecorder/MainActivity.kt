package com.majpuzik.voicerecorder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.*
import com.majpuzik.voicerecorder.view.WaveformView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.majpuzik.voicerecorder.audio.TTSPlayer
import com.majpuzik.voicerecorder.data.AppSettings
import com.majpuzik.voicerecorder.service.RecordingService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var ttsPlayer: TTSPlayer

    // UI elements
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvOriginalText: TextView
    private lateinit var tvTranslatedText: TextView
    private lateinit var tvTranslationLabel: TextView
    private lateinit var tvTimer: TextView
    private lateinit var fabRecord: FloatingActionButton
    private lateinit var btnPause: ImageButton
    private lateinit var btnSettings: ImageButton
    private lateinit var btnRecordings: ImageButton
    private lateinit var layoutRecordingName: LinearLayout
    private lateinit var etRecordingName: EditText
    private lateinit var btnSaveRecording: Button
    private lateinit var waveformView: WaveformView
    private lateinit var tvServerInfo: TextView
    private lateinit var btnTTS: ImageButton

    private var recordingService: RecordingService? = null
    private var isServiceBound = false
    private var isRecording = false
    private var currentRecordingId: String = ""

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            recordingService = binder.getService()
            isServiceBound = true

            // Observe recording state
            lifecycleScope.launch {
                recordingService?.recordingState?.collectLatest { state ->
                    updateRecordingUI(state)
                }
            }

            // Observe timer
            lifecycleScope.launch {
                recordingService?.recordingTime?.collectLatest { time ->
                    tvTimer.text = formatTime(time)
                }
            }

            // Observe amplitude for waveform
            lifecycleScope.launch {
                recordingService?.audioAmplitude?.collectLatest { amplitude ->
                    waveformView.addAmplitude(amplitude)
                }
            }

            // Observe transcription from service
            lifecycleScope.launch {
                recordingService?.transcription?.collectLatest { text ->
                    if (text.isNotEmpty()) {
                        tvOriginalText.text = text
                    }
                }
            }

            // Observe translation from service
            lifecycleScope.launch {
                recordingService?.translation?.collectLatest { text ->
                    if (text.isNotEmpty()) {
                        tvTranslatedText.text = text
                    }
                }
            }

            // Observe connection state from service
            lifecycleScope.launch {
                recordingService?.connectionState?.collectLatest { state ->
                    updateConnectionStatus(state)
                }
            }

            // Observe TTS audio from service
            lifecycleScope.launch {
                recordingService?.ttsAudio?.collectLatest { audioData ->
                    audioData?.let { data ->
                        if (data.isNotEmpty()) {
                            ttsPlayer.playBase64Audio(
                                data,
                                onComplete = {
                                    recordingService?.clearTTSAudio()
                                },
                                onError = { error ->
                                    Toast.makeText(this@MainActivity, "TTS chyba: $error", Toast.LENGTH_SHORT).show()
                                    recordingService?.clearTTSAudio()
                                }
                            )
                        }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isServiceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            startRecording()
        } else {
            Toast.makeText(this, "Potrebuji povoleni pro nahravani", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        settings = AppSettings(this)
        ttsPlayer = TTSPlayer(this)

        initViews()
        setupListeners()
        updateTranslationLabel()
    }

    private fun initViews() {
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvOriginalText = findViewById(R.id.tvOriginalText)
        tvTranslatedText = findViewById(R.id.tvTranslatedText)
        tvTranslationLabel = findViewById(R.id.tvTranslationLabel)
        tvTimer = findViewById(R.id.tvTimer)
        fabRecord = findViewById(R.id.fabRecord)
        btnPause = findViewById(R.id.btnPause)
        btnSettings = findViewById(R.id.btnSettings)
        btnRecordings = findViewById(R.id.btnRecordings)
        layoutRecordingName = findViewById(R.id.layoutRecordingName)
        etRecordingName = findViewById(R.id.etRecordingName)
        btnSaveRecording = findViewById(R.id.btnSaveRecording)
        waveformView = findViewById(R.id.waveformView)
        tvServerInfo = findViewById(R.id.tvServerInfo)
        btnTTS = findViewById(R.id.btnTTS)

        // Show server info
        tvServerInfo.text = "Server: ${settings.serverUrl}"
    }

    private fun setupListeners() {
        fabRecord.setOnClickListener {
            if (isRecording) {
                stopRecording()
            } else {
                checkPermissionsAndRecord()
            }
        }

        btnPause.setOnClickListener {
            recordingService?.togglePause()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        btnRecordings.setOnClickListener {
            startActivity(Intent(this, RecordingsActivity::class.java))
        }

        btnSaveRecording.setOnClickListener {
            saveRecording()
        }

        btnTTS.setOnClickListener {
            val translatedText = tvTranslatedText.text.toString()
            if (translatedText.isNotEmpty()) {
                if (ttsPlayer.isPlaying()) {
                    ttsPlayer.stop()
                    Toast.makeText(this, "TTS zastaveno", Toast.LENGTH_SHORT).show()
                } else {
                    recordingService?.requestTTS(translatedText)
                    Toast.makeText(this, "Prehravani prekladu...", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, "Zadny preklad k prehrani", Toast.LENGTH_SHORT).show()
            }
        }
    }


    private fun updateConnectionStatus(state: String) {
        runOnUiThread {
            when (state) {
                "connected" -> {
                    tvConnectionStatus.text = "Pripojeno k serveru"
                    tvConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                }
                "connecting" -> {
                    tvConnectionStatus.text = "Pripojuji..."
                    tvConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
                }
                "disconnected" -> {
                    tvConnectionStatus.text = "Odpojeno"
                    tvConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                }
                "error" -> {
                    tvConnectionStatus.text = "Chyba pripojeni"
                    tvConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                }
            }
        }
    }


    private fun checkPermissionsAndRecord() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startRecording()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startRecording() {
        currentRecordingId = UUID.randomUUID().toString()

        // Clear previous text
        tvOriginalText.text = ""
        tvTranslatedText.text = ""

        // Update server info
        tvServerInfo.text = "Server: ${settings.serverUrl}"

        // Start recording service (service handles WebSocket connection)
        val intent = Intent(this, RecordingService::class.java).apply {
            action = RecordingService.ACTION_START
            putExtra(RecordingService.EXTRA_RECORDING_ID, currentRecordingId)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        bindService(Intent(this, RecordingService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)

        isRecording = true
        updateRecordingUI(RecordingService.RecordingState.RECORDING)
    }

    private fun stopRecording() {
        recordingService?.stopRecording()

        // Show save dialog
        layoutRecordingName.visibility = View.VISIBLE
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        etRecordingName.setText("Nahravka ${dateFormat.format(Date())}")
    }

    private fun saveRecording() {
        val name = etRecordingName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Zadejte nazev nahravky", Toast.LENGTH_SHORT).show()
            return
        }

        // Save recording with transcription and translation
        recordingService?.saveRecording(name, tvOriginalText.text.toString(), tvTranslatedText.text.toString())

        layoutRecordingName.visibility = View.GONE
        isRecording = false
        updateRecordingUI(RecordingService.RecordingState.IDLE)

        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }

        Toast.makeText(this, "Nahravka ulozena", Toast.LENGTH_SHORT).show()
    }

    private fun updateRecordingUI(state: RecordingService.RecordingState) {
        runOnUiThread {
            when (state) {
                RecordingService.RecordingState.IDLE -> {
                    fabRecord.setImageResource(android.R.drawable.ic_btn_speak_now)
                    btnPause.visibility = View.GONE
                    tvTimer.text = "00:00"
                    waveformView.isActive = false
                }
                RecordingService.RecordingState.RECORDING -> {
                    fabRecord.setImageResource(android.R.drawable.ic_media_pause)
                    btnPause.visibility = View.VISIBLE
                    waveformView.isActive = true
                }
                RecordingService.RecordingState.PAUSED -> {
                    fabRecord.setImageResource(android.R.drawable.ic_media_play)
                    waveformView.isActive = false
                }
            }
        }
    }

    private fun updateTranslationLabel() {
        val langName = settings.availableLanguages[settings.targetLanguage] ?: settings.targetLanguage
        tvTranslationLabel.text = "PREKLAD ($langName)"
    }

    private fun formatTime(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / 1000 / 60) % 60
        val hours = millis / 1000 / 60 / 60
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    override fun onResume() {
        super.onResume()
        updateTranslationLabel()
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsPlayer.release()
        if (isServiceBound) {
            unbindService(serviceConnection)
        }
    }
}
