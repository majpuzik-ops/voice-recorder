package com.majpuzik.voicerecorder.data

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

class AppSettings(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("voice_recorder_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TARGET_LANGUAGE = "target_language"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_AUTO_TRANSCRIBE = "auto_transcribe"
        private const val KEY_AUTO_TRANSLATE = "auto_translate"
        private const val KEY_WHISPER_MODEL = "whisper_model"

        private const val DEFAULT_SERVER_URL = "ws://100.96.204.120:8765"  // Tailscale IP
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var targetLanguage: String
        get() = prefs.getString(KEY_TARGET_LANGUAGE, "en") ?: "en"
        set(value) = prefs.edit().putString(KEY_TARGET_LANGUAGE, value).apply()

    var userId: String
        get() {
            var id = prefs.getString(KEY_USER_ID, null)
            if (id == null) {
                id = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_USER_ID, id).apply()
            }
            return id
        }
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    var autoTranscribe: Boolean
        get() = prefs.getBoolean(KEY_AUTO_TRANSCRIBE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_TRANSCRIBE, value).apply()

    var autoTranslate: Boolean
        get() = prefs.getBoolean(KEY_AUTO_TRANSLATE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_TRANSLATE, value).apply()

    var whisperModel: String
        get() = prefs.getString(KEY_WHISPER_MODEL, "whisper-large-v3") ?: "whisper-large-v3"
        set(value) = prefs.edit().putString(KEY_WHISPER_MODEL, value).apply()

    val availableLanguages = mapOf(
        "en" to "Anglictina",
        "de" to "Nemcina",
        "fr" to "Francouzstina",
        "es" to "Spanelstina",
        "it" to "Italstina",
        "pl" to "Polstina",
        "ru" to "Rustina",
        "uk" to "Ukrajinstina",
        "sk" to "Slovenstina",
        "cs" to "Cestina (original)"
    )
}
