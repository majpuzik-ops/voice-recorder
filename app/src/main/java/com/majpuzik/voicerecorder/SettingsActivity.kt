package com.majpuzik.voicerecorder

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Bundle
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.majpuzik.voicerecorder.data.AppSettings
import kotlin.concurrent.thread
import kotlin.math.abs

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var etServerUrl: EditText
    private lateinit var spinnerLanguage: Spinner
    private lateinit var spinnerAudioSource: Spinner
    private lateinit var spinnerLlmProvider: Spinner
    private lateinit var etLlmApiKey: EditText
    private lateinit var tvUserId: TextView
    private lateinit var tvMicTestLevel: TextView
    private lateinit var switchAutoTranscribe: Switch
    private lateinit var switchAutoTranslate: Switch
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
        spinnerLanguage = findViewById(R.id.spinnerLanguage)
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

        // Setup language spinner
        val languages = settings.availableLanguages.values.toList()
        val langAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLanguage.adapter = langAdapter

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
    }

    private fun loadSettings() {
        etServerUrl.setText(settings.serverUrl)
        tvUserId.text = "ID: ${settings.userId}"
        switchAutoTranscribe.isChecked = settings.autoTranscribe
        switchAutoTranslate.isChecked = settings.autoTranslate
        etLlmApiKey.setText(settings.llmApiKey)

        // Select current language
        val currentLangIndex = settings.availableLanguages.keys.indexOf(settings.targetLanguage)
        if (currentLangIndex >= 0) {
            spinnerLanguage.setSelection(currentLangIndex)
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

        // Get selected language code
        val selectedLangIndex = spinnerLanguage.selectedItemPosition
        val languageCode = settings.availableLanguages.keys.toList()[selectedLangIndex]
        settings.targetLanguage = languageCode

        // Get selected audio source
        val selectedAudioIndex = spinnerAudioSource.selectedItemPosition
        val audioSourceCode = settings.availableAudioSources.keys.toList()[selectedAudioIndex]
        settings.audioSource = audioSourceCode

        // Get selected LLM provider
        val selectedLlmIndex = spinnerLlmProvider.selectedItemPosition
        val llmProviderCode = settings.availableLlmProviders.keys.toList()[selectedLlmIndex]
        settings.llmProvider = llmProviderCode

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
        if (requestCode == 100 && grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startMicTest()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMicTest()
    }
}
