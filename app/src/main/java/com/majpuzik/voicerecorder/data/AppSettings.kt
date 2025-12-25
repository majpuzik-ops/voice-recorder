package com.majpuzik.voicerecorder.data

import android.content.Context
import android.content.SharedPreferences
import android.media.MediaRecorder
import java.util.UUID

class AppSettings(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("voice_recorder_settings", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_TARGET_LANGUAGE = "target_language"
        private const val KEY_SOURCE_LANGUAGE = "source_language"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_AUTO_TRANSCRIBE = "auto_transcribe"
        private const val KEY_AUTO_TRANSLATE = "auto_translate"
        private const val KEY_WHISPER_MODEL = "whisper_model"
        private const val KEY_AUDIO_SOURCE = "audio_source"
        private const val KEY_LLM_PROVIDER = "llm_provider"
        private const val KEY_LLM_API_KEY = "llm_api_key"
        private const val KEY_TRANSCRIPTION_PROVIDER = "transcription_provider"
        private const val KEY_TRANSCRIPTION_API_KEY = "transcription_api_key"
        private const val KEY_COVER_DISPLAY_ENABLED = "cover_display_enabled"
        private const val KEY_SHOW_RECORDING_INDICATOR = "show_recording_indicator"
        private const val KEY_DISPLAY_MODE = "display_mode"
        private const val KEY_AUTO_CALL_RECORDING = "auto_call_recording"

        // Display mode constants
        const val DISPLAY_MODE_FLOATING = "floating"
        const val DISPLAY_MODE_SPLIT = "split"
        const val DISPLAY_MODE_COVER = "cover"
        const val DISPLAY_MODE_FULLSCREEN = "fullscreen"

        private const val DEFAULT_SERVER_URL = "ws://100.90.154.98:8765"  // MacBook Pro - streaming server

        // Audio source constants
        const val AUDIO_SOURCE_DEFAULT = "default"
        const val AUDIO_SOURCE_MIC = "mic"
        const val AUDIO_SOURCE_CAMCORDER = "camcorder"
        const val AUDIO_SOURCE_VOICE_RECOGNITION = "voice_recognition"
        const val AUDIO_SOURCE_VOICE_COMMUNICATION = "voice_communication"
        const val AUDIO_SOURCE_UNPROCESSED = "unprocessed"
        const val AUDIO_SOURCE_VOICE_CALL = "voice_call"
        const val AUDIO_SOURCE_VOICE_DOWNLINK = "voice_downlink"
        const val AUDIO_SOURCE_VOICE_UPLINK = "voice_uplink"

        // LLM Provider constants
        const val LLM_OLLAMA = "ollama"
        const val LLM_OPENAI = "openai"
        const val LLM_ANTHROPIC = "anthropic"
        const val LLM_GROQ = "groq"

        // Transcription Provider constants
        const val TRANSCRIPTION_LOCAL = "local"
        const val TRANSCRIPTION_OPENAI = "openai_whisper"
        const val TRANSCRIPTION_DEEPGRAM = "deepgram"
        const val TRANSCRIPTION_ASSEMBLYAI = "assemblyai"
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

    var targetLanguage: String
        get() = prefs.getString(KEY_TARGET_LANGUAGE, "en") ?: "en"
        set(value) = prefs.edit().putString(KEY_TARGET_LANGUAGE, value).apply()

    var sourceLanguage: String
        get() = prefs.getString(KEY_SOURCE_LANGUAGE, "cs") ?: "cs"
        set(value) = prefs.edit().putString(KEY_SOURCE_LANGUAGE, value).apply()

    var coverDisplayEnabled: Boolean
        get() = prefs.getBoolean(KEY_COVER_DISPLAY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_COVER_DISPLAY_ENABLED, value).apply()

    var showRecordingIndicator: Boolean
        get() = prefs.getBoolean(KEY_SHOW_RECORDING_INDICATOR, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_RECORDING_INDICATOR, value).apply()

    var displayMode: String
        get() = prefs.getString(KEY_DISPLAY_MODE, DISPLAY_MODE_SPLIT) ?: DISPLAY_MODE_SPLIT
        set(value) = prefs.edit().putString(KEY_DISPLAY_MODE, value).apply()

    var autoCallRecording: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CALL_RECORDING, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CALL_RECORDING, value).apply()

    val availableDisplayModes = mapOf(
        DISPLAY_MODE_FULLSCREEN to "Pres cely vnitrni display",
        DISPLAY_MODE_COVER to "Vnejsi display Fold6",
        DISPLAY_MODE_SPLIT to "Split screen",
        DISPLAY_MODE_FLOATING to "Plovouci okno"
    )

    /**
     * Swap source and target languages
     */
    fun swapLanguages() {
        val temp = sourceLanguage
        sourceLanguage = targetLanguage
        targetLanguage = temp
    }

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

    var audioSource: String
        get() = prefs.getString(KEY_AUDIO_SOURCE, AUDIO_SOURCE_DEFAULT) ?: AUDIO_SOURCE_DEFAULT
        set(value) = prefs.edit().putString(KEY_AUDIO_SOURCE, value).apply()

    var llmProvider: String
        get() = prefs.getString(KEY_LLM_PROVIDER, LLM_OLLAMA) ?: LLM_OLLAMA
        set(value) = prefs.edit().putString(KEY_LLM_PROVIDER, value).apply()

    var llmApiKey: String
        get() = prefs.getString(KEY_LLM_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LLM_API_KEY, value).apply()

    var transcriptionProvider: String
        get() = prefs.getString(KEY_TRANSCRIPTION_PROVIDER, TRANSCRIPTION_LOCAL) ?: TRANSCRIPTION_LOCAL
        set(value) = prefs.edit().putString(KEY_TRANSCRIPTION_PROVIDER, value).apply()

    var transcriptionApiKey: String
        get() = prefs.getString(KEY_TRANSCRIPTION_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TRANSCRIPTION_API_KEY, value).apply()

    val availableAudioSources = mapOf(
        AUDIO_SOURCE_DEFAULT to "Výchozí (auto)",
        AUDIO_SOURCE_MIC to "Interní mikrofon (blízko)",
        AUDIO_SOURCE_VOICE_COMMUNICATION to "Handsfree / Speakerphone",
        AUDIO_SOURCE_VOICE_RECOGNITION to "Rozpoznávání hlasu",
        AUDIO_SOURCE_CAMCORDER to "Kamerový mikrofon",
        AUDIO_SOURCE_UNPROCESSED to "Nezpracovaný (raw)",
        AUDIO_SOURCE_VOICE_CALL to "📞 Telefonní hovor (obě strany)",
        AUDIO_SOURCE_VOICE_DOWNLINK to "📞 Hovor - jen protistrana",
        AUDIO_SOURCE_VOICE_UPLINK to "📞 Hovor - jen já"
    )

    val availableLlmProviders = mapOf(
        LLM_OLLAMA to "Ollama (lokální)",
        LLM_OPENAI to "OpenAI GPT-4",
        LLM_ANTHROPIC to "Anthropic Claude",
        LLM_GROQ to "Groq (rychlý)"
    )

    val availableTranscriptionProviders = mapOf(
        TRANSCRIPTION_LOCAL to "Lokální Whisper",
        TRANSCRIPTION_OPENAI to "OpenAI Whisper API",
        TRANSCRIPTION_DEEPGRAM to "Deepgram (meetingy)",
        TRANSCRIPTION_ASSEMBLYAI to "AssemblyAI (diarizace)"
    )

    fun getAudioSourceInt(): Int {
        return when (audioSource) {
            AUDIO_SOURCE_MIC -> MediaRecorder.AudioSource.MIC
            AUDIO_SOURCE_CAMCORDER -> MediaRecorder.AudioSource.CAMCORDER
            AUDIO_SOURCE_VOICE_RECOGNITION -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            AUDIO_SOURCE_VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            AUDIO_SOURCE_UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
            AUDIO_SOURCE_VOICE_CALL -> MediaRecorder.AudioSource.VOICE_CALL
            AUDIO_SOURCE_VOICE_DOWNLINK -> MediaRecorder.AudioSource.VOICE_DOWNLINK
            AUDIO_SOURCE_VOICE_UPLINK -> MediaRecorder.AudioSource.VOICE_UPLINK
            else -> MediaRecorder.AudioSource.MIC
        }
    }

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
