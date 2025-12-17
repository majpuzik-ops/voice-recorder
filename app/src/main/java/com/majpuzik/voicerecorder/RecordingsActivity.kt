package com.majpuzik.voicerecorder

import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.majpuzik.voicerecorder.data.Recording
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class RecordingsActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: RecordingsAdapter
    private var recordings = mutableListOf<Recording>()
    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recordings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Nahravky"

        recyclerView = findViewById(R.id.recyclerView)
        tvEmpty = findViewById(R.id.tvEmpty)

        adapter = RecordingsAdapter(
            recordings,
            onPlayClick = { recording -> playRecording(recording) },
            onDeleteClick = { recording -> deleteRecording(recording) },
            onItemClick = { recording -> showRecordingDetails(recording) }
        )

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadRecordings()
    }

    private fun loadRecordings() {
        recordings.clear()
        val prefs = getSharedPreferences("recordings", MODE_PRIVATE)
        val recordingIds = prefs.getStringSet("recording_ids", emptySet()) ?: emptySet()

        for (id in recordingIds) {
            val name = prefs.getString("${id}_name", "") ?: ""
            val path = prefs.getString("${id}_path", "") ?: ""
            val duration = prefs.getLong("${id}_duration", 0)
            val timestamp = prefs.getLong("${id}_timestamp", 0)
            val original = prefs.getString("${id}_original", "") ?: ""
            val translated = prefs.getString("${id}_translated", "") ?: ""
            val language = prefs.getString("${id}_language", "en") ?: "en"

            if (File(path).exists()) {
                recordings.add(Recording(
                    id = id,
                    name = name,
                    filePath = path,
                    duration = duration,
                    timestamp = timestamp,
                    originalText = original,
                    translatedText = translated,
                    targetLanguage = language
                ))
            }
        }

        recordings.sortByDescending { it.timestamp }
        adapter.notifyDataSetChanged()

        tvEmpty.visibility = if (recordings.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (recordings.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun playRecording(recording: Recording) {
        if (currentlyPlayingId == recording.id) {
            // Stop playing
            mediaPlayer?.stop()
            mediaPlayer?.release()
            mediaPlayer = null
            currentlyPlayingId = null
            adapter.setPlaying(null)
            return
        }

        // Stop any current playback
        mediaPlayer?.release()

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(recording.filePath)
                prepare()
                start()
                setOnCompletionListener {
                    currentlyPlayingId = null
                    adapter.setPlaying(null)
                }
            }
            currentlyPlayingId = recording.id
            adapter.setPlaying(recording.id)
        } catch (e: Exception) {
            Toast.makeText(this, "Chyba prehravani: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteRecording(recording: Recording) {
        AlertDialog.Builder(this)
            .setTitle("Smazat nahravku?")
            .setMessage("Opravdu chcete smazat \"${recording.name}\"?")
            .setPositiveButton("Smazat") { _, _ ->
                // Delete file
                File(recording.filePath).delete()

                // Remove from prefs
                val prefs = getSharedPreferences("recordings", MODE_PRIVATE)
                val ids = prefs.getStringSet("recording_ids", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                ids.remove(recording.id)
                prefs.edit()
                    .putStringSet("recording_ids", ids)
                    .remove("${recording.id}_name")
                    .remove("${recording.id}_path")
                    .remove("${recording.id}_duration")
                    .remove("${recording.id}_timestamp")
                    .remove("${recording.id}_original")
                    .remove("${recording.id}_translated")
                    .remove("${recording.id}_language")
                    .apply()

                loadRecordings()
                Toast.makeText(this, "Nahravka smazana", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Zrusit", null)
            .show()
    }

    private fun showRecordingDetails(recording: Recording) {
        val intent = Intent(this, RecordingDetailActivity::class.java)
        intent.putExtra("recording_id", recording.id)
        startActivity(intent)
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

    inner class RecordingsAdapter(
        private val items: List<Recording>,
        private val onPlayClick: (Recording) -> Unit,
        private val onDeleteClick: (Recording) -> Unit,
        private val onItemClick: (Recording) -> Unit
    ) : RecyclerView.Adapter<RecordingsAdapter.ViewHolder>() {

        private var playingId: String? = null

        fun setPlaying(id: String?) {
            playingId = id
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvDate: TextView = view.findViewById(R.id.tvDate)
            val tvDuration: TextView = view.findViewById(R.id.tvDuration)
            val btnPlay: ImageButton = view.findViewById(R.id.btnPlay)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_recording, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val recording = items[position]
            holder.tvName.text = recording.name
            holder.tvDuration.text = recording.durationFormatted

            val dateFormat = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
            holder.tvDate.text = dateFormat.format(Date(recording.timestamp))

            val isPlaying = playingId == recording.id
            holder.btnPlay.setImageResource(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play
            )

            holder.btnPlay.setOnClickListener { onPlayClick(recording) }
            holder.btnDelete.setOnClickListener { onDeleteClick(recording) }
            holder.itemView.setOnClickListener { onItemClick(recording) }
        }

        override fun getItemCount() = items.size
    }
}
