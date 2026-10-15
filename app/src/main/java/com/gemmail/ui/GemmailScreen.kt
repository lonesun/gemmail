package com.gemmail.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.gemmail.audio.PlaybackState
import com.gemmail.model.VoiceEngineSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GemmailScreen(
    uiState: GemmailUiState,
    playbackState: PlaybackState,
    onSignInClick: () -> Unit,
    onFetchCalendarClick: () -> Unit,
    onLoadSampleJsonClick: () -> Unit,
    onApiKeyChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onJsonPayloadChange: (String) -> Unit,
    onPreferGeminiAudioChange: (Boolean) -> Unit,
    onAutoPlayChange: (Boolean) -> Unit,
    onPipeToGeminiClick: () -> Unit,
    onReplayVoiceClick: (Boolean) -> Unit,
    onStopVoiceClick: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gemmail • JSON to Gemini Voice") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StatusBanner(uiState = uiState, playbackState = playbackState)

            CalendarSourceCard(
                signedInEmail = uiState.signedInAccountEmail,
                isLoading = uiState.isLoadingCalendar,
                onSignInClick = onSignInClick,
                onFetchCalendarClick = onFetchCalendarClick,
                onLoadSampleJsonClick = onLoadSampleJsonClick
            )

            JsonPipelineCard(
                apiKey = uiState.apiKey,
                instructionPrompt = uiState.instructionPrompt,
                jsonPayload = uiState.jsonPayload,
                preferGeminiAudio = uiState.preferGeminiAudio,
                autoPlayVoice = uiState.autoPlayVoice,
                isCallingGemini = uiState.isCallingGemini,
                onApiKeyChange = onApiKeyChange,
                onPromptChange = onPromptChange,
                onJsonPayloadChange = onJsonPayloadChange,
                onPreferGeminiAudioChange = onPreferGeminiAudioChange,
                onAutoPlayChange = onAutoPlayChange,
                onPipeToGeminiClick = onPipeToGeminiClick
            )

            VoiceOutputCard(
                uiState = uiState,
                playbackState = playbackState,
                onReplayVoiceClick = onReplayVoiceClick,
                onStopVoiceClick = onStopVoiceClick
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun StatusBanner(
    uiState: GemmailUiState,
    playbackState: PlaybackState
) {
    val playbackLabel = when (playbackState) {
        is PlaybackState.Idle -> "Voice: Idle"
        is PlaybackState.Speaking ->
            if (playbackState.source == VoiceEngineSource.GEMINI_AUDIO) {
                "Voice: Playing Gemini Audio"
            } else {
                "Voice: Speaking via Android TTS"
            }
        is PlaybackState.Error -> "Voice Error: ${playbackState.message}"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = playbackLabel,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun CalendarSourceCard(
    signedInEmail: String?,
    isLoading: Boolean,
    onSignInClick: () -> Unit,
    onFetchCalendarClick: () -> Unit,
    onLoadSampleJsonClick: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "1. Google Calendar Source (Official SDK)",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = signedInEmail?.let { "Connected: $it" }
                    ?: "Not signed in. Connect Google Calendar or use sample JSON.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onSignInClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (signedInEmail == null) "Google Sign-In" else "Switch Account")
                }
                OutlinedButton(
                    onClick = onFetchCalendarClick,
                    enabled = !isLoading && signedInEmail != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isLoading) "Loading..." else "Fetch Events JSON")
                }
            }
            OutlinedButton(
                onClick = onLoadSampleJsonClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Load Sample Calendar JSON")
            }
        }
    }
}

@Composable
private fun JsonPipelineCard(
    apiKey: String,
    instructionPrompt: String,
    jsonPayload: String,
    preferGeminiAudio: Boolean,
    autoPlayVoice: Boolean,
    isCallingGemini: Boolean,
    onApiKeyChange: (String) -> Unit,
    onPromptChange: (String) -> Unit,
    onJsonPayloadChange: (String) -> Unit,
    onPreferGeminiAudioChange: (Boolean) -> Unit,
    onAutoPlayChange: (Boolean) -> Unit,
    onPipeToGeminiClick: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "2. Pipe JSON to Gemini SDK",
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChange,
                label = { Text("Gemini API Key") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = instructionPrompt,
                onValueChange = onPromptChange,
                label = { Text("Voice Briefing Instruction") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = jsonPayload,
                onValueChange = onJsonPayloadChange,
                label = { Text("JSON Payload") },
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                minLines = 6,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Request Gemini Native Audio (TTS fallback)",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = preferGeminiAudio,
                    onCheckedChange = onPreferGeminiAudioChange
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Auto-play voice when response arrives",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = autoPlayVoice,
                    onCheckedChange = onAutoPlayChange
                )
            }

            Button(
                onClick = onPipeToGeminiClick,
                enabled = !isCallingGemini,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isCallingGemini) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Pipe JSON to Gemini & Speak")
                }
            }
        }
    }
}

@Composable
private fun VoiceOutputCard(
    uiState: GemmailUiState,
    playbackState: PlaybackState,
    onReplayVoiceClick: (Boolean) -> Unit,
    onStopVoiceClick: () -> Unit
) {
    val result = uiState.lastVoiceResult
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "3. Gemini Spoken Briefing & Playback",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = result?.transcript
                    ?: "No briefing generated yet. Pipe a JSON payload above to hear Gemini speak.",
                style = MaterialTheme.typography.bodyMedium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onReplayVoiceClick(false) },
                    enabled = result != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Play Voice")
                }
                OutlinedButton(
                    onClick = { onReplayVoiceClick(true) },
                    enabled = result != null,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Android TTS")
                }
                OutlinedButton(
                    onClick = onStopVoiceClick,
                    enabled = playbackState is PlaybackState.Speaking,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Stop")
                }
            }
        }
    }
}
