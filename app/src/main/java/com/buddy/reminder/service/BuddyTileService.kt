package com.buddy.reminder.service

import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
class BuddyTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            label = "Buddy Mic"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()

        // Indicate active state on the tile
        qsTile?.apply {
            state = Tile.STATE_ACTIVE
            label = "Listening..."
            updateTile()
        }

        // Trigger the one-shot voice listener
        val voiceIntent = Intent(this, WakeWordService::class.java).apply {
            putExtra("EXTRA_ONE_SHOT", true)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(voiceIntent)
        } else {
            startService(voiceIntent)
        }
    }
}
