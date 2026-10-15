package com.gemmail.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gemmail.audio.PlaybackState
import com.gemmail.audio.VoicePlaybackManager
import com.gemmail.data.calendar.GoogleCalendarRepository
import com.gemmail.data.gemini.GeminiRepository
import com.gemmail.model.CalendarEventItem
import com.gemmail.model.GeminiVoiceResult
import com.gemmail.model.toJsonPayload
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.calendar.CalendarScopes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class GemmailUiState(
    val signedInAccountEmail: String? = null,
    val apiKey: String = "",
    val instructionPrompt: String =
        "Summarize this JSON payload into a warm, natural spoken briefing for me.",
    val jsonPayload: String = SAMPLE_CALENDAR_JSON,
    val preferGeminiAudio: Boolean = true,
    val autoPlayVoice: Boolean = true,
    val isLoadingCalendar: Boolean = false,
    val isCallingGemini: Boolean = false,
    val calendarEvents: List<CalendarEventItem> = emptyList(),
    val lastVoiceResult: GeminiVoiceResult? = null,
    val statusMessage: String = "Ready. Load Calendar events or edit the JSON payload, then pipe to Gemini."
) {
    companion object {
        val SAMPLE_CALENDAR_JSON = listOf(
            CalendarEventItem(
                id = "evt-101",
                summary = "Product Architecture Sync",
                description = "Review Gemini JSON-to-voice pipeline",
                location = "Meet / Room 4B",
                startIso = "2026-10-15T09:30:00-04:00",
                endIso = "2026-10-15T10:15:00-04:00"
            ),
            CalendarEventItem(
                id = "evt-102",
                summary = "Lunch with Alex",
                description = "Catch up at Blue Harbor Cafe",
                location = "Downtown",
                startIso = "2026-10-15T12:30:00-04:00",
                endIso = "2026-10-15T13:30:00-04:00"
            )
        ).toJsonPayload(source = "sample_google_calendar")
    }
}

class GemmailViewModel(application: Application) : AndroidViewModel(application) {
    private val calendarRepository = GoogleCalendarRepository(application)
    private val geminiRepository = GeminiRepository()
    private val voicePlaybackManager = VoicePlaybackManager(application)

    private val _uiState = MutableStateFlow(GemmailUiState())
    val uiState: StateFlow<GemmailUiState> = _uiState.asStateFlow()

    val playbackState: StateFlow<PlaybackState> = voicePlaybackManager.playbackState

    init {
        val existingAccount = GoogleSignIn.getLastSignedInAccount(application)
        if (existingAccount?.email != null) {
            _uiState.update { it.copy(signedInAccountEmail = existingAccount.email) }
        }
    }

    fun buildGoogleSignInIntent(): Intent {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope(CalendarScopes.CALENDAR_READONLY),
                Scope(CalendarScopes.CALENDAR_EVENTS)
            )
            .build()
        return GoogleSignIn.getClient(getApplication<Application>(), gso).signInIntent
    }

    fun onGoogleSignInResult(data: Intent?) {
        runCatching {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.result
            val email = account?.email ?: error("No email on Google account.")
            _uiState.update {
                it.copy(
                    signedInAccountEmail = email,
                    statusMessage = "Signed in as $email. Fetching upcoming calendar events..."
                )
            }
            fetchCalendarEventsIntoJson()
        }.onFailure { err ->
            _uiState.update {
                it.copy(statusMessage = "Google Sign-In failed: ${err.message}")
            }
        }
    }

    fun updateApiKey(value: String) {
        _uiState.update { it.copy(apiKey = value) }
    }

    fun updateInstructionPrompt(value: String) {
        _uiState.update { it.copy(instructionPrompt = value) }
    }

    fun updateJsonPayload(value: String) {
        _uiState.update { it.copy(jsonPayload = value) }
    }

    fun updatePreferGeminiAudio(enabled: Boolean) {
        _uiState.update { it.copy(preferGeminiAudio = enabled) }
    }

    fun updateAutoPlayVoice(enabled: Boolean) {
        _uiState.update { it.copy(autoPlayVoice = enabled) }
    }

    fun loadSampleCalendarJson() {
        _uiState.update {
            it.copy(
                jsonPayload = GemmailUiState.SAMPLE_CALENDAR_JSON,
                statusMessage = "Loaded sample Calendar JSON payload."
            )
        }
    }

    fun fetchCalendarEventsIntoJson() {
        val email = _uiState.value.signedInAccountEmail
        if (email.isNullOrBlank()) {
            _uiState.update {
                it.copy(statusMessage = "Please sign in with a Google account first.")
            }
            return
        }

        _uiState.update {
            it.copy(isLoadingCalendar = true, statusMessage = "Fetching Calendar events via Google Calendar SDK...")
        }

        viewModelScope.launch {
            val result = calendarRepository.fetchUpcomingEvents(accountName = email)
            result.fold(
                onSuccess = { events ->
                    val json = events.toJsonPayload(source = "google_calendar_sdk")
                    _uiState.update {
                        it.copy(
                            isLoadingCalendar = false,
                            calendarEvents = events,
                            jsonPayload = json,
                            statusMessage = "Loaded ${events.size} upcoming Calendar event(s) into JSON pipeline."
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isLoadingCalendar = false,
                            statusMessage = "Calendar SDK error: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    fun pipeJsonToGeminiAndSpeak() {
        val state = _uiState.value
        val key = state.apiKey.trim()
        if (key.isEmpty()) {
            _uiState.update { it.copy(statusMessage = "Please enter your Gemini API key first.") }
            return
        }

        _uiState.update {
            it.copy(
                isCallingGemini = true,
                statusMessage = "Piping JSON to Gemini SDK..."
            )
        }

        viewModelScope.launch {
            val result = geminiRepository.processJsonForVoice(
                apiKey = key,
                jsonPayload = state.jsonPayload,
                instructionPrompt = state.instructionPrompt,
                requestGeminiAudio = state.preferGeminiAudio
            )

            result.fold(
                onSuccess = { voiceResult ->
                    _uiState.update {
                        it.copy(
                            isCallingGemini = false,
                            lastVoiceResult = voiceResult,
                            statusMessage = "Gemini response ready (${voiceResult.preferredSource})."
                        )
                    }
                    if (_uiState.value.autoPlayVoice) {
                        voicePlaybackManager.play(voiceResult)
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isCallingGemini = false,
                            statusMessage = "Gemini error: ${err.message}"
                        )
                    }
                }
            )
        }
    }

    fun replayLastResponse(forceAndroidTts: Boolean = false) {
        val voiceResult = _uiState.value.lastVoiceResult ?: return
        voicePlaybackManager.play(voiceResult, forceAndroidTts = forceAndroidTts)
    }

    fun stopVoicePlayback() {
        voicePlaybackManager.stop()
    }

    override fun onCleared() {
        super.onCleared()
        voicePlaybackManager.shutdown()
    }
}
