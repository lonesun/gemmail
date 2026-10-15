package com.gemmail.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.gemmail.model.GeminiVoiceResult
import com.gemmail.model.VoiceEngineSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

sealed interface PlaybackState {
    data object Idle : PlaybackState
    data class Speaking(val source: VoiceEngineSource) : PlaybackState
    data class Error(val message: String) : PlaybackState
}

/**
 * Coordinates voice playback for Gemini responses:
 * - Plays inline audio bytes returned by Gemini (`audio/pcm` / `audio/L16` via [AudioTrack], or
 *   containerized formats like WAV/MP3/OGG via [MediaPlayer]).
 * - Automatically falls back to Android's native [TextToSpeech] when Gemini returns text-only
 *   or if inline audio playback fails.
 */
class VoicePlaybackManager(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private var tts: TextToSpeech? = null
    @Volatile
    private var isTtsReady = false

    private var activeAudioTrack: AudioTrack? = null
    private var activeMediaPlayer: MediaPlayer? = null
    private var pcmPlaybackJob: Job? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val langResult = tts?.setLanguage(Locale.getDefault())
                isTtsReady = langResult != TextToSpeech.LANG_MISSING_DATA &&
                    langResult != TextToSpeech.LANG_NOT_SUPPORTED
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _playbackState.value = PlaybackState.Speaking(VoiceEngineSource.ANDROID_TTS)
                    }

                    override fun onDone(utteranceId: String?) {
                        _playbackState.value = PlaybackState.Idle
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _playbackState.value = PlaybackState.Error("Android TTS playback error.")
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        _playbackState.value =
                            PlaybackState.Error("Android TTS error (code $errorCode).")
                    }
                })
            }
        }
    }

    fun play(result: GeminiVoiceResult, forceAndroidTts: Boolean = false) {
        stop()

        val audioBytes = result.audioBytes
        if (!forceAndroidTts && audioBytes != null && audioBytes.isNotEmpty()) {
            val mime = result.audioMimeType.orEmpty().lowercase(Locale.ROOT)
            if (mime.contains("pcm") || mime.contains("l16") || mime.isEmpty()) {
                val sampleRate = parseSampleRate(mime) ?: DEFAULT_GEMINI_PCM_SAMPLE_RATE
                playRawPcm(audioBytes, sampleRate, fallbackText = result.transcript)
            } else {
                playEncodedAudio(audioBytes, mime, fallbackText = result.transcript)
            }
        } else {
            speakWithAndroidTts(result.transcript)
        }
    }

    fun speakWithAndroidTts(text: String) {
        stop()
        val cleanText = text.trim()
        if (cleanText.isEmpty()) return

        val engine = tts
        if (!isTtsReady || engine == null) {
            _playbackState.value = PlaybackState.Error("Android TextToSpeech is not ready on this device.")
            return
        }

        val utteranceId = UUID.randomUUID().toString()
        _playbackState.value = PlaybackState.Speaking(VoiceEngineSource.ANDROID_TTS)
        val res = engine.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (res == TextToSpeech.ERROR) {
            _playbackState.value = PlaybackState.Error("Failed to start Android TextToSpeech.")
        }
    }

    fun stop() {
        pcmPlaybackJob?.cancel()
        pcmPlaybackJob = null

        runCatching {
            activeAudioTrack?.pause()
            activeAudioTrack?.flush()
            activeAudioTrack?.release()
        }
        activeAudioTrack = null

        runCatching {
            activeMediaPlayer?.stop()
            activeMediaPlayer?.release()
        }
        activeMediaPlayer = null

        runCatching { tts?.stop() }
        _playbackState.value = PlaybackState.Idle
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
    }

    private fun playRawPcm(pcmBytes: ByteArray, sampleRate: Int, fallbackText: String) {
        pcmPlaybackJob = scope.launch {
            runCatching {
                val minBuffer = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(4096)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuffer)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                activeAudioTrack = track
                _playbackState.value = PlaybackState.Speaking(VoiceEngineSource.GEMINI_AUDIO)
                track.play()

                var offset = 0
                val chunkSize = 4096
                while (isActive && offset < pcmBytes.size) {
                    val toWrite = minOf(chunkSize, pcmBytes.size - offset)
                    val written = track.write(pcmBytes, offset, toWrite)
                    if (written <= 0) break
                    offset += written
                }

                track.stop()
                track.release()
                if (activeAudioTrack === track) {
                    activeAudioTrack = null
                }
                if (isActive) {
                    _playbackState.value = PlaybackState.Idle
                }
            }.onFailure {
                speakWithAndroidTts(fallbackText)
            }
        }
    }

    private fun playEncodedAudio(audioBytes: ByteArray, mimeType: String, fallbackText: String) {
        runCatching {
            val ext = when {
                mimeType.contains("wav") -> ".wav"
                mimeType.contains("ogg") -> ".ogg"
                else -> ".mp3"
            }
            val tempFile = File.createTempFile("gemini_voice_", ext, appContext.cacheDir)
            FileOutputStream(tempFile).use { it.write(audioBytes) }

            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(tempFile.absolutePath)
                setOnCompletionListener { mp ->
                    mp.release()
                    tempFile.delete()
                    if (activeMediaPlayer === mp) activeMediaPlayer = null
                    _playbackState.value = PlaybackState.Idle
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release()
                    tempFile.delete()
                    if (activeMediaPlayer === mp) activeMediaPlayer = null
                    speakWithAndroidTts(fallbackText)
                    true
                }
                prepare()
                start()
            }
            activeMediaPlayer = player
            _playbackState.value = PlaybackState.Speaking(VoiceEngineSource.GEMINI_AUDIO)
        }.onFailure {
            speakWithAndroidTts(fallbackText)
        }
    }

    private fun parseSampleRate(mimeType: String): Int? {
        val match = Regex("rate=(\\d+)").find(mimeType) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()
    }

    companion object {
        private const val DEFAULT_GEMINI_PCM_SAMPLE_RATE = 24_000
    }
}
