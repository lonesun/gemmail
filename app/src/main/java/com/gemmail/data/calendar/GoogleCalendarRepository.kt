package com.gemmail.data.calendar

import android.content.Context
import com.gemmail.model.CalendarEventItem
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.Calendar
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event
import com.google.api.services.calendar.model.EventDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class GoogleCalendarRepository(private val appContext: Context) {

    fun buildCredential(accountName: String? = null): GoogleAccountCredential =
        GoogleAccountCredential.usingOAuth2(
            appContext,
            listOf(CalendarScopes.CALENDAR_READONLY, CalendarScopes.CALENDAR_EVENTS)
        ).apply {
            if (!accountName.isNullOrBlank()) {
                selectedAccountName = accountName
            }
        }

    suspend fun fetchUpcomingEvents(
        accountName: String,
        maxResults: Int = 10,
        calendarId: String = "primary"
    ): Result<List<CalendarEventItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val service = buildCalendarService(accountName)
            val now = DateTime(System.currentTimeMillis())
            val events = service.events()
                .list(calendarId)
                .setMaxResults(maxResults)
                .setTimeMin(now)
                .setOrderBy("startTime")
                .setSingleEvents(true)
                .execute()

            events.items.orEmpty().map { event ->
                event.toCalendarEventItem()
            }
        }
    }

    suspend fun insertEvent(
        accountName: String,
        summary: String,
        description: String = "",
        startMillis: Long,
        endMillis: Long,
        calendarId: String = "primary"
    ): Result<CalendarEventItem> = withContext(Dispatchers.IO) {
        runCatching {
            val service = buildCalendarService(accountName)
            val event = Event().apply {
                this.summary = summary
                if (description.isNotBlank()) {
                    this.description = description
                }
                start = EventDateTime().setDateTime(DateTime(startMillis))
                end = EventDateTime().setDateTime(DateTime(endMillis))
            }

            val created = service.events().insert(calendarId, event).execute()
            created.toCalendarEventItem()
        }
    }

    private fun buildCalendarService(accountName: String): Calendar {
        val credential = buildCredential(accountName)
        return Calendar.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential
        )
            .setApplicationName("Gemmail")
            .build()
    }

    private fun Event.toCalendarEventItem(): CalendarEventItem {
        val startStr = start?.dateTime?.toStringRfc3339()
            ?: start?.date?.toStringRfc3339()
            ?: ""
        val endStr = end?.dateTime?.toStringRfc3339()
            ?: end?.date?.toStringRfc3339()
            ?: ""
        return CalendarEventItem(
            id = id.orEmpty(),
            summary = summary ?: "(No title)",
            description = description.orEmpty(),
            location = location.orEmpty(),
            startIso = startStr,
            endIso = endStr
        )
    }
}
