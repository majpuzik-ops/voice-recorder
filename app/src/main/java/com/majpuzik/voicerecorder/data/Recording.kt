package com.majpuzik.voicerecorder.data

import java.io.File
import java.util.UUID

data class Recording(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val filePath: String,
    val duration: Long = 0,  // milliseconds
    val timestamp: Long = System.currentTimeMillis(),
    val originalText: String = "",
    val translatedText: String = "",
    val targetLanguage: String = "en",
    val userId: String = "",
    val syncedToServer: Boolean = false
) {
    val file: File get() = File(filePath)
    val exists: Boolean get() = file.exists()
    val durationFormatted: String get() {
        val seconds = (duration / 1000) % 60
        val minutes = (duration / 1000 / 60) % 60
        val hours = (duration / 1000 / 60 / 60)
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}

data class TranscriptionResult(
    val recordingId: String,
    val text: String,
    val isFinal: Boolean = false,
    val language: String = "cs"
)

data class TranslationResult(
    val recordingId: String,
    val originalText: String,
    val translatedText: String,
    val targetLanguage: String
)

data class ServerMessage(
    val type: String,  // "transcription", "translation", "error", "status"
    val data: Any? = null,
    val text: String? = null,  // Server sends "text" for translations
    val recordingId: String? = null,
    val error: String? = null
)
