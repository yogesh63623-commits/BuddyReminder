package com.buddy.reminder

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.buddy.reminder.data.AppDatabase
import com.buddy.reminder.service.WakeWordService
import com.buddy.reminder.ui.HomeScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val db = AppDatabase.getDatabase(this)

        setContent {
            MaterialTheme {
                val events by db.reminderDao().getAllEventsFlow().collectAsState(initial = emptyList())

                HomeScreen(
                    events = events,
                    onMicClick = {
                        val serviceIntent = Intent(this, WakeWordService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                    }
                )
            }
        }
    }
}
