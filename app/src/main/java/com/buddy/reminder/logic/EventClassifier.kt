package com.buddy.reminder.logic

import java.util.Calendar
import java.util.regex.Pattern

data class ParsedEvent(
    val title: String,
    val category: String,
    val eventTimeMs: Long,
    val offsetMinutes: Long
)

object EventClassifier {

    fun parse(text: String): ParsedEvent? {
        val lower = text.lowercase()
        val category: String
        val offsetMinutes: Long

        when {
            lower.contains("flight") || lower.contains("airline") || lower.contains("boarding") || lower.contains("ticket") -> {
                category = "FLIGHT"
                offsetMinutes = 180
            }
            lower.contains("meeting") || lower.contains("sync") || lower.contains("interview") || lower.contains("call") -> {
                category = "MEETING"
                offsetMinutes = 10
            }
            else -> return null
        }

        val eventTime = extractTime(text) ?: return null

        return ParsedEvent(
            title = if (category == "FLIGHT") "Flight Departure" else "Scheduled Meeting",
            category = category,
            eventTimeMs = eventTime,
            offsetMinutes = offsetMinutes
        )
    }

    private fun extractTime(text: String): Long? {
        val regex = ""(\d{1,2})[:.](\d{2})?\s*(am|pm)?""
        val matcher = Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(text)

        if (matcher.find()) {
            var hour = matcher.group(1)?.toIntOrNull() ?: return null
            val minute = matcher.group(2)?.toIntOrNull() ?: 0
            val ampm = matcher.group(3)?.lowercase()

            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0

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
