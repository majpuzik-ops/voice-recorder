package com.majpuzik.voicerecorder.audio

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class TTSPlayer(private val context: Context) {

    companion object {
        private const val TAG = "TTSPlayer"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var tempFile: File? = null

    fun playBase64Audio(base64Data: String, onComplete: () -> Unit = {}, onError: (String) -> Unit = {}) {
        try {
            // Stop any currently playing audio
            stop()

            // Decode base64 to bytes
            val audioBytes = Base64.decode(base64Data, Base64.DEFAULT)
            Log.d(TAG, "Decoded audio: ${audioBytes.size} bytes")

            // Write to temp file (MediaPlayer needs a file or URL)
            tempFile = File.createTempFile("tts_", ".mp3", context.cacheDir)
            FileOutputStream(tempFile!!).use { fos ->
                fos.write(audioBytes)
            }

            // Create and start MediaPlayer
            mediaPlayer = MediaPlayer().apply {
                setDataSource(tempFile!!.absolutePath)
                setOnCompletionListener {
                    Log.d(TAG, "Playback complete")
                    cleanup()
                    onComplete()
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what, extra=$extra")
                    cleanup()
                    onError("Playback error: $what")
                    true
                }
                prepare()
                start()
            }
            Log.d(TAG, "Started playing TTS audio")

        } catch (e: Exception) {
            Log.e(TAG, "Error playing audio: ${e.message}")
            cleanup()
            onError(e.message ?: "Unknown error")
        }
    }

    fun stop() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.stop()
            }
            it.release()
        }
        mediaPlayer = null
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    private fun cleanup() {
        mediaPlayer?.release()
        mediaPlayer = null
        tempFile?.delete()
        tempFile = null
    }

    fun release() {
        cleanup()
    }
}
