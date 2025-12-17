package com.majpuzik.voicerecorder

import android.os.Bundle
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.majpuzik.voicerecorder.data.AppSettings

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var etServerUrl: EditText
    private lateinit var spinnerLanguage: Spinner
    private lateinit var tvUserId: TextView
    private lateinit var switchAutoTranscribe: Switch
    private lateinit var switchAutoTranslate: Switch
    private lateinit var btnSave: Button
    private lateinit var btnTestConnection: Button

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
        tvUserId = findViewById(R.id.tvUserId)
        switchAutoTranscribe = findViewById(R.id.switchAutoTranscribe)
        switchAutoTranslate = findViewById(R.id.switchAutoTranslate)
        btnSave = findViewById(R.id.btnSave)
        btnTestConnection = findViewById(R.id.btnTestConnection)

        // Setup language spinner
        val languages = settings.availableLanguages.values.toList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLanguage.adapter = adapter
    }

    private fun loadSettings() {
        etServerUrl.setText(settings.serverUrl)
        tvUserId.text = "ID: ${settings.userId}"
        switchAutoTranscribe.isChecked = settings.autoTranscribe
        switchAutoTranslate.isChecked = settings.autoTranslate

        // Select current language
        val currentLangIndex = settings.availableLanguages.keys.indexOf(settings.targetLanguage)
        if (currentLangIndex >= 0) {
            spinnerLanguage.setSelection(currentLangIndex)
        }
    }

    private fun setupListeners() {
        btnSave.setOnClickListener {
            saveSettings()
        }

        btnTestConnection.setOnClickListener {
            testConnection()
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

        // Get selected language code
        val selectedIndex = spinnerLanguage.selectedItemPosition
        val languageCode = settings.availableLanguages.keys.toList()[selectedIndex]
        settings.targetLanguage = languageCode

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
                // Simple HTTP check - convert WS to HTTP
                val httpUrl = url.replace("ws://", "http://").replace("wss://", "https://")
                    .replace(Regex(":\\d+.*"), ":8080/health") // Assume health endpoint

                val connection = java.net.URL(httpUrl).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.requestMethod = "GET"

                val responseCode = connection.responseCode
                connection.disconnect()

                runOnUiThread {
                    if (responseCode == 200) {
                        Toast.makeText(this, "Pripojeni OK!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Server odpovida: $responseCode", Toast.LENGTH_SHORT).show()
                    }
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
}
