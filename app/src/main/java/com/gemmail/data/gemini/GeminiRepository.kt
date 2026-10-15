package com.gemmail.data.gemini

import android.util.Base64
import com.gemmail.model.GeminiVoiceResult
import com.gemmail.model.VoiceEngineSource
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GeminiRepository {

    /**
     * Pipes a JSON payload and instruction prompt to Gemini.
     *
     * 1. Uses the official Google AI Android SDK ([GenerativeModel]) to generate a
     *    spoken-friendly script/response from the structured JSON input.
     * 2. When [requestGeminiAudio] is enabled, also requests inline audio synthesis from
     *    Gemini (`responseModalities: ["AUDIO"]` with `speechConfig`) so the app can play
     *    Gemini's native voice directly, while preserving the transcript for UI display and
     *    automatic fallback to Android's on-device TextToSpeech if audio bytes are unavailable.
     */
    suspend fun processJsonForVoice(
        apiKey: String,
        jsonPayload: String,
        instructionPrompt: String,
        modelName: String = DEFAULT_TEXT_MODEL,
        requestGeminiAudio: Boolean = true,
        voiceName: String = DEFAULT_GEMINI_VOICE
    ): Result<GeminiVoiceResult> = withContext(Dispatchers.IO) {
        runCatching {
            val normalizedJson = normalizeJsonPayload(jsonPayload)

            val generativeModel = GenerativeModel(
                modelName = modelName,
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = 0.4f
                },
                systemInstruction = content {
                    text(
                        "You are Gemmail's voice assistant. Convert incoming JSON data into a " +
                            "clear, natural, conversational spoken briefing suitable for voice " +
                            "playback. Avoid reading raw brackets, keys, or markdown symbols."
                    )
                }
            )

            val inputContent = content {
                text(
                    buildString {
                        appendLine(instructionPrompt.trim())
                        appendLine()
                        appendLine("Input JSON payload:")
                        appendLine(normalizedJson)
                    }
                )
            }

            val response = generativeModel.generateContent(inputContent)
            val spokenScript = response.text?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: error("Gemini returned an empty response.")

            if (requestGeminiAudio) {
                val inlineAudio = runCatching {
                    requestGeminiInlineAudio(
                        apiKey = apiKey,
                        textToSpeak = spokenScript,
                        voiceName = voiceName
                    )
                }.getOrNull()

                if (inlineAudio != null) {
                    return@runCatching GeminiVoiceResult(
                        transcript = spokenScript,
                        audioBytes = inlineAudio.first,
                        audioMimeType = inlineAudio.second,
                        preferredSource = VoiceEngineSource.GEMINI_AUDIO
                    )
                }
            }

            GeminiVoiceResult(
                transcript = spokenScript,
                preferredSource = VoiceEngineSource.ANDROID_TTS
            )
        }
    }

    private fun normalizeJsonPayload(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.isNotEmpty()) { "JSON payload cannot be empty." }
        return when {
            trimmed.startsWith("{") -> JSONObject(trimmed).toString(2)
            trimmed.startsWith("[") -> JSONArray(trimmed).toString(2)
            else -> JSONObject().put("data", trimmed).toString(2)
        }
    }

    /**
     * Requests native voice synthesis from Gemini's multimodal audio endpoint.
     * Returns `(audioBytes, mimeType)` if the model produces an `inlineData` audio part.
     */
    private fun requestGeminiInlineAudio(
        apiKey: String,
        textToSpeak: String,
        voiceName: String,
        ttsModel: String = DEFAULT_TTS_MODEL
    ): Pair<ByteArray, String>? {
        val endpoint =
            "https://generativelanguage.googleapis.com/v1beta/models/$ttsModel:generateContent" +
                "?key=${URLEncoder.encode(apiKey, "UTF-8")}"

        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", textToSpeak))
                    )
                )
            )
            put(
                "generationConfig",
                JSONObject().apply {
                    put("responseModalities", JSONArray().put("AUDIO"))
                    put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put(
                                "prebuiltVoiceConfig",
                                JSONObject().put("voiceName", voiceName)
                            )
                        )
                    )
                }
            )
        }

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        }

        val status = conn.responseCode
        if (status !in 200..299) {
            conn.disconnect()
            return null
        }

        val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        conn.disconnect()

        val root = JSONObject(body)
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return null

        for (i in 0 until parts.length()) {
            val inlineData = parts.getJSONObject(i).optJSONObject("inlineData") ?: continue
            val mimeType = inlineData.optString("mimeType", "audio/pcm;rate=24000")
            val b64 = inlineData.optString("data")
            if (b64.isNotEmpty()) {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                if (bytes.isNotEmpty()) {
                    return bytes to mimeType
                }
            }
        }
        return null
    }

    companion object {
        const val DEFAULT_TEXT_MODEL = "gemini-2.5-flash"
        const val DEFAULT_TTS_MODEL = "gemini-2.5-flash-preview-tts"
        const val DEFAULT_GEMINI_VOICE = "Kore"
    }
}
