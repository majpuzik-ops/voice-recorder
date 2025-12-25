package com.majpuzik.voicerecorder

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.TextView
import com.majpuzik.voicerecorder.data.AppSettings

class FloatingTranslationService : Service() {

    companion object {
        const val ACTION_UPDATE = "com.majpuzik.voicerecorder.FLOATING_UPDATE"
        const val EXTRA_TRANSLATION = "translation"
        const val EXTRA_PROMPT = "prompt"
        const val EXTRA_SHOW_PROMPT = "show_prompt"
        const val EXTRA_IS_RECORDING = "is_recording"
    }

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var tvTranslation: TextView
    private lateinit var tvPrompt: TextView
    private lateinit var tvRecIndicator: TextView

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Inflate floating view
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_translation, null)

        tvTranslation = floatingView.findViewById(R.id.tvFloatingTranslation)
        tvPrompt = floatingView.findViewById(R.id.tvFloatingPrompt)
        tvRecIndicator = floatingView.findViewById(R.id.tvFloatingRec)

        // Layout params for overlay
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        params.y = 100

        // Add touch listener for dragging
        floatingView.setOnTouchListener(object : View.OnTouchListener {
            override fun onTouch(v: View?, event: MotionEvent?): Boolean {
                when (event?.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY - (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })

        // Close button
        floatingView.findViewById<View>(R.id.btnCloseFloating).setOnClickListener {
            stopSelf()
        }

        windowManager.addView(floatingView, params)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (it.action == ACTION_UPDATE) {
                val translation = it.getStringExtra(EXTRA_TRANSLATION) ?: ""
                val prompt = it.getStringExtra(EXTRA_PROMPT) ?: ""
                val showPrompt = it.getBooleanExtra(EXTRA_SHOW_PROMPT, false)
                val isRecording = it.getBooleanExtra(EXTRA_IS_RECORDING, false)

                tvTranslation.text = if (translation.isNotEmpty()) translation else "..."

                if (showPrompt && prompt.isNotEmpty()) {
                    tvPrompt.text = prompt
                    tvPrompt.visibility = View.VISIBLE
                } else {
                    tvPrompt.visibility = View.GONE
                }

                tvRecIndicator.visibility = if (isRecording) View.VISIBLE else View.GONE
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            windowManager.removeView(floatingView)
        } catch (e: Exception) {
            // View already removed
        }
    }
}
