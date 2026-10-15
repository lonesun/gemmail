package com.gemmail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gemmail.ui.GemmailScreen
import com.gemmail.ui.GemmailViewModel
import com.gemmail.ui.theme.GemmailTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GemmailTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val vm: GemmailViewModel = viewModel()
                    val uiState by vm.uiState.collectAsState()
                    val playbackState by vm.playbackState.collectAsState()

                    val signInLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.StartActivityForResult()
                    ) { result ->
                        vm.onGoogleSignInResult(result.data)
                    }

                    GemmailScreen(
                        uiState = uiState,
                        playbackState = playbackState,
                        onSignInClick = {
                            signInLauncher.launch(vm.buildGoogleSignInIntent())
                        },
                        onFetchCalendarClick = vm::fetchCalendarEventsIntoJson,
                        onLoadSampleJsonClick = vm::loadSampleCalendarJson,
                        onApiKeyChange = vm::updateApiKey,
                        onPromptChange = vm::updateInstructionPrompt,
                        onJsonPayloadChange = vm::updateJsonPayload,
                        onPreferGeminiAudioChange = vm::updatePreferGeminiAudio,
                        onAutoPlayChange = vm::updateAutoPlayVoice,
                        onPipeToGeminiClick = vm::pipeJsonToGeminiAndSpeak,
                        onReplayVoiceClick = vm::replayLastResponse,
                        onStopVoiceClick = vm::stopVoicePlayback
                    )
                }
            }
        }
    }
}
