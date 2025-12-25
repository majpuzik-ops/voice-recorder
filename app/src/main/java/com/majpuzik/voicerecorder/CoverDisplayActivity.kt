package com.majpuzik.voicerecorder

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout

/**
 * Activity displayed on the Samsung Fold cover display
 * Shows translation when user speaks, or "please speak" request for external speaker
 */
class CoverDisplayActivity : AppCompatActivity() {

    companion object {
        const val ACTION_UPDATE_CONTENT = "com.majpuzik.voicerecorder.COVER_UPDATE"
        const val EXTRA_MODE = "mode"
        const val EXTRA_TEXT = "text"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_BLINK = "blink"

        const val MODE_TRANSLATION = "translation"
        const val MODE_SPEAK_REQUEST = "speak_request"
    }

    private lateinit var rootLayout: ConstraintLayout
    private lateinit var tvMainText: TextView
    private lateinit var tvSubText: TextView
    private lateinit var blinkIndicator: View

    private var blinkAnimator: ObjectAnimator? = null
    private val handler = Handler(Looper.getMainLooper())

    private val contentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_UPDATE_CONTENT) {
                val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_TRANSLATION

                // Handle close mode
                if (mode == "close") {
                    finish()
                    return
                }

                val text = intent.getStringExtra(EXTRA_TEXT) ?: ""
                val language = intent.getStringExtra(EXTRA_LANGUAGE) ?: "en"
                val blink = intent.getBooleanExtra(EXTRA_BLINK, false)
                val showPrompt = intent.getBooleanExtra("show_prompt", false)
                val prompt = intent.getStringExtra("prompt") ?: ""

                // If other person should speak, show speak request
                if (showPrompt && text.isEmpty()) {
                    showSpeakRequest(language, true)
                } else {
                    updateContent(mode, text, language, blink)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full screen, keep screen on
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        setContentView(R.layout.activity_cover_display)

        initViews()
        registerReceiver(contentReceiver, IntentFilter(ACTION_UPDATE_CONTENT), RECEIVER_NOT_EXPORTED)

        // Initial state from intent
        intent?.let { handleIntent(it) }
    }

    private fun initViews() {
        rootLayout = findViewById(R.id.rootLayout)
        tvMainText = findViewById(R.id.tvMainText)
        tvSubText = findViewById(R.id.tvSubText)
        blinkIndicator = findViewById(R.id.blinkIndicator)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_TRANSLATION
        // Support both "text" and "translation" extras
        val text = intent.getStringExtra(EXTRA_TEXT)
            ?: intent.getStringExtra("translation")
            ?: ""
        val language = intent.getStringExtra(EXTRA_LANGUAGE) ?: "en"
        val blink = intent.getBooleanExtra(EXTRA_BLINK, false)
        val showPrompt = intent.getBooleanExtra("show_prompt", false)

        // If other person should speak and no text yet, show speak request
        if (showPrompt && text.isEmpty()) {
            showSpeakRequest(language, true)
        } else {
            updateContent(mode, text, language, blink)
        }
    }

    private fun updateContent(mode: String, text: String, language: String, blink: Boolean) {
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

        tvSubText.visibility = View.GONE
        blinkIndicator.visibility = View.GONE

        stopBlinking()
    }

    private fun showSpeakRequest(language: String, blink: Boolean) {
        // Dark red background to indicate listening mode
        rootLayout.setBackgroundColor(Color.parseColor("#2D1A1A"))

        tvMainText.text = getSpeakRequestMessage(language)
        tvMainText.setTextColor(Color.parseColor("#FF6B6B"))
        tvMainText.textSize = 28f

        tvSubText.text = getSubtitle(language)
        tvSubText.setTextColor(Color.parseColor("#888888"))
        tvSubText.visibility = View.VISIBLE

        if (blink) {
            blinkIndicator.visibility = View.VISIBLE
            startBlinking()
        } else {
            blinkIndicator.visibility = View.GONE
            stopBlinking()
        }
    }

    private fun getSpeakRequestMessage(language: String): String {
        return when (language) {
            "cs" -> "Prosim mluv cesky"
            "en" -> "Please speak\nin English"
            "de" -> "Bitte sprechen Sie\nDeutsch"
            "es" -> "Por favor hable\nen espanol"
            "fr" -> "Veuillez parler\nen francais"
            "it" -> "Per favore parla\nin italiano"
            "pl" -> "Prosze mowic\npo polsku"
            "ru" -> "Пожалуйста,\nговорите по-русски"
            "uk" -> "Будь ласка,\nговоріть українською"
            "zh" -> "请说中文"
            "ja" -> "日本語で\n話してください"
            "ko" -> "한국어로\n말씀해 주세요"
            else -> "Please speak in\ntranslated language"
        }
    }

    private fun getSubtitle(language: String): String {
        return when (language) {
            "cs" -> "Cekam na vas hlas..."
            "en" -> "Waiting for your voice..."
            "de" -> "Warte auf Ihre Stimme..."
            "es" -> "Esperando tu voz..."
            "fr" -> "J'attends votre voix..."
            else -> "Waiting..."
        }
    }

    private fun startBlinking() {
        stopBlinking()

        blinkAnimator = ObjectAnimator.ofFloat(blinkIndicator, "alpha", 1f, 0.2f).apply {
            duration = 1000
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopBlinking() {
        blinkAnimator?.cancel()
        blinkAnimator = null
        blinkIndicator.alpha = 1f
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBlinking()
        try {
            unregisterReceiver(contentReceiver)
        } catch (e: Exception) {
            // Ignore
        }
    }
}
