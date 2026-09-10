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
            val cleanJson = jsonString.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            val obj = JSONObject(cleanJson)

            val title = obj.optString("title", "Scheduled Event")
            val category = obj.optString("category", "GENERAL").uppercase()
            val timeStr = obj.optString("event_time", "")
            val offset = obj.optLong("offset_minutes", 15)

            if (timeStr.isBlank() || !timeStr.contains(":")) return null

            val parts = timeStr.split(":")
            val hour = parts[0].trim().toInt()
            val minute = parts[1].trim().toInt()

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
        val lower = text.lowercase().trim()
        val category: String
        val title: String
        val offsetMinutes: Long

        when {
            lower.contains("train") || lower.contains("railway") || lower.contains("express") || lower.contains("irctc") -> {
                category = "TRAIN"
                title = "Train Departure"
                offsetMinutes = 60
            }
            lower.contains("flight") || lower.contains("airline") || lower.contains("boarding") -> {
                category = "FLIGHT"
                title = "Flight Departure"
                offsetMinutes = 180
            }
            lower.contains("concert") || lower.contains("concept") || lower.contains("show") || lower.contains("gig") || lower.contains("music") -> {
                category = "CONCERT"
                title = "Concert Event"
                offsetMinutes = 120
            }
            lower.contains("movie") || lower.contains("cinema") || lower.contains("theatre") || lower.contains("film") -> {
                category = "MOVIE"
                title = "Movie Showtime"
                offsetMinutes = 45
            }
            lower.contains("meeting") || lower.contains("sync") || lower.contains("interview") || lower.contains("call") -> {
                category = "MEETING"
                title = "Scheduled Meeting"
                offsetMinutes = 10
            }
            else -> {
                category = "GENERAL"
                title = "Scheduled Reminder"
                offsetMinutes = 15
            }
        }

        val eventTime = extractTime(lower) ?: return null

        return ParsedEvent(
            title = title,
            category = category,
            eventTimeMs = eventTime,
            offsetMinutes = offsetMinutes
        )
    }

    private fun extractTime(cleanText: String): Long? {
        // Tolerates "7 pm", "7pm", "7:00 pm", "7:00pm", "7.00 pm", "at 7", "7:00"
        val pattern = Pattern.compile("""(\b\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?""", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(cleanText)

        while (matcher.find()) {
            val hourStr = matcher.group(1) ?: continue
            val minStr = matcher.group(2)
            val period = matcher.group(3)?.lowercase()?.replace(".", "")?.trim()

            var hour = hourStr.toIntOrNull() ?: continue
            val minute = minStr?.toIntOrNull() ?: 0

            // If an explicit period like am/pm exists
            if (period == "pm" && hour < 12) hour += 12
            if (period == "am" && hour == 12) hour = 0

            // If no am/pm specified, assume daytime/evening logical default
            if (period == null && hour in 1..6) hour += 12

            val now = Calendar.getInstance()
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (before(now)) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            return cal.timeInMillis
        }
        return null
    }
}
