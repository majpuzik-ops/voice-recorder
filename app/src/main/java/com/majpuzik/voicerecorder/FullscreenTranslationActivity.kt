package com.majpuzik.voicerecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Fullscreen activity showing translation on entire internal display
 * Designed for showing translation to the other person during conversation
 */
class FullscreenTranslationActivity : AppCompatActivity() {

    companion object {
        const val ACTION_UPDATE = "com.majpuzik.voicerecorder.FULLSCREEN_UPDATE"
    }

    private lateinit var tvTranslation: TextView
    private lateinit var tvOriginal: TextView
    private lateinit var tvPrompt: TextView
    private lateinit var scrollTranslation: ScrollView
    private lateinit var scrollOriginal: ScrollView

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_UPDATE) {
                val mode = intent.getStringExtra("mode") ?: "update"

                if (mode == "close") {
                    finish()
                    return
                }

                val translation = intent.getStringExtra("translation") ?: ""
                val original = intent.getStringExtra("original") ?: ""
                val prompt = intent.getStringExtra("prompt") ?: ""
                val showPrompt = intent.getBooleanExtra("show_prompt", false)
                val isRecording = intent.getBooleanExtra("is_recording", true)

                updateContent(translation, original, prompt, showPrompt, isRecording)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen, keep screen on
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        setContentView(R.layout.activity_fullscreen_translation)

        initViews()
        registerReceiver(updateReceiver, IntentFilter(ACTION_UPDATE), RECEIVER_NOT_EXPORTED)

        // Initial content from intent
        intent?.let { handleIntent(it) }
    }

    private fun initViews() {
        tvTranslation = findViewById(R.id.tvTranslation)
        tvOriginal = findViewById(R.id.tvOriginal)
        tvPrompt = findViewById(R.id.tvPrompt)
        scrollTranslation = findViewById(R.id.scrollTranslation)
        scrollOriginal = findViewById(R.id.scrollOriginal)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        val translation = intent.getStringExtra("translation") ?: ""
        val original = intent.getStringExtra("original") ?: ""
        val prompt = intent.getStringExtra("prompt") ?: ""
        val showPrompt = intent.getBooleanExtra("show_prompt", false)
        val isRecording = intent.getBooleanExtra("is_recording", true)

        updateContent(translation, original, prompt, showPrompt, isRecording)
    }

    private fun updateContent(translation: String, original: String, prompt: String, showPrompt: Boolean, isRecording: Boolean) {
        // Update translation - main content, large font
        tvTranslation.text = translation.ifEmpty { "..." }
        tvTranslation.textSize = if (translation.length > 200) 28f else 36f

        // Update original - smaller, at bottom
        tvOriginal.text = original.ifEmpty { "" }
        tvOriginal.visibility = if (original.isNotEmpty()) View.VISIBLE else View.GONE

        // Show prompt when other person should speak
        if (showPrompt && translation.isEmpty()) {
            tvPrompt.text = prompt
            tvPrompt.visibility = View.VISIBLE
            tvTranslation.visibility = View.GONE
        } else {
            tvPrompt.visibility = View.GONE
            tvTranslation.visibility = View.VISIBLE
        }

        // Auto-scroll
        scrollTranslation.post { scrollTranslation.fullScroll(View.FOCUS_DOWN) }
        scrollOriginal.post { scrollOriginal.fullScroll(View.FOCUS_DOWN) }

        // Visual indicator for recording state
        if (!isRecording) {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(updateReceiver)
        } catch (e: Exception) {
            // Ignore
        }
    }

    override fun onBackPressed() {
        super.onBackPressed()
        // Just finish, MainActivity will handle state
        finish()
    }
}
