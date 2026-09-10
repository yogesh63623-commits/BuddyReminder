package com.buddy.reminder.data

import androidx.room.*

@Dao
interface ReminderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: ReminderEvent): Long

    @Update
    suspend fun updateEvent(event: ReminderEvent)

    @Delete
    suspend fun deleteEvent(event: ReminderEvent)

    @Query("SELECT * FROM internal_events WHERE isHandled = 0 ORDER BY id DESC LIMIT 1")
    suspend fun getLatestPendingEvent(): ReminderEvent?

    @Query("UPDATE internal_events SET isHandled = 1 WHERE id = :id")
    suspend fun markAsHandled(id: Long)

    @Query("SELECT * FROM reminders ORDER BY eventTimestamp ASC")
    fun getAllEventsFlow(): kotlinx.coroutines.flow.Flow<List<ReminderEvent>>

    @Query("SELECT * FROM internal_events WHERE eventTimestamp >= :start AND eventTimestamp < :end ORDER BY eventTimestamp ASC")
    suspend fun getEventsForRange(start: Long, end: Long): List<ReminderEvent>
}
