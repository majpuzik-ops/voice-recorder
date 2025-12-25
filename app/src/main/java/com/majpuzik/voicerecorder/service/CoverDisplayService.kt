package com.majpuzik.voicerecorder.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.majpuzik.voicerecorder.CoverDisplayActivity
import com.majpuzik.voicerecorder.R

/**
 * Service that manages content display on Samsung Fold cover screen
 * Uses Presentation API for secondary display
 */
class CoverDisplayService : Service() {

    companion object {
        private const val TAG = "CoverDisplayService"
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "cover_display_channel"

        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        const val ACTION_UPDATE = "update"
        const val ACTION_SET_MODE = "set_mode"

        const val EXTRA_MODE = "mode"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_BLINK = "blink"

        const val MODE_TRANSLATION = "translation"
        const val MODE_SPEAK_REQUEST = "speak_request"

        fun startService(context: Context) {
            val intent = Intent(context, CoverDisplayService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, CoverDisplayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun updateTranslation(context: Context, text: String) {
            val intent = Intent(context, CoverDisplayService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_MODE, MODE_TRANSLATION)
                putExtra(EXTRA_TEXT, text)
            }
            context.startService(intent)
        }

        fun setSpeakRequestMode(context: Context, language: String, blink: Boolean = true) {
            val intent = Intent(context, CoverDisplayService::class.java).apply {
                action = ACTION_SET_MODE
                putExtra(EXTRA_MODE, MODE_SPEAK_REQUEST)
                putExtra(EXTRA_LANGUAGE, language)
                putExtra(EXTRA_BLINK, blink)
            }
            context.startService(intent)
        }

        fun setTranslationMode(context: Context) {
            val intent = Intent(context, CoverDisplayService::class.java).apply {
                action = ACTION_SET_MODE
                putExtra(EXTRA_MODE, MODE_TRANSLATION)
            }
            context.startService(intent)
        }
    }

    private var coverPresentation: CoverPresentation? = null
    private var secondaryDisplay: Display? = null
    private val handler = Handler(Looper.getMainLooper())

    private var currentMode = MODE_TRANSLATION
    private var currentText = ""
    private var currentLanguage = "en"
    private var isBlinking = false

    // Edge lighting / LED blink state
    private var blinkRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        findSecondaryDisplay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, createNotification())
                showCoverPresentation()
            }
            ACTION_STOP -> {
                hideCoverPresentation()
                stopLedBlinking()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_UPDATE -> {
                currentText = intent.getStringExtra(EXTRA_TEXT) ?: ""
                updatePresentation()
            }
            ACTION_SET_MODE -> {
                val newMode = intent.getStringExtra(EXTRA_MODE) ?: MODE_TRANSLATION
                currentLanguage = intent.getStringExtra(EXTRA_LANGUAGE) ?: currentLanguage
                val shouldBlink = intent.getBooleanExtra(EXTRA_BLINK, false)

                setMode(newMode, shouldBlink)
            }
        }
        return START_STICKY
    }

    private fun findSecondaryDisplay() {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays

        Log.d(TAG, "Found ${displays.size} displays")

        for (display in displays) {
            Log.d(TAG, "Display: ${display.displayId}, name: ${display.name}, " +
                    "isValid: ${display.isValid}")

            if (display.displayId != Display.DEFAULT_DISPLAY && display.isValid) {
                secondaryDisplay = display
                Log.d(TAG, "Using secondary display: ${display.name}")
                break
            }
        }

        // Also listen for display changes
        displayManager.registerDisplayListener(object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {
                Log.d(TAG, "Display added: $displayId")
                if (secondaryDisplay == null) {
                    val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    secondaryDisplay = dm.getDisplay(displayId)
                    if (coverPresentation == null) {
                        showCoverPresentation()
                    }
                }
            }

            override fun onDisplayRemoved(displayId: Int) {
                Log.d(TAG, "Display removed: $displayId")
                if (secondaryDisplay?.displayId == displayId) {
                    hideCoverPresentation()
                    secondaryDisplay = null
                }
            }

            override fun onDisplayChanged(displayId: Int) {
                Log.d(TAG, "Display changed: $displayId")
            }
        }, handler)
    }

    private fun showCoverPresentation() {
        val display = secondaryDisplay
        if (display == null) {
            Log.w(TAG, "No secondary display available, sending broadcast instead")
            sendUpdateBroadcast()
            return
        }

        try {
            coverPresentation = CoverPresentation(this, display)
            coverPresentation?.show()
            Log.d(TAG, "Cover presentation shown on display ${display.displayId}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show cover presentation: ${e.message}")
            // Fallback to broadcast
            sendUpdateBroadcast()
        }
    }

    private fun hideCoverPresentation() {
        coverPresentation?.dismiss()
        coverPresentation = null
    }

    private fun updatePresentation() {
        coverPresentation?.updateContent(currentMode, currentText, currentLanguage, isBlinking)
            ?: sendUpdateBroadcast()
    }

    private fun setMode(mode: String, blink: Boolean) {
        currentMode = mode
        isBlinking = blink

        if (mode == MODE_SPEAK_REQUEST && blink) {
            startLedBlinking()
        } else {
            stopLedBlinking()
        }

        updatePresentation()
    }

    private fun sendUpdateBroadcast() {
        // Send broadcast to CoverDisplayActivity if it's running
        val intent = Intent(CoverDisplayActivity.ACTION_UPDATE_CONTENT).apply {
            putExtra(CoverDisplayActivity.EXTRA_MODE, currentMode)
            putExtra(CoverDisplayActivity.EXTRA_TEXT, currentText)
            putExtra(CoverDisplayActivity.EXTRA_LANGUAGE, currentLanguage)
            putExtra(CoverDisplayActivity.EXTRA_BLINK, isBlinking)
        }
        sendBroadcast(intent)
    }

    private fun startLedBlinking() {
        if (blinkRunnable != null) return

        blinkRunnable = object : Runnable {
            private var blinkState = false

            override fun run() {
                blinkState = !blinkState
                triggerEdgeLighting(blinkState)
                handler.postDelayed(this, 1000) // 1 second interval
            }
        }

        handler.post(blinkRunnable!!)
        Log.d(TAG, "LED blinking started")
    }

    private fun stopLedBlinking() {
        blinkRunnable?.let { handler.removeCallbacks(it) }
        blinkRunnable = null
        Log.d(TAG, "LED blinking stopped")
    }

    /**
     * Trigger Edge Lighting effect on Samsung devices
     * This uses notification with lights to trigger Edge Lighting
     */
    private fun triggerEdgeLighting(on: Boolean) {
        if (!on) return

        // Create a brief notification to trigger Edge Lighting
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val edgeNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Ceka se na odpoved")
            .setContentText("Mluv prosim v prekladanem jazyce")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setLights(Color.RED, 500, 500)
            .setAutoCancel(true)
            .build()

        nm.notify(NOTIFICATION_ID + 1, edgeNotification)

        // Cancel after brief moment
        handler.postDelayed({
            nm.cancel(NOTIFICATION_ID + 1)
        }, 500)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Cover Display",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Zobrazeni na cover displeji"
                enableLights(true)
                lightColor = Color.RED
            }

            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Cover Display aktivni")
            .setContentText("Preklad zobrazen na vnejsim displeji")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        hideCoverPresentation()
        stopLedBlinking()
    }

    /**
     * Presentation for showing content on cover display
     */
    inner class CoverPresentation(
        context: Context,
        display: Display
    ) : Presentation(context, display) {

        private lateinit var tvMainText: TextView
        private lateinit var tvSubText: TextView
        private lateinit var blinkIndicator: android.view.View
        private lateinit var rootLayout: android.view.ViewGroup

        private var animator: android.animation.ObjectAnimator? = null

        override fun onCreate(savedInstanceState: android.os.Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(R.layout.activity_cover_display)

            rootLayout = findViewById(R.id.rootLayout)
            tvMainText = findViewById(R.id.tvMainText)
            tvSubText = findViewById(R.id.tvSubText)
            blinkIndicator = findViewById(R.id.blinkIndicator)

            updateContent(currentMode, currentText, currentLanguage, isBlinking)
        }

        fun updateContent(mode: String, text: String, language: String, blink: Boolean) {
            when (mode) {
                MODE_TRANSLATION -> showTranslation(text)
                MODE_SPEAK_REQUEST -> showSpeakRequest(language, blink)
            }
        }

        private fun showTranslation(text: String) {
            rootLayout.setBackgroundColor(Color.parseColor("#1A1A1A"))
            tvMainText.text = text.ifEmpty { "..." }
            tvMainText.setTextColor(Color.WHITE)
            tvMainText.textSize = if (text.length > 100) 24f else 32f
            tvSubText.visibility = android.view.View.GONE
            blinkIndicator.visibility = android.view.View.GONE
            stopAnimation()
        }

        private fun showSpeakRequest(language: String, blink: Boolean) {
            rootLayout.setBackgroundColor(Color.parseColor("#2D1A1A"))
            tvMainText.text = getSpeakMessage(language)
            tvMainText.setTextColor(Color.parseColor("#FF6B6B"))
            tvMainText.textSize = 28f
            tvSubText.text = getWaitingMessage(language)
            tvSubText.visibility = android.view.View.VISIBLE

            if (blink) {
                blinkIndicator.visibility = android.view.View.VISIBLE
                startBlinkAnimation()
            } else {
                blinkIndicator.visibility = android.view.View.GONE
                stopAnimation()
            }
        }

        private fun getSpeakMessage(lang: String): String = when (lang) {
            "cs" -> "Prosim mluv\ncesky"
            "en" -> "Please speak\nin English"
            "de" -> "Bitte sprechen\nSie Deutsch"
            "es" -> "Por favor hable\nen espanol"
            "fr" -> "Veuillez parler\nen francais"
            else -> "Please speak"
        }

        private fun getWaitingMessage(lang: String): String = when (lang) {
            "cs" -> "Cekam na hlas..."
            "en" -> "Waiting..."
            "de" -> "Warte..."
            else -> "..."
        }

        private fun startBlinkAnimation() {
            stopAnimation()
            animator = android.animation.ObjectAnimator.ofFloat(blinkIndicator, "alpha", 1f, 0.2f).apply {
                duration = 1000
                repeatCount = android.animation.ValueAnimator.INFINITE
                repeatMode = android.animation.ValueAnimator.REVERSE
                start()
            }
        }

        private fun stopAnimation() {
            animator?.cancel()
            animator = null
        }

        override fun onStop() {
            super.onStop()
            stopAnimation()
        }
    }
}
