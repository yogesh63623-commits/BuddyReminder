package com.buddy.reminder.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.buddy.reminder.data.AppDatabase
import com.buddy.reminder.data.ReminderEvent
import com.buddy.reminder.logic.EventClassifier
import com.buddy.reminder.logic.GeminiApiClient
import com.buddy.reminder.logic.ParsedEvent
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

class WakeWordService : Service(), TextToSpeech.OnInitListener {

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastRecognizedText = ""

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification("Buddy is active", "Listening for your voice command...")
        tts = TextToSpeech(this, this)
        initSpeechRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastRecognizedText = ""
        startListening()
        return START_NOT_STICKY
    }

    private fun startForegroundNotification(title: String, content: String) {
        val channelId = "buddy_listener_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Buddy Voice Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
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
                        val text = matches?.firstOrNull() ?: lastRecognizedText
                        if (text.isNotBlank()) {
                            processSpokenPhrase(text)
                        } else {
                            speak("I didn't catch that. Please try again.")
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            lastRecognizedText = matches[0]
                        }
                    }

                    override fun onError(error: Int) {
                        if (lastRecognizedText.isNotBlank()) {
                            processSpokenPhrase(lastRecognizedText)
                        } else {
                            if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                                restartListening()
                            } else {
                                stopSelf()
                            }
                        }
                    }

                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }

            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            }
        }
    }

    private fun startListening() {
        mainHandler.post {
            try {
                speechRecognizer?.startListening(recognizerIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun restartListening() {
        mainHandler.postDelayed({
            startListening()
        }, 500)
    }

    private fun processSpokenPhrase(spokenText: String) {
        scope.launch {
            val db = AppDatabase.getDatabase(applicationContext)

            // Try LLM parsing first
            val llmResponse = GeminiApiClient.analyzeEvent(applicationContext, spokenText)
            var parsedEvent: ParsedEvent? = null

            if (!llmResponse.isNullOrBlank()) {
                parsedEvent = EventClassifier.parseLlmResponse(llmResponse)
            }

            // Fallback to heuristic classifier
            if (parsedEvent == null) {
                parsedEvent = EventClassifier.parseFallback(spokenText)
            }

            if (parsedEvent != null) {
                val reminderTime: Long = parsedEvent.eventTimeMs - (parsedEvent.offsetMinutes * 60 * 1000)

                val id = db.reminderDao().insertEvent(
                    ReminderEvent(
                        title = parsedEvent.title,
                        category = parsedEvent.category,
                        eventTimestamp = parsedEvent.eventTimeMs,
                        alarmTimestamp = reminderTime,
                        isHandled = true
                    )
                )

                scheduleSystemAlarm(reminderTime, parsedEvent.title, id.toInt())

                val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                val reminderTimeStr = timeFmt.format(Date(reminderTime))

                val offsetHours = parsedEvent.offsetMinutes / 60
                val offsetRemMins = parsedEvent.offsetMinutes % 60
                val offsetText = when {
                    offsetHours > 0 && offsetRemMins > 0 -> "$offsetHours hours and $offsetRemMins minutes"
                    offsetHours > 0 -> "$offsetHours hour${if (offsetHours > 1) "s" else ""}"
                    else -> "$offsetRemMins minutes"
                }

                speak("${parsedEvent.title} has been scheduled. Your reminder is set for $reminderTimeStr, which is $offsetText ahead.")
                return@launch
            }

            val pendingEvent = db.reminderDao().getLatestPendingEvent()
            if (pendingEvent != null) {
                scheduleSystemAlarm(pendingEvent.alarmTimestamp, pendingEvent.title, pendingEvent.id.toInt())
                db.reminderDao().markAsHandled(pendingEvent.id)

                val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
                val reminderTimeStr = timeFmt.format(Date(pendingEvent.alarmTimestamp))
                val offsetText = if (pendingEvent.category == "FLIGHT") "3 hours" else "10 minutes"

                speak("${pendingEvent.title} confirmed. Reminder set $offsetText ahead for $reminderTimeStr.")
            } else {
                speak("I heard: $spokenText, but couldn't detect any event details.")
            }
        }
    }

    private fun scheduleSystemAlarm(triggerAtMs: Long, title: String, requestCode: Int) {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            putExtra("EXTRA_TITLE", title)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            }
        } catch (se: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        }
    }

    private fun speak(text: String) {
        mainHandler.post {
            if (!isTtsReady) {
                Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
                stopSelf()
                return@post
            }

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    mainHandler.post { stopSelf() }
                }
                override fun onError(utteranceId: String?) {
                    mainHandler.post { stopSelf() }
                }
            })

            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "BUDDY_CONFIRM_TTS")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isTtsReady = true
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        tts?.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
