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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.majpuzik.voicerecorder.data.AppSettings
import com.majpuzik.voicerecorder.data.ServerMessage
import com.majpuzik.voicerecorder.network.WebSocketClient
import com.majpuzik.voicerecorder.service.RecordingService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var webSocketClient: WebSocketClient

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
        webSocketClient = WebSocketClient()

        initViews()
        setupListeners()
        observeWebSocket()
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
    }

    private fun observeWebSocket() {
        lifecycleScope.launch {
            webSocketClient.connectionState.collectLatest { state ->
                updateConnectionStatus(state)
            }
        }

        lifecycleScope.launch {
            webSocketClient.messages.collectLatest { message ->
                handleServerMessage(message)
            }
        }
    }

    private fun updateConnectionStatus(state: WebSocketClient.ConnectionState) {
        runOnUiThread {
            when (state) {
                WebSocketClient.ConnectionState.CONNECTED -> {
                    tvConnectionStatus.text = "Pripojeno k serveru"
                    tvConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_light))
                }
                WebSocketClient.ConnectionState.CONNECTING -> {
                    tvConnectionStatus.text = "Pripojuji..."
                    tvConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_light))
                }
                WebSocketClient.ConnectionState.DISCONNECTED -> {
                    tvConnectionStatus.text = "Odpojeno"
                    tvConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_light))
                }
                WebSocketClient.ConnectionState.ERROR -> {
                    tvConnectionStatus.text = "Chyba pripojeni"
                    tvConnectionStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                }
            }
        }
    }

    private fun handleServerMessage(message: ServerMessage) {
        runOnUiThread {
            when (message.type) {
                "transcription" -> {
                    val text = message.data as? String ?: return@runOnUiThread
                    tvOriginalText.text = text
                }
                "translation" -> {
                    val text = message.data as? String ?: return@runOnUiThread
                    tvTranslatedText.text = text
                }
                "error" -> {
                    Toast.makeText(this, "Server error: ${message.error}", Toast.LENGTH_SHORT).show()
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

        // Connect to server
        webSocketClient.connect(
            settings.serverUrl,
            settings.userId,
            currentRecordingId
        )

        // Start recording service
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

        recordingService?.saveRecording(name, tvOriginalText.text.toString(), tvTranslatedText.text.toString())

        // Send end command to server
        webSocketClient.sendCommand("end_recording", mapOf(
            "name" to name,
            "original_text" to tvOriginalText.text.toString(),
            "translated_text" to tvTranslatedText.text.toString(),
            "target_language" to settings.targetLanguage
        ))

        webSocketClient.disconnect()

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
                }
                RecordingService.RecordingState.RECORDING -> {
                    fabRecord.setImageResource(android.R.drawable.ic_media_pause)
                    btnPause.visibility = View.VISIBLE
                }
                RecordingService.RecordingState.PAUSED -> {
                    fabRecord.setImageResource(android.R.drawable.ic_media_play)
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
        if (isServiceBound) {
            unbindService(serviceConnection)
        }
        webSocketClient.disconnect()
    }
}
