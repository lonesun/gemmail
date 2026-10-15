package com.gemmail.model

enum class VoiceEngineSource {
    GEMINI_AUDIO,
    ANDROID_TTS
}

data class GeminiVoiceResult(
    val transcript: String,
    val audioBytes: ByteArray? = null,
    val audioMimeType: String? = null,
    val preferredSource: VoiceEngineSource =
        if (audioBytes != null && audioBytes.isNotEmpty()) VoiceEngineSource.GEMINI_AUDIO
        else VoiceEngineSource.ANDROID_TTS
)
