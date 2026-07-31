package com.together.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AlarmLogger.log(context, "AlarmReceiver.onReceive() triggered.")
        val serviceIntent = Intent(context, CoupleService::class.java).apply {
            action = "PLAY_ALARM"
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
                AlarmLogger.log(context, "Started CoupleService in Foreground to play alarm.")
            } else {
                context.startService(serviceIntent)
                AlarmLogger.log(context, "Started CoupleService to play alarm.")
            }
        } catch (e: Exception) {
            AlarmLogger.log(context, "Exception starting CoupleService from AlarmReceiver", e)
        }
    }
}
