package com.majpuzik.voicerecorder

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Build
import android.os.Bundle
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.majpuzik.voicerecorder.data.AppSettings
import kotlin.concurrent.thread
import kotlin.math.abs

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var etServerUrl: EditText
    private lateinit var spinnerSourceLanguage: Spinner
    private lateinit var spinnerTargetLanguage: Spinner
    private lateinit var spinnerAudioSource: Spinner
    private lateinit var spinnerLlmProvider: Spinner
    private lateinit var etLlmApiKey: EditText
    private lateinit var spinnerTranscriptionProvider: Spinner
    private lateinit var etTranscriptionApiKey: EditText
    private lateinit var spinnerDisplayMode: Spinner
    private lateinit var tvUserId: TextView
    private lateinit var tvMicTestLevel: TextView
    private lateinit var switchAutoTranscribe: Switch
    private lateinit var switchAutoTranslate: Switch
    private lateinit var switchAutoCallRecording: Switch
    private lateinit var btnSave: Button
    private lateinit var btnTestConnection: Button
    private lateinit var btnTestMic: Button
    private lateinit var btnStopTestMic: Button

    private var audioRecord: AudioRecord? = null
    private var isTesting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Nastaveni"

        settings = AppSettings(this)
        initViews()
        loadSettings()
        setupListeners()
    }

    private fun initViews() {
        etServerUrl = findViewById(R.id.etServerUrl)
        spinnerSourceLanguage = findViewById(R.id.spinnerSourceLanguage)
        spinnerTargetLanguage = findViewById(R.id.spinnerTargetLanguage)
        spinnerAudioSource = findViewById(R.id.spinnerAudioSource)
        spinnerLlmProvider = findViewById(R.id.spinnerLlmProvider)
        etLlmApiKey = findViewById(R.id.etLlmApiKey)
        tvUserId = findViewById(R.id.tvUserId)
        tvMicTestLevel = findViewById(R.id.tvMicTestLevel)
        switchAutoTranscribe = findViewById(R.id.switchAutoTranscribe)
        switchAutoTranslate = findViewById(R.id.switchAutoTranslate)
        btnSave = findViewById(R.id.btnSave)
        btnTestConnection = findViewById(R.id.btnTestConnection)
        btnTestMic = findViewById(R.id.btnTestMic)
        btnStopTestMic = findViewById(R.id.btnStopTestMic)

        // Setup language spinners
        val languages = settings.availableLanguages.values.toList()
        val sourceLangAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        sourceLangAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerSourceLanguage.adapter = sourceLangAdapter

        val targetLangAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        targetLangAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTargetLanguage.adapter = targetLangAdapter

        // Setup audio source spinner
        val audioSources = settings.availableAudioSources.values.toList()
        val audioAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, audioSources)
        audioAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAudioSource.adapter = audioAdapter

        // Setup LLM provider spinner
        val llmProviders = settings.availableLlmProviders.values.toList()
        val llmAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, llmProviders)
        llmAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLlmProvider.adapter = llmAdapter

        // Setup transcription provider spinner
        spinnerTranscriptionProvider = findViewById(R.id.spinnerTranscriptionProvider)
        etTranscriptionApiKey = findViewById(R.id.etTranscriptionApiKey)
        val transcriptionProviders = settings.availableTranscriptionProviders.values.toList()
        val transcriptionAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, transcriptionProviders)
        transcriptionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerTranscriptionProvider.adapter = transcriptionAdapter

        // Setup display mode spinner
        spinnerDisplayMode = findViewById(R.id.spinnerDisplayMode)
        val displayModes = settings.availableDisplayModes.values.toList()
        val displayModeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, displayModes)
        displayModeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerDisplayMode.adapter = displayModeAdapter

        // Setup call recording switch
        switchAutoCallRecording = findViewById(R.id.switchAutoCallRecording)
    }

    private fun loadSettings() {
        etServerUrl.setText(settings.serverUrl)
        tvUserId.text = "ID: ${settings.userId}"
        switchAutoTranscribe.isChecked = settings.autoTranscribe
        switchAutoTranslate.isChecked = settings.autoTranslate
        etLlmApiKey.setText(settings.llmApiKey)

        // Select current source language
        val currentSourceIndex = settings.availableLanguages.keys.indexOf(settings.sourceLanguage)
        if (currentSourceIndex >= 0) {
            spinnerSourceLanguage.setSelection(currentSourceIndex)
        }

        // Select current target language
        val currentTargetIndex = settings.availableLanguages.keys.indexOf(settings.targetLanguage)
        if (currentTargetIndex >= 0) {
            spinnerTargetLanguage.setSelection(currentTargetIndex)
        }

        // Select current audio source
        val currentAudioIndex = settings.availableAudioSources.keys.indexOf(settings.audioSource)
        if (currentAudioIndex >= 0) {
            spinnerAudioSource.setSelection(currentAudioIndex)
        }

        // Select current LLM provider
        val currentLlmIndex = settings.availableLlmProviders.keys.indexOf(settings.llmProvider)
        if (currentLlmIndex >= 0) {
            spinnerLlmProvider.setSelection(currentLlmIndex)
        }

        // Select current transcription provider
        val currentTranscriptionIndex = settings.availableTranscriptionProviders.keys.indexOf(settings.transcriptionProvider)
        if (currentTranscriptionIndex >= 0) {
            spinnerTranscriptionProvider.setSelection(currentTranscriptionIndex)
        }
        etTranscriptionApiKey.setText(settings.transcriptionApiKey)

        // Select current display mode
        val currentDisplayModeIndex = settings.availableDisplayModes.keys.indexOf(settings.displayMode)
        if (currentDisplayModeIndex >= 0) {
            spinnerDisplayMode.setSelection(currentDisplayModeIndex)
        }

        // Load call recording setting
        switchAutoCallRecording.isChecked = settings.autoCallRecording
    }

    private fun setupListeners() {
        btnSave.setOnClickListener {
            saveSettings()
        }

        btnTestConnection.setOnClickListener {
            testConnection()
        }

        btnTestMic.setOnClickListener {
            startMicTest()
        }

        btnStopTestMic.setOnClickListener {
            stopMicTest()
        }

        switchAutoCallRecording.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requestCallRecordingPermissions()
            }
        }
    }

    private fun requestCallRecordingPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_CALL_LOG)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Opravneni pro nahravani hovoru")
                .setMessage("Pro automaticke nahravani hovoru potrebuji pristup k telefonu a mikrofonu. " +
                        "Poznamka: Na nekterych telefonech nemusí nahravani druhe strany fungovat bez root opravneni.")
                .setPositiveButton("Povolit") { _, _ ->
                    ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 200)
                }
                .setNegativeButton("Zrusit") { _, _ ->
                    switchAutoCallRecording.isChecked = false
                }
                .show()
        }
    }

    private fun saveSettings() {
        val url = etServerUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Zadejte URL serveru", Toast.LENGTH_SHORT).show()
            return
        }

        settings.serverUrl = url
        settings.autoTranscribe = switchAutoTranscribe.isChecked
        settings.autoTranslate = switchAutoTranslate.isChecked
        settings.llmApiKey = etLlmApiKey.text.toString().trim()

        // Get selected source language
        val selectedSourceIndex = spinnerSourceLanguage.selectedItemPosition
        val sourceLanguageCode = settings.availableLanguages.keys.toList()[selectedSourceIndex]
        settings.sourceLanguage = sourceLanguageCode

        // Get selected target language
        val selectedTargetIndex = spinnerTargetLanguage.selectedItemPosition
        val targetLanguageCode = settings.availableLanguages.keys.toList()[selectedTargetIndex]
        settings.targetLanguage = targetLanguageCode

        // Get selected audio source
        val selectedAudioIndex = spinnerAudioSource.selectedItemPosition
        val audioSourceCode = settings.availableAudioSources.keys.toList()[selectedAudioIndex]
        settings.audioSource = audioSourceCode

        // Get selected LLM provider
        val selectedLlmIndex = spinnerLlmProvider.selectedItemPosition
        val llmProviderCode = settings.availableLlmProviders.keys.toList()[selectedLlmIndex]
        settings.llmProvider = llmProviderCode

        // Get selected transcription provider
        val selectedTranscriptionIndex = spinnerTranscriptionProvider.selectedItemPosition
        val transcriptionProviderCode = settings.availableTranscriptionProviders.keys.toList()[selectedTranscriptionIndex]
        settings.transcriptionProvider = transcriptionProviderCode
        settings.transcriptionApiKey = etTranscriptionApiKey.text.toString().trim()

        // Get selected display mode
        val selectedDisplayModeIndex = spinnerDisplayMode.selectedItemPosition
        val displayModeCode = settings.availableDisplayModes.keys.toList()[selectedDisplayModeIndex]
        settings.displayMode = displayModeCode

        // Save call recording setting
        settings.autoCallRecording = switchAutoCallRecording.isChecked

        Toast.makeText(this, "Nastaveni ulozeno", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun testConnection() {
        val url = etServerUrl.text.toString().trim()
        if (url.isEmpty()) {
            Toast.makeText(this, "Zadejte URL serveru", Toast.LENGTH_SHORT).show()
            return
        }

        btnTestConnection.isEnabled = false
        btnTestConnection.text = "Testuji..."

        Thread {
            try {
                // Extract host and port from WebSocket URL
                val cleanUrl = url.replace("ws://", "").replace("wss://", "")
                val parts = cleanUrl.split(":")
                val host = parts[0]
                val port = if (parts.size > 1) parts[1].split("/")[0].toInt() else 8765

                // Test TCP connection to WebSocket port
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(host, port), 5000)
                socket.close()

                runOnUiThread {
                    Toast.makeText(this, "Pripojeni OK! ($host:$port)", Toast.LENGTH_SHORT).show()
                    btnTestConnection.isEnabled = true
                    btnTestConnection.text = "Test pripojeni"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Chyba: ${e.message}", Toast.LENGTH_SHORT).show()
                    btnTestConnection.isEnabled = true
                    btnTestConnection.text = "Test pripojeni"
                }
            }
        }.start()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun startMicTest() {
        // Check permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }

        // Get selected audio source
        val selectedAudioIndex = spinnerAudioSource.selectedItemPosition
        val audioSourceCode = settings.availableAudioSources.keys.toList()[selectedAudioIndex]

        // Temporarily set for test
        val originalSource = settings.audioSource
        settings.audioSource = audioSourceCode
        val audioSourceInt = settings.getAudioSourceInt()
        settings.audioSource = originalSource

        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            audioRecord = AudioRecord(
                audioSourceInt,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Toast.makeText(this, "Nelze inicializovat mikrofon", Toast.LENGTH_SHORT).show()
                return
            }

            audioRecord?.startRecording()
            isTesting = true

            btnTestMic.isEnabled = false
            btnStopTestMic.isEnabled = true
            tvMicTestLevel.text = "Uroven: Testuji..."

            thread {
                val buffer = ByteArray(bufferSize)
                while (isTesting) {
                    val bytesRead = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (bytesRead > 0) {
                        // Calculate amplitude
                        var sum = 0L
                        for (i in 0 until bytesRead step 2) {
                            if (i + 1 < bytesRead) {
                                val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                                sum += abs(sample.toShort().toInt())
                            }
                        }
                        val amplitude = (sum / (bytesRead / 2)).toFloat() / Short.MAX_VALUE
                        val percent = (amplitude * 100).toInt()
                        val bars = "█".repeat((amplitude * 20).toInt().coerceIn(0, 20))

                        runOnUiThread {
                            tvMicTestLevel.text = "Uroven: $percent% $bars"
                        }
                    }
                    Thread.sleep(50)
                }
            }

        } catch (e: SecurityException) {
            Toast.makeText(this, "Chybi opravneni k mikrofonu", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Chyba: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopMicTest() {
        isTesting = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (e: Exception) {
            // Ignore
        }

        btnTestMic.isEnabled = true
        btnStopTestMic.isEnabled = false
        tvMicTestLevel.text = "Uroven: --"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            100 -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startMicTest()
                }
            }
            200 -> {
                val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                if (allGranted) {
                    Toast.makeText(this, "Opravneni udelena - nahravani hovoru aktivni", Toast.LENGTH_SHORT).show()
                } else {
                    switchAutoCallRecording.isChecked = false
                    Toast.makeText(this, "Nektera opravneni nebyla udelena", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMicTest()
    }
}
