package com.majpuzik.voicerecorder

import android.media.MediaPlayer
import android.os.Bundle
import android.view.MenuItem
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.majpuzik.voicerecorder.data.AppSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingDetailActivity : AppCompatActivity() {

    private lateinit var tvName: TextView
    private lateinit var tvDate: TextView
    private lateinit var tvDuration: TextView
    private lateinit var tvOriginalText: TextView
    private lateinit var tvTranslatedText: TextView
    private lateinit var tvTranslationLabel: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var btnRename: Button
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalTime: TextView

    private var recordingId: String = ""
    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recording_detail)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Detail nahravky"

        settings = AppSettings(this)
        recordingId = intent.getStringExtra("recording_id") ?: run {
            finish()
            return
        }

        initViews()
        loadRecording()
        setupListeners()
    }

    private fun initViews() {
        tvName = findViewById(R.id.tvName)
        tvDate = findViewById(R.id.tvDate)
        tvDuration = findViewById(R.id.tvDuration)
        tvOriginalText = findViewById(R.id.tvOriginalText)
        tvTranslatedText = findViewById(R.id.tvTranslatedText)
        tvTranslationLabel = findViewById(R.id.tvTranslationLabel)
        btnPlay = findViewById(R.id.btnPlay)
        btnRename = findViewById(R.id.btnRename)
        seekBar = findViewById(R.id.seekBar)
        tvCurrentTime = findViewById(R.id.tvCurrentTime)
        tvTotalTime = findViewById(R.id.tvTotalTime)
    }

    private fun loadRecording() {
        val prefs = getSharedPreferences("recordings", MODE_PRIVATE)

        val name = prefs.getString("${recordingId}_name", "") ?: ""
        val path = prefs.getString("${recordingId}_path", "") ?: ""
        val duration = prefs.getLong("${recordingId}_duration", 0)
        val timestamp = prefs.getLong("${recordingId}_timestamp", 0)
        val original = prefs.getString("${recordingId}_original", "") ?: ""
        val translated = prefs.getString("${recordingId}_translated", "") ?: ""
        val language = prefs.getString("${recordingId}_language", "en") ?: "en"

        tvName.text = name

        val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault())
        tvDate.text = dateFormat.format(Date(timestamp))

        tvDuration.text = formatTime(duration)
        tvTotalTime.text = formatTime(duration)

        tvOriginalText.text = if (original.isNotEmpty()) original else "Zadny prepis"
        tvTranslatedText.text = if (translated.isNotEmpty()) translated else "Zadny preklad"

        val langName = settings.availableLanguages[language] ?: language
        tvTranslationLabel.text = "PREKLAD ($langName)"

        // Initialize media player
        if (File(path).exists()) {
            try {
                mediaPlayer = MediaPlayer().apply {
                    setDataSource(path)
                    prepare()
                }
                seekBar.max = mediaPlayer?.duration ?: 0
            } catch (e: Exception) {
                Toast.makeText(this, "Chyba nacitani audio: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupListeners() {
        btnPlay.setOnClickListener {
            togglePlayback()
        }

        btnRename.setOnClickListener {
            showRenameDialog()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    mediaPlayer?.seekTo(progress)
                    tvCurrentTime.text = formatTime(progress.toLong())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        mediaPlayer?.setOnCompletionListener {
            isPlaying = false
            btnPlay.setImageResource(android.R.drawable.ic_media_play)
            seekBar.progress = 0
            tvCurrentTime.text = "00:00"
        }
    }

    private fun togglePlayback() {
        if (isPlaying) {
            mediaPlayer?.pause()
            btnPlay.setImageResource(android.R.drawable.ic_media_play)
            isPlaying = false
        } else {
            mediaPlayer?.start()
            btnPlay.setImageResource(android.R.drawable.ic_media_pause)
            isPlaying = true
            updateSeekBar()
        }
    }

    private fun updateSeekBar() {
        if (isPlaying) {
            mediaPlayer?.let { player ->
                seekBar.progress = player.currentPosition
                tvCurrentTime.text = formatTime(player.currentPosition.toLong())
            }
            seekBar.postDelayed({ updateSeekBar() }, 100)
        }
    }

    private fun showRenameDialog() {
        val prefs = getSharedPreferences("recordings", MODE_PRIVATE)
        val currentName = prefs.getString("${recordingId}_name", "") ?: ""

        val editText = EditText(this).apply {
            setText(currentName)
            setSelection(currentName.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Prejmenovat nahravku")
            .setView(editText)
            .setPositiveButton("Ulozit") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty()) {
                    prefs.edit().putString("${recordingId}_name", newName).apply()
                    tvName.text = newName
                    Toast.makeText(this, "Prejmenovano", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Zrusit", null)
            .show()
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaPlayer?.release()
    }
}
