package com.buddy.reminder.logic

import org.json.JSONObject
import java.util.Calendar
import java.util.regex.Pattern

data class ParsedEvent(
    val title: String,
    val category: String,
    val eventTimeMs: Long,
    val offsetMinutes: Long
)

object EventClassifier {

    fun parseLlmResponse(jsonString: String): ParsedEvent? {
        return try {
            val cleanJson = jsonString.trim().removeSurrounding("```json", "```").trim()
            val obj = JSONObject(cleanJson)

            val title = obj.getString("title")
            val category = obj.getString("category")
            val timeStr = obj.getString("event_time")
            val offset = obj.getLong("offset_minutes")

            val parts = timeStr.split(":")
            val hour = parts[0].toInt()
            val minute = parts[1].toInt()

            val targetCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }

            ParsedEvent(
                title = title,
                category = category,
                eventTimeMs = targetCal.timeInMillis,
                offsetMinutes = offset
            )
        } catch (e: Exception) {
            null
        }
    }

    fun parseFallback(text: String): ParsedEvent? {
        val lower = text.lowercase()
        val category: String
        val title: String
        val offsetMinutes: Long

        when {
            lower.contains("flight") || lower.contains("airline") || lower.contains("boarding") -> {
                category = "FLIGHT"
                title = "Flight Departure"
                offsetMinutes = 180
            }
            lower.contains("concert") || lower.contains("concept") || lower.contains("show") || lower.contains("gig") -> {
                category = "CONCERT"
                title = "Concert Event"
                offsetMinutes = 120
            }
            lower.contains("train") || lower.contains("railway") || lower.contains("express") || lower.contains("ticket") -> {
                category = "TRAIN"
                title = "Train Departure"
                offsetMinutes = 60
            }
            lower.contains("movie") || lower.contains("cinema") || lower.contains("theatre") -> {
                category = "MOVIE"
                title = "Movie Showtime"
                offsetMinutes = 45
            }
            lower.contains("meeting") || lower.contains("sync") || lower.contains("interview") -> {
                category = "MEETING"
                title = "Scheduled Meeting"
                offsetMinutes = 10
            }
            else -> {
                category = "GENERAL"
                title = "Reminder"
                offsetMinutes = 15
            }
        }

        val eventTime = extractTime(text) ?: return null

        return ParsedEvent(
            title = title,
            category = category,
            eventTimeMs = eventTime,
            offsetMinutes = offsetMinutes
        )
    }

    private fun extractTime(text: String): Long? {
        // Tolerates spaces between hours, colons, and minutes like "8 :00 pm" or "8: 00pm"
        val regex = """\b(\d{1,2})\s*(?:[:;.]\s*(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)\b"""
        val matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text)

        if (matcher.find()) {
            var hour = matcher.group(1)?.toIntOrNull() ?: return null
            val minute = matcher.group(2)?.toIntOrNull() ?: 0
            val ampmRaw = matcher.group(3)?.lowercase()?.replace(".", "") ?: ""

            if (ampmRaw == "pm" && hour < 12) hour += 12
            if (ampmRaw == "am" && hour == 12) hour = 0

            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(Calendar.getInstance())) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            return cal.timeInMillis
        }
        return null
    }
}
