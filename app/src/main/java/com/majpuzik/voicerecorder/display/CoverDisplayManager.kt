package com.majpuzik.voicerecorder.display

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import kotlinx.coroutines.*

/**
 * Manages cover display on Samsung Fold devices
 * Shows translation when recording self, shows "please speak" message for external speaker
 * Uses DisplayManager for secondary display detection (compatible with API 26+)
 */
class CoverDisplayManager(
    private val activity: Activity
) {

    companion object {
        private const val TAG = "CoverDisplayManager"
    }

    // Speaker mode
    enum class SpeakerMode {
        SELF,       // User is speaking - show translation on cover
        EXTERNAL    // External person speaking - show "please speak in X language"
    }

    interface CoverDisplayListener {
        fun onCoverDisplayAvailable(available: Boolean)
        fun onSecondaryDisplayChanged(display: Display?)
    }

    private var listener: CoverDisplayListener? = null
    private var currentSpeakerMode: SpeakerMode = SpeakerMode.SELF
    private var currentTranslation: String = ""
    private var targetLanguage: String = "en"
    private var secondaryDisplay: Display? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // LED blinking state
    private var isBlinking = false
    private var blinkRunnable: Runnable? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            Log.d(TAG, "Display added: $displayId")
            checkForSecondaryDisplay()
        }

        override fun onDisplayRemoved(displayId: Int) {
            Log.d(TAG, "Display removed: $displayId")
            if (secondaryDisplay?.displayId == displayId) {
                secondaryDisplay = null
                listener?.onSecondaryDisplayChanged(null)
                listener?.onCoverDisplayAvailable(false)
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            Log.d(TAG, "Display changed: $displayId")
        }
    }

    fun initialize() {
        try {
            val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager.registerDisplayListener(displayListener, mainHandler)
            checkForSecondaryDisplay()
            Log.d(TAG, "CoverDisplayManager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CoverDisplayManager: ${e.message}")
        }
    }

    private fun checkForSecondaryDisplay() {
        val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays

        Log.d(TAG, "Found ${displays.size} displays")

        for (display in displays) {
            Log.d(TAG, "Display: ${display.displayId}, name: ${display.name}, isValid: ${display.isValid}")

            if (display.displayId != Display.DEFAULT_DISPLAY && display.isValid) {
                secondaryDisplay = display
                listener?.onSecondaryDisplayChanged(display)
                listener?.onCoverDisplayAvailable(true)
                Log.d(TAG, "Using secondary display: ${display.name}")
                return
            }
        }

        secondaryDisplay = null
        listener?.onCoverDisplayAvailable(false)
    }

    fun setListener(listener: CoverDisplayListener?) {
        this.listener = listener
    }

    /**
     * Set speaker mode - determines what to show on cover display
     */
    fun setSpeakerMode(mode: SpeakerMode) {
        if (currentSpeakerMode != mode) {
            currentSpeakerMode = mode
            Log.d(TAG, "Speaker mode changed to: $mode")

            when (mode) {
                SpeakerMode.EXTERNAL -> {
                    startLedBlinking()
                }
                SpeakerMode.SELF -> {
                    stopLedBlinking()
                }
            }
        }
    }

    /**
     * Update current translation text
     */
    fun updateTranslation(text: String) {
        currentTranslation = text
    }

    /**
     * Set target language for "please speak" message
     */
    fun setTargetLanguage(language: String) {
        targetLanguage = language
    }

    /**
     * Get current display content based on speaker mode
     */
    fun getCurrentDisplayContent(): DisplayContent {
        return when (currentSpeakerMode) {
            SpeakerMode.SELF -> DisplayContent(
                type = DisplayContentType.TRANSLATION,
                text = currentTranslation,
                backgroundColor = 0xFF1A1A1A.toInt()
            )
            SpeakerMode.EXTERNAL -> DisplayContent(
                type = DisplayContentType.SPEAK_REQUEST,
                text = getSpeakRequestMessage(),
                backgroundColor = 0xFF2D1A1A.toInt() // Slight red tint
            )
        }
    }

    private fun getSpeakRequestMessage(): String {
        return when (targetLanguage) {
            "cs" -> "Prosim mluv cesky"
            "en" -> "Please speak in English"
            "de" -> "Bitte sprechen Sie Deutsch"
            "es" -> "Por favor hable en espanol"
            "fr" -> "Veuillez parler en francais"
            "it" -> "Per favore parla in italiano"
            "pl" -> "Prosze mowic po polsku"
            "ru" -> "Пожалуйста, говорите по-русски"
            "uk" -> "Будь ласка, говоріть українською"
            "zh" -> "请说中文"
            "ja" -> "日本語で話してください"
            "ko" -> "한국어로 말씀해 주세요"
            else -> "Please speak in translated language"
        }
    }

    /**
     * Start LED/Edge lighting blinking for external speaker mode
     */
    private fun startLedBlinking() {
        if (isBlinking) return
        isBlinking = true

        blinkRunnable = object : Runnable {
            private var isOn = false

            override fun run() {
                if (!isBlinking) return

                isOn = !isOn
                // LED blinking is handled by notification in CoverDisplayService

                // Slow blink - 1 second interval
                mainHandler.postDelayed(this, 1000)
            }
        }

        mainHandler.post(blinkRunnable!!)
        Log.d(TAG, "LED blinking started")
    }

    private fun stopLedBlinking() {
        isBlinking = false
        blinkRunnable?.let { mainHandler.removeCallbacks(it) }
        blinkRunnable = null
        Log.d(TAG, "LED blinking stopped")
    }

    /**
     * Check if device has cover display (Samsung Fold)
     */
    fun hasCoverDisplay(): Boolean {
        return secondaryDisplay != null
    }

    /**
     * Get secondary display if available
     */
    fun getSecondaryDisplay(): Display? {
        return secondaryDisplay
    }

    fun cleanup() {
        stopLedBlinking()
        try {
            val displayManager = activity.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            displayManager.unregisterDisplayListener(displayListener)
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering display listener: ${e.message}")
        }
        scope.cancel()
        listener = null
    }

    data class DisplayContent(
        val type: DisplayContentType,
        val text: String,
        val backgroundColor: Int
    )

    enum class DisplayContentType {
        TRANSLATION,
        SPEAK_REQUEST
    }
}
