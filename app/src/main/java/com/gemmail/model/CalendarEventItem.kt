package com.gemmail.model

import org.json.JSONArray
import org.json.JSONObject

data class CalendarEventItem(
    val id: String,
    val summary: String,
    val description: String = "",
    val location: String = "",
    val startIso: String,
    val endIso: String
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("summary", summary)
        if (description.isNotBlank()) put("description", description)
        if (location.isNotBlank()) put("location", location)
        put("start", startIso)
        put("end", endIso)
    }
}

fun List<CalendarEventItem>.toJsonPayload(source: String = "google_calendar"): String {
    val eventsArray = JSONArray()
    forEach { eventsArray.put(it.toJsonObject()) }
    return JSONObject().apply {
        put("source", source)
        put("count", size)
        put("events", eventsArray)
    }.toString(2)
}
