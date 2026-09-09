package com.buddy.reminder.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "internal_events")
data class ReminderEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val category: String,
    val eventTimestamp: Long,
    val alarmTimestamp: Long,
    val isHandled: Boolean = false
)
