package com.majpuzik.voicerecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Activity for split-screen mode on Samsung Fold.
 * Shows translation for the other person to read.
 */
class SplitDisplayActivity : AppCompatActivity() {

    companion object {
        const val ACTION_UPDATE = "com.majpuzik.voicerecorder.SPLIT_UPDATE"
        const val EXTRA_TRANSLATION = "translation"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_SHOW_PROMPT = "show_prompt"
        const val EXTRA_IS_RECORDING = "is_recording"
    }

    private lateinit var tvTranslation: TextView
    private lateinit var tvPrompt: TextView
    private lateinit var ivRecIndicator: ImageView
    private lateinit var tvRecStatus: TextView

    private val blinkHandler = Handler(Looper.getMainLooper())
    private var isBlinkOn = true
    private val blinkRunnable = object : Runnable {
        override fun run() {
            isBlinkOn = !isBlinkOn
            val alpha = if (isBlinkOn) 1f else 0.3f
            ivRecIndicator.alpha = alpha
            tvRecStatus.alpha = alpha
            blinkHandler.postDelayed(this, 750)
        }
    }

    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UPDATE) {
                val translation = intent.getStringExtra(EXTRA_TRANSLATION) ?: ""
                val prompt = intent.getStringExtra(EXTRA_PROMPT) ?: ""
                val showPrompt = intent.getBooleanExtra(EXTRA_SHOW_PROMPT, false)
                val isRecording = intent.getBooleanExtra(EXTRA_IS_RECORDING, false)

                updateUI(translation, prompt, showPrompt, isRecording)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_split_display)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        tvTranslation = findViewById(R.id.tvSplitTranslation)
        tvPrompt = findViewById(R.id.tvSplitPrompt)
        ivRecIndicator = findViewById(R.id.ivSplitRecIndicator)
        tvRecStatus = findViewById(R.id.tvSplitRecStatus)

        // Register receiver
        val filter = IntentFilter(ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(updateReceiver, filter)
        }

        // Get initial data from intent
        intent?.let {
            val translation = it.getStringExtra(EXTRA_TRANSLATION) ?: ""
            val prompt = it.getStringExtra(EXTRA_PROMPT) ?: ""
            val showPrompt = it.getBooleanExtra(EXTRA_SHOW_PROMPT, false)
            val isRecording = it.getBooleanExtra(EXTRA_IS_RECORDING, false)
            updateUI(translation, prompt, showPrompt, isRecording)
        }
    }

    private fun updateUI(translation: String, prompt: String, showPrompt: Boolean, isRecording: Boolean) {
        runOnUiThread {
            tvTranslation.text = if (translation.isNotEmpty()) translation else "..."

            if (showPrompt && prompt.isNotEmpty()) {
                tvPrompt.text = prompt
                tvPrompt.visibility = View.VISIBLE
            } else {
                tvPrompt.visibility = View.GONE
            }

            if (isRecording) {
                ivRecIndicator.visibility = View.VISIBLE
                tvRecStatus.visibility = View.VISIBLE
                startBlinking()
            } else {
                ivRecIndicator.visibility = View.GONE
                tvRecStatus.visibility = View.GONE
                stopBlinking()
            }
        }
    }

    private fun startBlinking() {
        blinkHandler.removeCallbacks(blinkRunnable)
        blinkHandler.post(blinkRunnable)
    }

    private fun stopBlinking() {
        blinkHandler.removeCallbacks(blinkRunnable)
        ivRecIndicator.alpha = 1f
        tvRecStatus.alpha = 1f
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBlinking()
        try {
            unregisterReceiver(updateReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }

    override fun onBackPressed() {
        // Minimize instead of closing
        moveTaskToBack(true)
    }
}
