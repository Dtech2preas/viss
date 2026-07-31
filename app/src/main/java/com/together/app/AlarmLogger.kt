package com.together.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AlarmLogger {
    private const val PREFS_NAME = "TogetherAlarmDiagnosticPrefs"
    private const val KEY_LOGS = "alarm_logs"
    private const val MAX_LOGS = 2000 // Increased from 100 to capture more diagnostic context

    fun log(context: Context, message: String, exception: Throwable? = null) {
        val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val logsString = prefs.getString(KEY_LOGS, "[]") ?: "[]"

        val jsonArray = try {
            JSONArray(logsString)
        } catch (e: Exception) {
            JSONArray()
        }

        val logEntry = JSONObject()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
        val timestamp = sdf.format(Date())

        try {
            logEntry.put("timestamp", timestamp)
            logEntry.put("message", message)

            if (exception != null) {
                logEntry.put("exception", Log.getStackTraceString(exception))
            }

            // Log to logcat as well
            Log.d("AlarmLogger", "$timestamp: $message", exception)

            jsonArray.put(logEntry)

            // Keep only the last MAX_LOGS
            val trimmedArray = JSONArray()
            val startIndex = if (jsonArray.length() > MAX_LOGS) jsonArray.length() - MAX_LOGS else 0
            for (i in startIndex until jsonArray.length()) {
                trimmedArray.put(jsonArray.get(i))
            }

            prefs.edit().putString(KEY_LOGS, trimmedArray.toString()).apply()

        } catch (e: Exception) {
            Log.e("AlarmLogger", "Failed to write log", e)
        }
    }

    fun getLogs(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LOGS, "[]") ?: "[]"
    }

    fun clearLogs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_LOGS).apply()
    }
}
