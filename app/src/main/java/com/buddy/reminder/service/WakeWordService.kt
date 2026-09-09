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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import com.buddy.reminder.data.AppDatabase
import com.buddy.reminder.logic.EventClassifier
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class WakeWordService : Service(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private lateinit var tts: TextToSpeech
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        tts = TextToSpeech(this, this)
        initSpeechRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startListening()
        return START_NOT_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "buddy_listener_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Buddy Voice Listener",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Buddy is Listening")
            .setContentText("Speak your reminder...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()

        startForeground(1001, notification)
    }

    private fun initSpeechRecognizer() {
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                stopSelf()
                return@post
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            processSpokenPhrase(matches[0])
                        } else {
                            stopSelf()
                        }
                    }

                    override fun onError(error: Int) {
                        // Stop listening immediately on error or silence timeout to conserve battery
                        stopSelf()
                    }

                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
        }
    }

    private fun startListening() {
        mainHandler.post {
            try {
                speechRecognizer?.startListening(recognizerIntent)
            } catch (e: Exception) {
                e.printStackTrace()
                stopSelf()
            }
        }
    }

    private fun processSpokenPhrase(rawText: String) {
        val text = rawText.lowercase()

        scope.launch {
            val db = AppDatabase.getDatabase(applicationContext)

            // Direct Voice Command check (e.g., "Hey buddy meeting at 9 pm")
            val directParse = EventClassifier.parse(text)

            if (directParse != null) {
                val reminderTime = directParse.eventTimeMs - (directParse.offsetMinutes * 60 * 1000)

                db.reminderDao().insertEvent(
                    com.buddy.reminder.data.ReminderEvent(
                        title = directParse.title,
                        category = directParse.category,
                        eventTimestamp = directParse.eventTimeMs,
                        alarmTimestamp = reminderTime,
                        isHandled = true
                    )
                )

                scheduleSystemAlarm(reminderTime, directParse.title)

                val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                val reminderTimeStr = timeFmt.format(Date(reminderTime))
                val offsetText = if (directParse.category == "FLIGHT") "3 hours" else "10 minutes"

                speak("${directParse.title} reminder has been set $offsetText ahead of time for $reminderTimeStr.")
                return@launch
            }

            // Otherwise process unhandled notification
            val event = db.reminderDao().getLatestPendingEvent()
            if (event != null) {
                scheduleSystemAlarm(event.alarmTimestamp, event.title)
                db.reminderDao().markAsHandled(event.id)

                val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                val reminderTimeStr = timeFmt.format(Date(event.alarmTimestamp))
                val offsetText = if (event.category == "FLIGHT") "3 hours" else "10 minutes"

                speak("${event.title} reminder has been set $offsetText ahead of time for $reminderTimeStr.")
            } else {
                speak("No event details found.")
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
        mainHandler.post {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    // Turn off service and microphone completely after speaking
                    stopSelf()
                }
                override fun onError(utteranceId: String?) {
                    stopSelf()
                }
            })
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "BUDDY_TTS_ID")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.US
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
