package com.buddy.reminder.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("EXTRA_TITLE") ?: "Scheduled Event"
        Toast.makeText(context, "BUDDY ALERT: $title is coming up!", Toast.LENGTH_LONG).show()
    }
}
