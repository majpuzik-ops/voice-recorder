package com.majpuzik.voicerecorder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.app.ActivityOptions
import android.graphics.Rect
import android.print.PrintAttributes
import android.print.PrintManager
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
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
import com.majpuzik.voicerecorder.service.CoverDisplayService
import com.majpuzik.voicerecorder.util.TailscaleHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executor
import androidx.window.area.WindowAreaCapability
import androidx.window.area.WindowAreaController
import androidx.window.area.WindowAreaInfo
import androidx.window.area.WindowAreaSession
import androidx.window.area.WindowAreaSessionPresenter
import androidx.window.area.WindowAreaPresentationSessionCallback
import android.widget.LinearLayout
import android.view.Gravity
import android.graphics.Color

@androidx.window.core.ExperimentalWindowApi
class MainActivity : AppCompatActivity(), WindowAreaPresentationSessionCallback {

    private lateinit var settings: AppSettings
    private lateinit var ttsPlayer: TTSPlayer
    private lateinit var tailscaleHelper: TailscaleHelper

    // WindowArea for Samsung Fold Dual Screen Mode (PRESENT_ON_AREA - both screens active!)
    private lateinit var windowAreaController: WindowAreaController
    private lateinit var displayExecutor: Executor
    private var windowAreaSession: WindowAreaSessionPresenter? = null
    private var windowAreaInfo: WindowAreaInfo? = null
    private var dualScreenStatus: WindowAreaCapability.Status = WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED
    private val dualScreenOperation = WindowAreaCapability.Operation.OPERATION_PRESENT_ON_AREA

    // UI elements
    private lateinit var tvConnectionStatus: TextView
    private lateinit var tvOriginalText: TextView
    private lateinit var scrollOriginal: ScrollView
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
    private lateinit var btnShare: ImageButton
    private lateinit var btnPrint: ImageButton
    private lateinit var btnSwapLanguages: ImageButton
    private lateinit var btnCoverDisplay: ImageButton
    private lateinit var ivTailscale: ImageView
    private lateinit var btnEnableTailscale: ImageButton
    private lateinit var fabSwitchSpeaker: FloatingActionButton

    private var recordingService: RecordingService? = null
    private var isServiceBound = false
    private var isRecording = false
    private var currentRecordingId: String = ""

    // Floating translation window for other person
    private var isCoverDisplayActive = false

    // Speaker tracking: false = user speaking (speaker 1), true = other person speaking (speaker 2)
    private var isOtherPersonSpeaking = false

    private val tailscaleCheckHandler = Handler(Looper.getMainLooper())
    private val tailscaleCheckRunnable = object : Runnable {
        override fun run() {
            checkTailscaleStatus()
            tailscaleCheckHandler.postDelayed(this, 5000) // Check every 5 seconds
        }
    }

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
                        // Auto-scroll to bottom
                        scrollOriginal.post {
                            scrollOriginal.fullScroll(View.FOCUS_DOWN)
                        }
                        // Debug
                        android.util.Log.d("MainActivity", "Transcription: $text")
                    }
                }
            }

            // Observe translation from service
            lifecycleScope.launch {
                recordingService?.translation?.collectLatest { text ->
                    if (text.isNotEmpty()) {
                        tvTranslatedText.text = text
                        // Update cover display
                        updateCoverDisplay()
                        // Debug
                        android.util.Log.d("MainActivity", "Translation: $text")
                    }
                }
            }

            // Observe connection state from service
            lifecycleScope.launch {
                recordingService?.connectionState?.collectLatest { state ->
                    // Debug toast
                    Toast.makeText(this@MainActivity, "Server: $state", Toast.LENGTH_SHORT).show()
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

            // Observe speaker mode for automatic cover display updates
            lifecycleScope.launch {
                recordingService?.speakerMode?.collectLatest { mode ->
                    when (mode) {
                        RecordingService.SpeakerMode.SELF -> {
                            isOtherPersonSpeaking = false
                            btnSwapLanguages.setColorFilter(ContextCompat.getColor(this@MainActivity, android.R.color.holo_orange_light))
                        }
                        RecordingService.SpeakerMode.EXTERNAL -> {
                            isOtherPersonSpeaking = true
                            btnSwapLanguages.setColorFilter(ContextCompat.getColor(this@MainActivity, android.R.color.holo_green_light))
                        }
                    }
                    // Update existing cover display
                    updateCoverDisplay()
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
        tailscaleHelper = TailscaleHelper(this)

        initViews()
        setupListeners()
        updateTranslationLabel()
        startTailscaleMonitoring()
        initDualScreenMode()
    }

    private fun initViews() {
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        tvOriginalText = findViewById(R.id.tvOriginalText)
        scrollOriginal = findViewById(R.id.scrollOriginal)
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
        btnShare = findViewById(R.id.btnShare)
        btnPrint = findViewById(R.id.btnPrint)
        btnSwapLanguages = findViewById(R.id.btnSwapLanguages)
        btnCoverDisplay = findViewById(R.id.btnCoverDisplay)
        ivTailscale = findViewById(R.id.ivTailscale)
        btnEnableTailscale = findViewById(R.id.btnEnableTailscale)
        fabSwitchSpeaker = findViewById(R.id.fabSwitchSpeaker)

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

        btnShare.setOnClickListener {
            shareConversation()
        }

        btnPrint.setOnClickListener {
            printConversation()
        }

        btnSwapLanguages.setOnClickListener {
            swapLanguages()
        }

        btnCoverDisplay.setOnClickListener {
            toggleCoverDisplay()
        }

        btnEnableTailscale.setOnClickListener {
            tailscaleHelper.openTailscale()
            Toast.makeText(this, "Otviram Tailscale...", Toast.LENGTH_SHORT).show()
        }

        fabSwitchSpeaker.setOnClickListener {
            toggleSpeakerMode()
        }
    }

    private fun toggleSpeakerMode() {
        recordingService?.let { service ->
            val currentMode = service.speakerMode.value
            val newMode = if (currentMode == RecordingService.SpeakerMode.SELF) {
                RecordingService.SpeakerMode.EXTERNAL
            } else {
                RecordingService.SpeakerMode.SELF
            }
            service.setSpeakerMode(newMode)

            // Update button appearance
            updateSpeakerButtonUI(newMode)

            // Swap languages when switching speaker
            if (newMode == RecordingService.SpeakerMode.EXTERNAL) {
                // Other person speaks - swap languages so their speech gets translated to our language
                swapLanguages()
                Toast.makeText(this, "Druhy recnik - cekam na hlas...", Toast.LENGTH_SHORT).show()
            } else {
                // Back to self - swap languages back
                swapLanguages()
                Toast.makeText(this, "Vas hlas - prekladam...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateSpeakerButtonUI(mode: RecordingService.SpeakerMode) {
        when (mode) {
            RecordingService.SpeakerMode.SELF -> {
                fabSwitchSpeaker.setImageResource(android.R.drawable.ic_menu_call)
                fabSwitchSpeaker.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#03DAC6")
                )
            }
            RecordingService.SpeakerMode.EXTERNAL -> {
                fabSwitchSpeaker.setImageResource(android.R.drawable.ic_btn_speak_now)
                fabSwitchSpeaker.backgroundTintList = android.content.res.ColorStateList.valueOf(
                    android.graphics.Color.parseColor("#FF6B6B")
                )
            }
        }
    }

    private fun shareConversation() {
        val originalText = tvOriginalText.text.toString()
        val translatedText = tvTranslatedText.text.toString()

        if (originalText.isEmpty() && translatedText.isEmpty()) {
            Toast.makeText(this, "Zadna konverzace ke sdileni", Toast.LENGTH_SHORT).show()
            return
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val timestamp = dateFormat.format(Date())

        val shareText = buildString {
            appendLine("=== Voice Recorder AI ===")
            appendLine("Datum: $timestamp")
            appendLine()
            appendLine("--- ORIGINAL ---")
            appendLine(originalText.ifEmpty { "(prázdné)" })
            appendLine()
            appendLine("--- PŘEKLAD (${settings.targetLanguage.uppercase()}) ---")
            appendLine(translatedText.ifEmpty { "(prázdné)" })
        }

        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_TEXT, shareText)
            putExtra(Intent.EXTRA_SUBJECT, "Konverzace z Voice Recorder AI - $timestamp")
            type = "text/plain"
        }

        val shareIntent = Intent.createChooser(sendIntent, "Sdílet konverzaci")
        startActivity(shareIntent)
    }

    private fun printConversation() {
        val originalText = tvOriginalText.text.toString()
        val translatedText = tvTranslatedText.text.toString()

        if (originalText.isEmpty() && translatedText.isEmpty()) {
            Toast.makeText(this, "Zadna konverzace k tisku", Toast.LENGTH_SHORT).show()
            return
        }

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        val timestamp = dateFormat.format(Date())

        val htmlContent = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <style>
                    body { font-family: Arial, sans-serif; padding: 20px; }
                    h1 { color: #333; border-bottom: 2px solid #03DAC6; padding-bottom: 10px; }
                    h2 { color: #666; margin-top: 20px; }
                    .timestamp { color: #888; font-size: 12px; }
                    .original { background: #f5f5f5; padding: 15px; border-radius: 8px; margin: 10px 0; }
                    .translation { background: #e8f5f3; padding: 15px; border-radius: 8px; margin: 10px 0; border-left: 4px solid #03DAC6; }
                    p { line-height: 1.6; white-space: pre-wrap; }
                </style>
            </head>
            <body>
                <h1>Voice Recorder AI</h1>
                <p class="timestamp">$timestamp</p>

                <h2>Originál</h2>
                <div class="original">
                    <p>${originalText.ifEmpty { "<em>(prázdné)</em>" }.replace("\n", "<br>")}</p>
                </div>

                <h2>Překlad (${settings.targetLanguage.uppercase()})</h2>
                <div class="translation">
                    <p>${translatedText.ifEmpty { "<em>(prázdné)</em>" }.replace("\n", "<br>")}</p>
                </div>
            </body>
            </html>
        """.trimIndent()

        val webView = WebView(this)
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                createWebPrintJob(webView, "Konverzace_$timestamp")
            }
        }
        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
    }

    private fun createWebPrintJob(webView: WebView, jobName: String) {
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val printAdapter = webView.createPrintDocumentAdapter(jobName)
        printManager.print(jobName, printAdapter, PrintAttributes.Builder().build())
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
        val permissions = mutableListOf<String>()

        // Always need RECORD_AUDIO
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }

        // POST_NOTIFICATIONS permission is required on API 33+
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                permissions.add("android.permission.POST_NOTIFICATIONS")
            }
        }

        if (permissions.isEmpty()) {
            startRecording()
        } else {
            permissionLauncher.launch(permissions.toTypedArray())
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

        // Reset speaker state
        isOtherPersonSpeaking = false
        btnSwapLanguages.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_orange_light))

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
                    fabSwitchSpeaker.visibility = View.GONE
                    tvTimer.text = "00:00"
                    waveformView.isActive = false
                    // Reset speaker mode
                    updateSpeakerButtonUI(RecordingService.SpeakerMode.SELF)
                }
                RecordingService.RecordingState.RECORDING -> {
                    fabRecord.setImageResource(android.R.drawable.ic_media_pause)
                    btnPause.visibility = View.VISIBLE
                    fabSwitchSpeaker.visibility = View.VISIBLE
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
        val sourceLang = settings.availableLanguages[settings.sourceLanguage] ?: settings.sourceLanguage
        val targetLang = settings.availableLanguages[settings.targetLanguage] ?: settings.targetLanguage
        tvTranslationLabel.text = "$sourceLang -> $targetLang"
    }

    // Tailscale monitoring
    private fun startTailscaleMonitoring() {
        tailscaleCheckHandler.post(tailscaleCheckRunnable)
    }

    private fun stopTailscaleMonitoring() {
        tailscaleCheckHandler.removeCallbacks(tailscaleCheckRunnable)
    }

    private fun checkTailscaleStatus() {
        val isActive = tailscaleHelper.isTailscaleActive()
        runOnUiThread {
            if (isActive) {
                ivTailscale.setImageResource(android.R.drawable.presence_online)
                btnEnableTailscale.visibility = View.GONE
            } else {
                ivTailscale.setImageResource(android.R.drawable.presence_offline)
                if (tailscaleHelper.isTailscaleInstalled()) {
                    btnEnableTailscale.visibility = View.VISIBLE
                }
            }
        }
    }

    // Language/Speaker swap - switches who is speaking
    private fun swapLanguages() {
        if (!isRecording) {
            Toast.makeText(this, "Nejprve spustte nahravani", Toast.LENGTH_SHORT).show()
            return
        }

        // Toggle speaker
        isOtherPersonSpeaking = !isOtherPersonSpeaking

        // Swap languages
        settings.swapLanguages()
        updateTranslationLabel()

        // Notify server about language swap
        recordingService?.notifyLanguageSwap(settings.sourceLanguage, settings.targetLanguage)

        // Update swap button color
        if (isOtherPersonSpeaking) {
            btnSwapLanguages.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_light))
        } else {
            btnSwapLanguages.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_orange_light))
        }

        // Update cover display with speaker info
        updateCoverDisplay()

        // Visual feedback
        val speaker = if (isOtherPersonSpeaking) "Protistrana mluvi" else "Ja mluvim"
        Toast.makeText(this, speaker, Toast.LENGTH_SHORT).show()
    }

    // Cover display - shows translation on front screen of Fold6
    private fun toggleCoverDisplay() {
        if (isCoverDisplayActive) {
            closeCoverDisplay()
        } else {
            openCoverDisplay()
        }
    }

    private fun openCoverDisplay() {
        when (settings.displayMode) {
            AppSettings.DISPLAY_MODE_FULLSCREEN -> openFullscreenDisplay()
            AppSettings.DISPLAY_MODE_COVER -> openFoldCoverDisplay()
            AppSettings.DISPLAY_MODE_FLOATING -> openFloatingDisplay()
            AppSettings.DISPLAY_MODE_SPLIT -> openSplitDisplay()
            else -> openSplitDisplay()
        }
    }

    private fun openFullscreenDisplay() {
        // Launch FullscreenTranslationActivity - covers entire internal display
        val intent = Intent(this, FullscreenTranslationActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("translation", tvTranslatedText.text.toString())
            putExtra("original", tvOriginalText.text.toString())
            putExtra("prompt", getLanguagePrompt(settings.sourceLanguage))
            putExtra("show_prompt", isOtherPersonSpeaking)
            putExtra("is_recording", isRecording)
        }
        startActivity(intent)

        isCoverDisplayActive = true
        btnCoverDisplay.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_light))
        Toast.makeText(this, "Fullscreen preklad zapnut", Toast.LENGTH_SHORT).show()
    }

    private fun openFoldCoverDisplay() {
        // Use Samsung Dual Screen Mode API (OPERATION_PRESENT_ON_AREA) - both screens active!
        when (dualScreenStatus) {
            WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE -> {
                // Already active, close it
                windowAreaSession?.close()
            }
            WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE -> {
                // Activate dual screen mode - presents content on cover while main stays active!
                windowAreaInfo?.token?.let { token ->
                    windowAreaController.presentContentOnWindowArea(
                        token = token,
                        activity = this,
                        executor = displayExecutor,
                        windowAreaPresentationSessionCallback = this
                    )
                } ?: run {
                    Toast.makeText(this, "Dual screen neni dostupny", Toast.LENGTH_SHORT).show()
                    openFoldCoverDisplayFallback()
                }
            }
            else -> {
                // Fallback - launch CoverDisplayActivity
                openFoldCoverDisplayFallback()
            }
        }
    }

    private fun openFoldCoverDisplayFallback() {
        // Fallback: Launch CoverDisplayActivity for the cover screen
        val intent = Intent(this, CoverDisplayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("translation", tvTranslatedText.text.toString())
            putExtra("prompt", getLanguagePrompt(settings.sourceLanguage))
            putExtra("show_prompt", isOtherPersonSpeaking)
            putExtra("is_recording", isRecording)
        }
        startActivity(intent)

        isCoverDisplayActive = true
        btnCoverDisplay.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_light))
        Toast.makeText(this, "Cover display (fallback) - zavrete telefon", Toast.LENGTH_SHORT).show()
    }

    private fun openFloatingDisplay() {
        // Check overlay permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Povol 'Zobrazeni pres jine aplikace'", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
            return
        }

        // Start floating translation service
        startService(Intent(this, FloatingTranslationService::class.java))
        isCoverDisplayActive = true
        btnCoverDisplay.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_light))
        updateCoverDisplay()
        Toast.makeText(this, "Plovouci okno zapnuto", Toast.LENGTH_SHORT).show()
    }

    private fun openSplitDisplay() {
        // Launch split display activity on the LEFT side (for other person to see)
        val intent = Intent(this, SplitDisplayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            putExtra(SplitDisplayActivity.EXTRA_TRANSLATION, tvTranslatedText.text.toString())
            putExtra(SplitDisplayActivity.EXTRA_PROMPT, getLanguagePrompt(settings.sourceLanguage))
            putExtra(SplitDisplayActivity.EXTRA_SHOW_PROMPT, isOtherPersonSpeaking)
            putExtra(SplitDisplayActivity.EXTRA_IS_RECORDING, isRecording)
        }

        // Try to position on left half of screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val displayMetrics = DisplayMetrics()
            windowManager.defaultDisplay.getMetrics(displayMetrics)
            val screenWidth = displayMetrics.widthPixels
            val screenHeight = displayMetrics.heightPixels

            // Left half bounds
            val leftBounds = Rect(0, 0, screenWidth / 2, screenHeight)
            val options = ActivityOptions.makeBasic()
            options.launchBounds = leftBounds

            startActivity(intent, options.toBundle())
        } else {
            startActivity(intent)
        }

        isCoverDisplayActive = true
        btnCoverDisplay.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_light))
        Toast.makeText(this, "Split screen zapnut", Toast.LENGTH_SHORT).show()
    }

    private fun closeCoverDisplay() {
        when (settings.displayMode) {
            AppSettings.DISPLAY_MODE_FULLSCREEN -> {
                // Send close broadcast to fullscreen activity
                sendBroadcast(Intent("com.majpuzik.voicerecorder.FULLSCREEN_UPDATE").apply {
                    putExtra("mode", "close")
                })
            }
            AppSettings.DISPLAY_MODE_COVER -> {
                // Close WindowArea session if active
                windowAreaSession?.close()
                // Also send close broadcast to cover display activity (fallback)
                sendBroadcast(Intent("com.majpuzik.voicerecorder.COVER_UPDATE").apply {
                    putExtra("mode", "close")
                })
            }
            AppSettings.DISPLAY_MODE_FLOATING -> {
                stopService(Intent(this, FloatingTranslationService::class.java))
            }
            AppSettings.DISPLAY_MODE_SPLIT -> {
                // Send close broadcast to split activity
                sendBroadcast(Intent(SplitDisplayActivity.ACTION_UPDATE).apply {
                    putExtra(SplitDisplayActivity.EXTRA_IS_RECORDING, false)
                })
            }
        }
        isCoverDisplayActive = false
        btnCoverDisplay.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_blue_light))
    }

    private fun updateCoverDisplay() {
        if (!isCoverDisplayActive) return

        val prompt = getLanguagePrompt(settings.sourceLanguage)
        val translation = tvTranslatedText.text.toString()
        val original = tvOriginalText.text.toString()

        when (settings.displayMode) {
            AppSettings.DISPLAY_MODE_FULLSCREEN -> {
                // Update fullscreen activity via broadcast
                sendBroadcast(Intent("com.majpuzik.voicerecorder.FULLSCREEN_UPDATE").apply {
                    putExtra("mode", "update")
                    putExtra("translation", translation)
                    putExtra("original", original)
                    putExtra("prompt", prompt)
                    putExtra("show_prompt", isOtherPersonSpeaking)
                    putExtra("is_recording", isRecording)
                })
            }
            AppSettings.DISPLAY_MODE_COVER -> {
                // Update dual screen content directly if session is active
                if (windowAreaSession != null) {
                    updateCoverDisplayContent()
                } else {
                    // Fallback: Update cover display activity via broadcast
                    sendBroadcast(Intent("com.majpuzik.voicerecorder.COVER_UPDATE").apply {
                        putExtra("mode", "translation")
                        putExtra("text", translation)
                        putExtra("prompt", prompt)
                        putExtra("show_prompt", isOtherPersonSpeaking)
                        putExtra("is_recording", isRecording)
                    })
                }
            }
            AppSettings.DISPLAY_MODE_FLOATING -> {
                val intent = Intent(this, FloatingTranslationService::class.java).apply {
                    action = FloatingTranslationService.ACTION_UPDATE
                    putExtra(FloatingTranslationService.EXTRA_TRANSLATION, translation)
                    putExtra(FloatingTranslationService.EXTRA_PROMPT, prompt)
                    putExtra(FloatingTranslationService.EXTRA_SHOW_PROMPT, isOtherPersonSpeaking)
                    putExtra(FloatingTranslationService.EXTRA_IS_RECORDING, isRecording)
                }
                startService(intent)
            }
            AppSettings.DISPLAY_MODE_SPLIT -> {
                sendBroadcast(Intent(SplitDisplayActivity.ACTION_UPDATE).apply {
                    putExtra(SplitDisplayActivity.EXTRA_TRANSLATION, translation)
                    putExtra(SplitDisplayActivity.EXTRA_PROMPT, prompt)
                    putExtra(SplitDisplayActivity.EXTRA_SHOW_PROMPT, isOtherPersonSpeaking)
                    putExtra(SplitDisplayActivity.EXTRA_IS_RECORDING, isRecording)
                })
            }
        }
    }

    private fun getLanguagePrompt(languageCode: String): String {
        return when (languageCode) {
            "en" -> "Please speak in English"
            "de" -> "Bitte sprechen Sie Deutsch"
            "fr" -> "Veuillez parler en français"
            "es" -> "Por favor hable en español"
            "it" -> "Per favore parla in italiano"
            "pl" -> "Proszę mówić po polsku"
            "ru" -> "Пожалуйста, говорите по-русски"
            "uk" -> "Будь ласка, говоріть українською"
            "sk" -> "Prosím hovorte po slovensky"
            "cs" -> "Prosím mluvte česky"
            else -> "Please speak"
        }
    }

    // Samsung Fold Dual Screen Mode initialization (OPERATION_PRESENT_ON_AREA)
    @androidx.window.core.ExperimentalWindowApi
    private fun initDualScreenMode() {
        try {
            displayExecutor = ContextCompat.getMainExecutor(this)
            windowAreaController = WindowAreaController.getOrCreate()

            lifecycleScope.launch {
                try {
                    windowAreaController.windowAreaInfos
                        .collectLatest { windowAreaInfoList ->
                            // Find rear display area
                            windowAreaInfo = windowAreaInfoList.firstOrNull { info ->
                                info.type == WindowAreaInfo.Type.TYPE_REAR_FACING
                            }

                            windowAreaInfo?.let { info ->
                                // Use OPERATION_PRESENT_ON_AREA for dual screen (both screens active!)
                                val capability = info.getCapability(dualScreenOperation)
                                dualScreenStatus = capability?.status ?: WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED
                                updateDualScreenButton()
                                android.util.Log.d("DualScreen", "Dual screen status: $dualScreenStatus")
                            } ?: run {
                                dualScreenStatus = WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED
                                updateDualScreenButton()
                                android.util.Log.d("DualScreen", "No rear facing display found")
                            }
                        }
                } catch (e: Exception) {
                    dualScreenStatus = WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED
                    updateDualScreenButton()
                    android.util.Log.e("DualScreen", "Error initializing: ${e.message}")
                }
            }
        } catch (e: Exception) {
            // WindowArea not supported on this device
            dualScreenStatus = WindowAreaCapability.Status.WINDOW_AREA_STATUS_UNSUPPORTED
            android.util.Log.e("DualScreen", "WindowArea not supported: ${e.message}")
        }
    }

    private fun updateDualScreenButton() {
        runOnUiThread {
            when (dualScreenStatus) {
                WindowAreaCapability.Status.WINDOW_AREA_STATUS_AVAILABLE -> {
                    btnCoverDisplay.isEnabled = true
                    btnCoverDisplay.alpha = 1.0f
                    android.util.Log.d("DualScreen", "Dual screen AVAILABLE")
                }
                WindowAreaCapability.Status.WINDOW_AREA_STATUS_ACTIVE -> {
                    btnCoverDisplay.isEnabled = true
                    btnCoverDisplay.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_light))
                    android.util.Log.d("DualScreen", "Dual screen ACTIVE")
                }
                else -> {
                    // Still enabled - will use fallback
                    btnCoverDisplay.isEnabled = true
                    btnCoverDisplay.alpha = 0.8f
                    android.util.Log.d("DualScreen", "Dual screen not available: $dualScreenStatus - using fallback")
                }
            }
        }
    }

    // WindowAreaPresentationSessionCallback implementations - DUAL SCREEN MODE
    override fun onSessionStarted(session: WindowAreaSessionPresenter) {
        windowAreaSession = session
        isCoverDisplayActive = true

        // Create translation view for cover display
        updateCoverDisplayContent()

        runOnUiThread {
            btnCoverDisplay.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_green_light))
            Toast.makeText(this, "Dual Screen aktivni! Obe obrazovky bezi.", Toast.LENGTH_SHORT).show()
        }
        android.util.Log.d("DualScreen", "Session started - both screens active!")
    }

    override fun onSessionEnded(t: Throwable?) {
        windowAreaSession = null
        isCoverDisplayActive = false
        runOnUiThread {
            btnCoverDisplay.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_blue_light))
            if (t != null) {
                Toast.makeText(this, "Dual Screen ukoncen: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
        android.util.Log.d("DualScreen", "Session ended: ${t?.message}")
    }

    override fun onContainerVisibilityChanged(isVisible: Boolean) {
        android.util.Log.d("DualScreen", "Cover display visibility: $isVisible")
        if (isVisible) {
            // Refresh content when cover becomes visible
            updateCoverDisplayContent()
        }
    }

    // Update content on cover display
    private fun updateCoverDisplayContent() {
        windowAreaSession?.let { session ->
            val translation = tvTranslatedText.text.toString()
            val prompt = getLanguagePrompt(settings.sourceLanguage)

            // Create layout for cover display
            val container = LinearLayout(session.context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#1a1a2e"))
                gravity = Gravity.CENTER
                setPadding(32, 32, 32, 32)
            }

            // Show prompt if other person should speak
            if (isOtherPersonSpeaking) {
                val promptView = TextView(session.context).apply {
                    text = prompt
                    textSize = 24f
                    setTextColor(Color.parseColor("#03DAC6"))
                    gravity = Gravity.CENTER
                    setPadding(16, 16, 16, 32)
                }
                container.addView(promptView)
            }

            // Translation text - large and readable
            val translationView = TextView(session.context).apply {
                text = if (translation.isNotEmpty()) translation else "Cekam na preklad..."
                textSize = 36f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(16, 16, 16, 16)
            }
            container.addView(translationView)

            // Recording indicator
            if (isRecording) {
                val recordingIndicator = TextView(session.context).apply {
                    text = "● NAHRAVANI"
                    textSize = 18f
                    setTextColor(Color.RED)
                    gravity = Gravity.CENTER
                    setPadding(16, 32, 16, 16)
                }
                container.addView(recordingIndicator)
            }

            session.setContentView(container)
            android.util.Log.d("DualScreen", "Updated cover display content: $translation")
        }
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
        stopTailscaleMonitoring()
        windowAreaSession?.close()
        closeCoverDisplay()
        ttsPlayer.release()
        if (isServiceBound) {
            unbindService(serviceConnection)
        }
    }
}
