package com.together.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PollReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("PollReceiver", "Alarm received")
        val serviceIntent = Intent(context, CoupleService::class.java)
        serviceIntent.action = "com.together.app.ACTION_POLL"

        try {
            // Because we might be in background, we just use startService
            // If the service is a foreground service, it will just process the intent
            context.startService(serviceIntent)
        } catch (e: Exception) {
            Log.e("PollReceiver", "Error starting service", e)
        }
    }
}
