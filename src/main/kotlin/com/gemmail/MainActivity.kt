package com.gemmail

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private lateinit var outputView: TextView
    private lateinit var tokenInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val apiKeyInput = EditText(this).apply {
            hint = "Gemini API Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }

        val promptInput = EditText(this).apply {
            hint = "Prompt for Gemini"
            setText("Summarize my day in one friendly sentence.")
        }

        val geminiButton = Button(this).apply {
            text = "Ask Gemini"
            setOnClickListener {
                val key = apiKeyInput.text.toString().trim()
                val prompt = promptInput.text.toString().trim()
                if (key.isEmpty()) {
                    outputView.text = "Please enter a Gemini API key."
                    return@setOnClickListener
                }
                outputView.text = "Calling Gemini..."
                ioExecutor.execute {
                    val result = GoogleApiClient.generateContent(apiKey = key, prompt = prompt)
                    runOnUiThread {
                        outputView.text = result.fold(
                            onSuccess = { it },
                            onFailure = { "Gemini error: ${it.message}" }
                        )
                    }
                }
            }
        }

        tokenInput = EditText(this).apply {
            hint = "Calendar OAuth Bearer Token (or pick account below)"
        }

        val pickAccountButton = Button(this).apply {
            text = "Get Token from Device Google Account"
            setOnClickListener {
                @Suppress("DEPRECATION")
                startActivityForResult(GoogleApiClient.newAccountPickerIntent(), REQUEST_ACCOUNT_PICKER)
            }
        }

        val calendarButton = Button(this).apply {
            text = "List Upcoming Calendar Events"
            setOnClickListener {
                val token = tokenInput.text.toString().trim()
                if (token.isEmpty()) {
                    outputView.text = "Please provide an OAuth token or pick a Google account first."
                    return@setOnClickListener
                }
                outputView.text = "Fetching calendar events..."
                ioExecutor.execute {
                    val result = GoogleApiClient.listUpcomingEvents(accessToken = token)
                    runOnUiThread {
                        outputView.text = result.fold(
                            onSuccess = { events ->
                                if (events.isEmpty()) "No upcoming events found."
                                else events.joinToString("\n\n") { "• ${it.summary}\n  ${it.start}" }
                            },
                            onFailure = { "Calendar error: ${it.message}" }
                        )
                    }
                }
            }
        }

        outputView = TextView(this).apply {
            text = "Ready. Enter your Gemini API key or connect Google Calendar."
            setPadding(0, 24, 0, 24)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(apiKeyInput)
            addView(promptInput)
            addView(geminiButton)
            addView(tokenInput)
            addView(pickAccountButton)
            addView(calendarButton)
            addView(outputView)
        }

        setContentView(ScrollView(this).apply { addView(container) })
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ACCOUNT_PICKER && resultCode == RESULT_OK && data != null) {
            val accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME) ?: return
            outputView.text = "Requesting OAuth token for $accountName..."
            ioExecutor.execute {
                val result = GoogleApiClient.fetchDeviceOAuthToken(this, accountName)
                runOnUiThread {
                    result.fold(
                        onSuccess = { token ->
                            tokenInput.setText(token)
                            outputView.text = "OAuth token acquired for $accountName."
                        },
                        onFailure = { err ->
                            outputView.text = "Token error: ${err.message}"
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdownNow()
    }

    companion object {
        private const val REQUEST_ACCOUNT_PICKER = 1001
    }
}

