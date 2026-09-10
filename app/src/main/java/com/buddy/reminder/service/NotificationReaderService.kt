package com.buddy.reminder.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.buddy.reminder.data.AppDatabase
import com.buddy.reminder.data.ReminderEvent
import com.buddy.reminder.logic.EventClassifier
import com.buddy.reminder.logic.GeminiApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NotificationReaderService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val extras = sbn?.notification?.extras ?: return
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val combined = "$title $text".trim()

        if (combined.isBlank()) return

        serviceScope.launch {
            // Pass applicationContext as the first argument
            val llmResponse = GeminiApiClient.analyzeEvent(applicationContext, combined)
            var parsed = if (!llmResponse.isNullOrBlank()) {
                EventClassifier.parseLlmResponse(llmResponse)
            } else {
                null
            }

            if (parsed == null) {
                parsed = EventClassifier.parseFallback(combined)
            }

            parsed?.let { event ->
                val calculatedAlarm = event.eventTimeMs - (event.offsetMinutes * 60 * 1000)
                val db = AppDatabase.getDatabase(applicationContext)
                db.reminderDao().insertEvent(
                    ReminderEvent(
                        title = event.title,
                        category = event.category,
                        eventTimestamp = event.eventTimeMs,
                        alarmTimestamp = calculatedAlarm,
                        isHandled = false
                    )
                )
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
