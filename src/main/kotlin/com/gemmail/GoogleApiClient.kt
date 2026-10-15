package com.gemmail

import android.accounts.AccountManager
import android.app.Activity
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

data class CalendarEvent(
    val id: String,
    val summary: String,
    val start: String,
    val end: String
)

object GoogleApiClient {
    private const val GEMINI_BASE_URL =
        "https://generativelanguage.googleapis.com/v1beta/models"
    private const val CALENDAR_BASE_URL =
        "https://www.googleapis.com/calendar/v3"

    const val CALENDAR_READONLY_SCOPE =
        "oauth2:https://www.googleapis.com/auth/calendar.readonly"
    const val CALENDAR_EVENTS_SCOPE =
        "oauth2:https://www.googleapis.com/auth/calendar.events"

    /**
     * Calls the Gemini REST API (`generateContent`) using an API key.
     * Must be called off the main thread.
     */
    fun generateContent(
        apiKey: String,
        prompt: String,
        model: String = "gemini-2.5-flash"
    ): Result<String> = runCatching {
        val endpoint = "$GEMINI_BASE_URL/$model:generateContent?key=${URLEncoder.encode(apiKey, "UTF-8")}"
        val payload = JSONObject().apply {
            put(
                "contents",
                JSONArray().put(
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("text", prompt))
                    )
                )
            )
        }

        val responseJson = requestJson(
            url = endpoint,
            method = "POST",
            body = payload.toString()
        )

        val candidates = responseJson.optJSONArray("candidates")
            ?: error("No candidates returned: $responseJson")
        val parts = candidates.getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")

        buildString {
            for (i in 0 until parts.length()) {
                append(parts.getJSONObject(i).optString("text"))
            }
        }.trim()
    }

    /**
     * Lists upcoming events from the user's primary Google Calendar.
     * Requires an OAuth 2.0 Bearer access token. Must be called off the main thread.
     */
    fun listUpcomingEvents(
        accessToken: String,
        maxResults: Int = 10,
        calendarId: String = "primary"
    ): Result<List<CalendarEvent>> = runCatching {
        val encodedCalendarId = URLEncoder.encode(calendarId, "UTF-8")
        val timeMin = URLEncoder.encode(Instant.now().toString(), "UTF-8")
        val endpoint =
            "$CALENDAR_BASE_URL/calendars/$encodedCalendarId/events" +
                "?maxResults=$maxResults&singleEvents=true&orderBy=startTime&timeMin=$timeMin"

        val responseJson = requestJson(
            url = endpoint,
            method = "GET",
            headers = mapOf("Authorization" to "Bearer $accessToken")
        )

        val items = responseJson.optJSONArray("items") ?: JSONArray()
        List(items.length()) { i ->
            val item = items.getJSONObject(i)
            val startObj = item.optJSONObject("start")
            val endObj = item.optJSONObject("end")
            CalendarEvent(
                id = item.optString("id"),
                summary = item.optString("summary", "(No title)"),
                start = startObj?.optString("dateTime")?.ifEmpty { startObj.optString("date") } ?: "",
                end = endObj?.optString("dateTime")?.ifEmpty { endObj.optString("date") } ?: ""
            )
        }
    }

    /**
     * Inserts a new event into the user's Google Calendar.
     * `startIso` and `endIso` should be ISO-8601 timestamps (e.g. "2026-10-15T14:00:00Z").
     * Must be called off the main thread.
     */
    fun createCalendarEvent(
        accessToken: String,
        summary: String,
        startIso: String,
        endIso: String,
        description: String = "",
        calendarId: String = "primary"
    ): Result<CalendarEvent> = runCatching {
        val encodedCalendarId = URLEncoder.encode(calendarId, "UTF-8")
        val endpoint = "$CALENDAR_BASE_URL/calendars/$encodedCalendarId/events"
        val payload = JSONObject().apply {
            put("summary", summary)
            if (description.isNotBlank()) put("description", description)
            put("start", JSONObject().put("dateTime", startIso))
            put("end", JSONObject().put("dateTime", endIso))
        }

        val item = requestJson(
            url = endpoint,
            method = "POST",
            headers = mapOf("Authorization" to "Bearer $accessToken"),
            body = payload.toString()
        )

        val startObj = item.optJSONObject("start")
        val endObj = item.optJSONObject("end")
        CalendarEvent(
            id = item.optString("id"),
            summary = item.optString("summary", summary),
            start = startObj?.optString("dateTime") ?: startIso,
            end = endObj?.optString("dateTime") ?: endIso
        )
    }

    /**
     * Launches the built-in Android account picker for a Google account so you can
     * obtain an OAuth token via [fetchDeviceOAuthToken] without any external SDK.
     */
    fun newAccountPickerIntent(): Intent =
        AccountManager.newChooseAccountIntent(
            null,
            null,
            arrayOf("com.google"),
            null,
            null,
            null,
            null
        )

    /**
     * Retrieves an OAuth2 token from Android's built-in AccountManager for a chosen
     * Google account name. Must be called off the main thread.
     */
    fun fetchDeviceOAuthToken(
        activity: Activity,
        accountName: String,
        scope: String = CALENDAR_READONLY_SCOPE
    ): Result<String> = runCatching {
        val accountManager = AccountManager.get(activity)
        val account = android.accounts.Account(accountName, "com.google")
        val bundle = accountManager.getAuthToken(account, scope, null, activity, null, null).result
        bundle.getString(AccountManager.KEY_AUTHTOKEN)
            ?: error("Authorization required or no token returned")
    }

    private fun requestJson(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null
    ): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }

        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()

        if (status !in 200..299) {
            error("HTTP $status: $responseText")
        }
        return if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
    }
}
