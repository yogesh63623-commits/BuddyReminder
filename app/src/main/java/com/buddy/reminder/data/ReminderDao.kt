package com.buddy.reminder.data

import androidx.room.*

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: ReminderEvent): Long

    @Query("SELECT * FROM internal_events WHERE isHandled = 0 ORDER BY id DESC LIMIT 1")
    suspend fun getLatestPendingEvent(): ReminderEvent?

    @Query("UPDATE internal_events SET isHandled = 1 WHERE id = :id")
    suspend fun markAsHandled(id: Long)

    @Query("SELECT * FROM internal_events ORDER BY eventTimestamp ASC")
    suspend fun getAllEvents(): List<ReminderEvent>

    @Query("SELECT * FROM internal_events WHERE eventTimestamp >= :start AND eventTimestamp < :end ORDER BY eventTimestamp ASC")
    suspend fun getEventsForRange(start: Long, end: Long): List<ReminderEvent>
}
