package com.buddy.reminder.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.buddy.reminder.data.AppDatabase
import com.buddy.reminder.data.ReminderEvent
import com.buddy.reminder.logic.EventClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class NotificationReaderService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val extras = sbn?.notification?.extras ?: return
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val fullContent = "$title $text"

        val parsed = EventClassifier.parse(fullContent) ?: return

        val reminderTime = parsed.eventTimeMs - (parsed.offsetMinutes * 60 * 1000)

        CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getDatabase(applicationContext)
            db.reminderDao().insertEvent(
                ReminderEvent(
                    title = parsed.title,
                    category = parsed.category,
                    eventTimestamp = parsed.eventTimeMs,
                    alarmTimestamp = reminderTime,
                    isHandled = false
                )
            )
        }
    }
}
