package com.buddy.reminder

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.buddy.reminder.data.AppDatabase
import com.buddy.reminder.data.ReminderEvent
import com.buddy.reminder.service.WakeWordService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var adapter: ReminderAdapter
    private val eventList = mutableListOf<ReminderEvent>()
    private lateinit var tvSectionTitle: TextView
    private lateinit var cardCalendar: CardView
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tts = TextToSpeech(this, this)

        val ivLogo = findViewById<ImageView>(R.id.ivBuddyHeaderIcon)
        val btnVoice = findViewById<Button>(R.id.btnQuickVoice)
        val btnShowAll = findViewById<Button>(R.id.btnShowAll)
        val rvReminders = findViewById<RecyclerView>(R.id.rvReminders)
        val calendarView = findViewById<CalendarView>(R.id.calendarView)
        cardCalendar = findViewById(R.id.cardCalendarContainer)
        tvSectionTitle = findViewById(R.id.tvSectionTitle)

        // 2) Smooth startup entry animation (Icon bounce/scale + fade in)
        ivLogo.scaleX = 0.3f
        ivLogo.scaleY = 0.3f
        ivLogo.alpha = 0f
        ivLogo.animate()
            .scaleX(1.0f)
            .scaleY(1.0f)
            .alpha(1.0f)
            .setDuration(700)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // 4) Tapping top icon collapses or expands calendar
        ivLogo.setOnClickListener {
            if (cardCalendar.visibility == View.VISIBLE) {
                cardCalendar.animate()
                    .alpha(0f)
                    .setDuration(250)
                    .withEndAction { cardCalendar.visibility = View.GONE }
                    .start()
            } else {
                cardCalendar.alpha = 0f
                cardCalendar.visibility = View.VISIBLE
                cardCalendar.animate().alpha(1f).setDuration(250).start()
            }
        }

        // Adapter setup with Edit & Delete callbacks
        adapter = ReminderAdapter(
            items = eventList,
            onEdit = { event -> showEditDialog(event) },
            onDelete = { event -> confirmDelete(event) }
        )
        rvReminders.layoutManager = LinearLayoutManager(this)
        rvReminders.adapter = adapter

        loadAllReminders()

        calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            filterEventsForDate(year, month, dayOfMonth)
        }

        btnShowAll.setOnClickListener {
            loadAllReminders()
        }

        btnVoice.setOnClickListener {
            val serviceIntent = Intent(this, WakeWordService::class.java).apply {
                putExtra("EXTRA_ONE_SHOT", true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Listening for command...", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        loadAllReminders()
    }

    private fun loadAllReminders() {
        tvSectionTitle.text = "All Scheduled Events & Timings"
        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            val events = db.reminderDao().getAllEvents()
            withContext(Dispatchers.Main) {
                eventList.clear()
                eventList.addAll(events)
                adapter.notifyDataSetChanged()
            }
        }
    }

    private fun filterEventsForDate(year: Int, month: Int, day: Int) {
        val targetCal = Calendar.getInstance().apply {
            set(year, month, day, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startMs = targetCal.timeInMillis
        targetCal.add(Calendar.DAY_OF_YEAR, 1)
        val endMs = targetCal.timeInMillis

        val displayFmt = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
        tvSectionTitle.text = "Events for " + displayFmt.format(Date(startMs))

        lifecycleScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getDatabase(applicationContext)
            val filtered = db.reminderDao().getEventsForRange(startMs, endMs)
            withContext(Dispatchers.Main) {
                eventList.clear()
                eventList.addAll(filtered)
                adapter.notifyDataSetChanged()
            }
        }
    }

    // 3) Edit Event Dialog
    private fun showEditDialog(event: ReminderEvent) {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Edit Event Title")

        val input = EditText(this).apply {
            setText(event.title)
            setSelection(event.title.length)
        }
        builder.setView(input)

        builder.setPositiveButton("Save") { _, _ ->
            val updatedTitle = input.text.toString().trim()
            if (updatedTitle.isNotEmpty()) {
                val updatedEvent = event.copy(title = updatedTitle)
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(applicationContext)
                    db.reminderDao().updateEvent(updatedEvent)
                    withContext(Dispatchers.Main) {
                        loadAllReminders()
                        speakFeedback("Event updated to $updatedTitle")
                    }
                }
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    // 3) Delete Event Dialog
    private fun confirmDelete(event: ReminderEvent) {
        AlertDialog.Builder(this)
            .setTitle("Delete Reminder")
            .setMessage("Are you sure you want to remove '${event.title}'?")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val db = AppDatabase.getDatabase(applicationContext)
                    db.reminderDao().deleteEvent(event)
                    withContext(Dispatchers.Main) {
                        loadAllReminders()
                        speakFeedback("Reminder for ${event.title} has been deleted.")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // 1) Dynamic voice feedback
    private fun speakFeedback(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "BUDDY_UI_TTS")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

// Custom Adapter with Remaining Countdown Timing
class ReminderAdapter(
    private val items: List<ReminderEvent>,
    private val onEdit: (ReminderEvent) -> Unit,
    private val onDelete: (ReminderEvent) -> Unit
) : RecyclerView.Adapter<ReminderAdapter.ViewHolder>() {

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle: TextView = v.findViewById(R.id.tvEventTitle)
        val tvCategory: TextView = v.findViewById(R.id.tvEventCategory)
        val tvEventTime: TextView = v.findViewById(R.id.tvEventTime)
        val tvAlarmTime: TextView = v.findViewById(R.id.tvAlarmTime)
        val btnEdit: Button = v.findViewById(R.id.btnEditEvent)
        val btnDelete: Button = v.findViewById(R.id.btnDeleteEvent)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_reminder, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val fmt = SimpleDateFormat("MMM dd, h:mm a", Locale.getDefault())

        holder.tvTitle.text = item.title
        holder.tvCategory.text = item.category
        holder.tvEventTime.text = "Event: " + fmt.format(Date(item.eventTimestamp))

        // Calculate remaining time
        val now = System.currentTimeMillis()
        val diffMs = item.alarmTimestamp - now
        val remainingText = if (diffMs > 0) {
            val hours = diffMs / (1000 * 60 * 60)
            val minutes = (diffMs / (1000 * 60)) % 60
            "Alarm rings in: ${hours}h ${minutes}m (" + fmt.format(Date(item.alarmTimestamp)) + ")"
        } else {
            "Reminder Passed (" + fmt.format(Date(item.alarmTimestamp)) + ")"
        }

        holder.tvAlarmTime.text = remainingText
        holder.btnEdit.setOnClickListener { onEdit(item) }
        holder.btnDelete.setOnClickListener { onDelete(item) }
    }

    override fun getItemCount() = items.size
}
