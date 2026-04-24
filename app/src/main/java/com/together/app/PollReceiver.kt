package com.together.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

class PollReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("PollReceiver", "Alarm received")

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Together:PollReceiverWakeLock")

        // Acquire wake lock for 5 seconds to ensure the service has time to start and acquire its own lock
        wakeLock.acquire(5000L)

        val serviceIntent = Intent(context, CoupleService::class.java)
        serviceIntent.action = "com.together.app.ACTION_POLL"

        try {
            // Using startForegroundService to prevent IllegalStateException in Android 8.0+ background execution
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.e("PollReceiver", "Error starting service", e)
        }
    }
}
