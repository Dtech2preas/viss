package com.together.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class CoupleWorker(appContext: Context, workerParams: WorkerParameters) :
    Worker(appContext, workerParams) {

    private val apiUrl = "https://shrill-base-9781.dtechxpreas.workers.dev/api/couple"

    override fun doWork(): Result {
        val sharedPref = applicationContext.getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
        val profileJson = sharedPref.getString("togetherProfile", null)

        if (profileJson.isNullOrEmpty()) {
            Log.d("CoupleWorker", "No profile found, skipping work.")
            return Result.success()
        }

        try {
            val profile = JSONObject(profileJson)
            val partnerName = profile.getJSONObject("partner").getString("name")

            val client = OkHttpClient()
            val request = Request.Builder()
                .url(apiUrl)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                return Result.retry()
            }

            val responseBody = response.body?.string()
            if (!responseBody.isNullOrEmpty()) {
                val globalState = JSONObject(responseBody)

                // Track root level secret additions (bucketList & rouletteState)
                var lastBucketCount = sharedPref.getInt("lastBucketCount_$partnerName", -1)
                if (globalState.has("bucketList")) {
                    val bucketList = globalState.getJSONArray("bucketList")
                    if (lastBucketCount != -1 && bucketList.length() > lastBucketCount) {
                        sendNotification("$partnerName added something to the bucket list!")
                    }
                    with(sharedPref.edit()) {
                        putInt("lastBucketCount_$partnerName", bucketList.length())
                        apply()
                    }
                }

                if (globalState.has(partnerName)) {
                    val partnerStateStr = globalState.getJSONObject(partnerName).toString()
                    val lastPartnerStateStr = sharedPref.getString("lastPartnerState_$partnerName", "{}")

                    if (partnerStateStr != lastPartnerStateStr) {
                        val currentPartnerState = JSONObject(partnerStateStr)
                        val lastPartnerState = JSONObject(lastPartnerStateStr)

                        checkAndNotify(partnerName, currentPartnerState, lastPartnerState)

                        with(sharedPref.edit()) {
                            putString("lastPartnerState_$partnerName", partnerStateStr)
                            apply()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CoupleWorker", "Error in doWork", e)
            return Result.retry()
        }

        return Result.success()
    }

    private fun checkAndNotify(partnerName: String, currentState: JSONObject, lastState: JSONObject) {
        if (currentState.has("activities")) {
            val currentActivities = currentState.getJSONArray("activities")
            val lastActivities = if (lastState.has("activities")) lastState.getJSONArray("activities") else null

            if (currentActivities.length() > 0) {
                val latestActivity = currentActivities.getJSONObject(currentActivities.length() - 1)
                val type = latestActivity.getString("type")
                val timestamp = latestActivity.getString("timestamp")

                var isNew = true
                if (lastActivities != null && lastActivities.length() > 0) {
                    val lastLatestActivity = lastActivities.getJSONObject(lastActivities.length() - 1)
                    if (lastLatestActivity.getString("timestamp") == timestamp && lastLatestActivity.getString("type") == type) {
                        isNew = false
                    }
                }

                if (isNew) {
                    val formattedType = formatActivityType(type)
                    sendNotification("$partnerName is $formattedType")
                }
            }
        }

        if (currentState.has("mood")) {
            val currentMood = currentState.getString("mood")
            val lastMood = if (lastState.has("mood")) lastState.getString("mood") else null
            if (currentMood != lastMood && currentMood.isNotEmpty()) {
                sendNotification("$partnerName is feeling $currentMood")
            }
        }

        if (currentState.has("studyLogs")) {
            val currentLogs = currentState.getJSONArray("studyLogs")
            val lastLogsCount = if (lastState.has("studyLogs")) lastState.getJSONArray("studyLogs").length() else 0

            if (currentLogs.length() > lastLogsCount) {
                val latestLog = currentLogs.getJSONObject(currentLogs.length() - 1)
                val subject = if (latestLog.has("subject")) latestLog.getString("subject") else "something"
                sendNotification("$partnerName finished studying $subject")
            }
        }

        if (currentState.has("gameData")) {
            val currentGameData = currentState.getJSONObject("gameData")
            val currentTotalScore = if (currentGameData.has("totalScore")) currentGameData.getInt("totalScore") else 0

            var lastTotalScore = 0
            if (lastState.has("gameData")) {
                val lastGameData = lastState.getJSONObject("gameData")
                if (lastGameData.has("totalScore")) {
                    lastTotalScore = lastGameData.getInt("totalScore")
                }
            }

            if (currentTotalScore > lastTotalScore) {
                sendNotification("$partnerName is playing games and scored points!")
            }
        }
    }

    private fun formatActivityType(type: String): String {
        val map = mapOf(
            "pooping" to "pooping 💩",
            "eating" to "eating 🍽️",
            "working" to "working 💼",
            "sleeping" to "sleeping 😴",
            "exercising" to "exercising 🏃",
            "thinking" to "thinking of you 💭",
            "shopping" to "shopping 🛍️",
            "watching" to "watching TV 📺",
            "cooking" to "cooking 👩‍🍳",
            "driving" to "driving 🚗",
            "missing" to "missing you 💔",
            "celebrating" to "celebrating 🎉",
            "studying" to "studying 📚",
            "goingout" to "going out 🚶‍♀️",
            "goingoffline" to "going offline 🔌"
        )
        return map[type] ?: type
    }

    private fun sendNotification(content: String) {
        val channelId = "TogetherUpdates"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = applicationContext.getString(R.string.channel_name)
            val descriptionText = applicationContext.getString(R.string.channel_description)
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Together Update")
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(applicationContext)) {
            try {
                notify(System.currentTimeMillis().toInt(), builder.build())
            } catch (e: SecurityException) {
                Log.e("CoupleWorker", "Notification permission not granted", e)
            }
        }
    }
}
