package com.buddy.reminder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CalendarView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: ReminderAdapter
    private val eventList = mutableListOf<ReminderEvent>()
    private lateinit var tvSectionTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkPermissions()

        val calendarView = findViewById<CalendarView>(R.id.calendarView)
        val rvReminders = findViewById<RecyclerView>(R.id.rvReminders)
        val btnStartVoice = findViewById<Button>(R.id.btnStartVoice)
        val btnShowAll = findViewById<Button>(R.id.btnShowAll)
        tvSectionTitle = findViewById(R.id.tvSectionTitle)

        adapter = ReminderAdapter(eventList)
        rvReminders.layoutManager = LinearLayoutManager(this)
        rvReminders.adapter = adapter

        // Load all reminders initially
        loadAllReminders()

        // Filter events when tapping a specific date
        calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            filterEventsForDate(year, month, dayOfMonth)
        }

        btnShowAll.setOnClickListener {
            loadAllReminders()
        }

        btnStartVoice.setOnClickListener {
            val serviceIntent = Intent(this, WakeWordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "Buddy Listener Active", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        loadAllReminders()
    }

    private fun loadAllReminders() {
        tvSectionTitle.text = "All Scheduled Reminders"
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

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 101)
        }

        // Prompt for Notification Listener Access if missing
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (enabledListeners == null || !enabledListeners.contains(packageName)) {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }
    }
}

class ReminderAdapter(private val items: List<ReminderEvent>) : RecyclerView.Adapter<ReminderAdapter.ViewHolder>() {

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvTitle: TextView = v.findViewById(R.id.tvEventTitle)
        val tvCategory: TextView = v.findViewById(R.id.tvEventCategory)
        val tvEventTime: TextView = v.findViewById(R.id.tvEventTime)
        val tvAlarmTime: TextView = v.findViewById(R.id.tvAlarmTime)
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
        holder.tvEventTime.text = "Event Time: " + fmt.format(Date(item.eventTimestamp))
        holder.tvAlarmTime.text = "Reminder: " + fmt.format(Date(item.alarmTimestamp))
    }

    override fun getItemCount() = items.size
}
