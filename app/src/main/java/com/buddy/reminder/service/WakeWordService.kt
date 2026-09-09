package com.buddy.reminder.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import ai.picovoice.porcupine.*
import com.buddy.reminder.data.AppDatabase
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class WakeWordService : Service(), TextToSpeech.OnInitListener {

    private var porcupineManager: PorcupineManager? = null
    private lateinit var tts: TextToSpeech
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        tts = TextToSpeech(this, this)
        initPorcupine()
    }

    private fun startForegroundNotification() {
        val channelId = "buddy_listener_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Buddy Wake Word Listener",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Buddy Voice Listener Active")
            .setContentText("Listening for 'Hey Buddy'...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(1001, notification)
    }

    private fun initPorcupine() {
        try {
            porcupineManager = PorcupineManager.Builder()
                .setAccessKey("YOUR_PICOVOICE_ACCESS_KEY")
                .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
                .setSensitivity(0.7f)
                .build(applicationContext) {
                    handleWakeWordTriggered()
                }
            porcupineManager?.start()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun handleWakeWordTriggered() {
        scope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val event = db.reminderDao().getLatestPendingEvent()

            if (event != null) {
                scheduleSystemAlarm(event.alarmTimestamp, event.title)
                db.reminderDao().markAsHandled(event.id)

                val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                val reminderTimeStr = timeFmt.format(Date(event.alarmTimestamp))

                val offsetText = if (event.category == "FLIGHT") "3 hours" else "10 minutes"
                val speechResponse = "$event.title reminder has been set $offsetText ahead of time for $reminderTimeStr."

                withContext(Dispatchers.Main) {
                    speak(speechResponse)
                }
            } else {
                withContext(Dispatchers.Main) {
                    speak("No new scheduled events found.")
                }
            }
        }
    }

    private fun scheduleSystemAlarm(triggerAtMs: Long, title: String) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra("EXTRA_TITLE", title)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            triggerAtMs.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMs,
            pendingIntent
        )
    }

    private fun speak(text: String) {
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "BUDDY_TTS_ID")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    override fun onDestroy() {
        porcupineManager?.stop()
        porcupineManager?.delete()
        tts.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
