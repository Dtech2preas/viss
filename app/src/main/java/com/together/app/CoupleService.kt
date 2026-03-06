package com.together.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import kotlin.concurrent.thread
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class CoupleService : Service() {

    private val apiUrl = "https://shrill-base-9781.dtechxpreas.workers.dev/api/couple"
    private var isRunning = false
    private val client = OkHttpClient()

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            startForegroundService()
            startPolling()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "TogetherServiceChannel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Together Background Service"
            val descriptionText = "Keeps the connection active for real-time notifications"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(channelId, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Together")
            .setContentText("Connected to your partner ❤️")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun startPolling() {
        thread {
            while (isRunning) {
                pollForUpdates()
                // Check every 15 seconds
                Thread.sleep(15000)
            }
        }
    }

    private fun pollForUpdates() {
        val sharedPref = applicationContext.getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
        val profileJson = sharedPref.getString("togetherProfile", null)

        if (profileJson.isNullOrEmpty()) {
            return
        }

        try {
            val profile = JSONObject(profileJson)
            val localUserName = profile.optString("name", "")
            val partnerObj = profile.optJSONObject("partner")
            val partnerName = partnerObj?.optString("name", "") ?: ""

            if (localUserName.isEmpty() || partnerName.isEmpty()) return

            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer auth_token_jonas_owami_secure_2024")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return

            val responseBody = response.body?.string()
            if (!responseBody.isNullOrEmpty()) {
                val globalState = JSONObject(responseBody)

                // Track streak and update widget
                updateStreakAndWidget(globalState, localUserName, partnerName)

                // Track root level secret additions (bucketList & rouletteState)
                var lastBucketCount = sharedPref.getInt("lastBucketCount_$partnerName", -1)
                val bucketList = globalState.optJSONArray("bucketList")
                if (bucketList != null) {
                    if (lastBucketCount != -1 && bucketList.length() > lastBucketCount) {
                        sendNotification("$partnerName added a new item to the bucket list! ✨")
                    }
                    with(sharedPref.edit()) {
                        putInt("lastBucketCount_$partnerName", bucketList.length())
                        apply()
                    }
                }

                var lastRouletteProposalsCount = sharedPref.getInt("lastRouletteProposalsCount_$partnerName", -1)
                val rouletteState = globalState.optJSONObject("rouletteState")
                if (rouletteState != null) {
                    val proposals = rouletteState.optJSONArray("proposals")
                    if (proposals != null) {
                        var partnerProposalsCount = 0
                        for (i in 0 until proposals.length()) {
                            val proposal = proposals.optJSONObject(i)
                            if (proposal != null && proposal.optString("author") == partnerName) {
                                partnerProposalsCount++
                            }
                        }

                        if (lastRouletteProposalsCount != -1 && partnerProposalsCount > lastRouletteProposalsCount) {
                            sendNotification("$partnerName added a suggestion to roulette")
                        }
                        with(sharedPref.edit()) {
                            putInt("lastRouletteProposalsCount_$partnerName", partnerProposalsCount)
                            apply()
                        }
                    }
                }

                val couponStateObj = globalState.optJSONObject("couponState")
                if (couponStateObj != null) {
                    val couponStateStr = couponStateObj.toString()
                    val lastCouponStateStr = sharedPref.getString("lastCouponState", "{}") ?: "{}"

                    if (couponStateStr != lastCouponStateStr) {
                        val currentCouponState = JSONObject(couponStateStr)
                        val lastCouponState = JSONObject(lastCouponStateStr)

                        // Check if points changed
                        val currentBalances = currentCouponState.optJSONObject("balances")
                        val lastBalances = lastCouponState.optJSONObject("balances")
                        if (currentBalances != null && lastBalances != null) {
                            if (currentBalances.has(localUserName) && lastBalances.has(localUserName)) {
                                val currentUserPoints = currentBalances.optInt(localUserName, 0)
                                val lastUserPoints = lastBalances.optInt(localUserName, 0)

                                if (currentUserPoints > lastUserPoints) {
                                    val difference = currentUserPoints - lastUserPoints
                                    sendNotification("$partnerName had awarded you $difference points")
                                }
                            }
                        }

                        // Check if partner's inventory decreased (they redeemed a coupon)
                        val currentInventory = currentCouponState.optJSONObject("inventory")
                        val lastInventory = lastCouponState.optJSONObject("inventory")
                        if (currentInventory != null && lastInventory != null) {
                            val currentPartnerInv = currentInventory.optJSONArray(partnerName)
                            val lastPartnerInv = lastInventory.optJSONArray(partnerName)
                            if (currentPartnerInv != null && lastPartnerInv != null) {
                                if (currentPartnerInv.length() < lastPartnerInv.length()) {
                                    sendNotification("$partnerName redeemed a coupon!")
                                } else if (currentPartnerInv.length() > lastPartnerInv.length()) {
                                    sendNotification("$partnerName got a new coupon!")
                                }
                            }
                        }

                        with(sharedPref.edit()) {
                            putString("lastCouponState", couponStateStr)
                            apply()
                        }
                    }
                }

                val partnerStateObj = globalState.optJSONObject(partnerName)
                if (partnerStateObj != null) {
                    val partnerStateStr = partnerStateObj.toString()
                    val lastPartnerStateStr = sharedPref.getString("lastPartnerState_$partnerName", "{}") ?: "{}"

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
            Log.e("CoupleService", "Error polling for updates", e)
        }
    }


    private fun updateStreakAndWidget(globalState: JSONObject, localUserName: String, partnerName: String) {
        try {
            val sharedPref = applicationContext.getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
            val myLastActiveDate = sharedPref.getString("myLastActiveDate", "") ?: ""

            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val todayDate = Date()
            val todayStr = dateFormat.format(todayDate)

            // Calculate yesterday
            val cal = Calendar.getInstance()
            cal.add(Calendar.DATE, -1)
            val yesterdayStr = dateFormat.format(cal.time)

            var streakState = globalState.optJSONObject("streakState")
            if (streakState == null) {
                streakState = JSONObject()
            }

            val jonasLastActiveDate = streakState.optString("jonasLastActiveDate", "")
            val owamiLastActiveDate = streakState.optString("owamiLastActiveDate", "")
            val lastStreakDate = streakState.optString("lastStreakDate", "")
            var currentStreak = streakState.optInt("streak", 0)

            var changed = false

            // Update my last active date if I logged in today
            if (myLastActiveDate == todayStr) {
                if (localUserName.equals("jonas", ignoreCase = true) && jonasLastActiveDate != todayStr) {
                    streakState.put("jonasLastActiveDate", todayStr)
                    changed = true
                } else if (localUserName.equals("owami", ignoreCase = true) && owamiLastActiveDate != todayStr) {
                    streakState.put("owamiLastActiveDate", todayStr)
                    changed = true
                }
            }

            val newJonasLastActiveDate = streakState.optString("jonasLastActiveDate", "")
            val newOwamiLastActiveDate = streakState.optString("owamiLastActiveDate", "")

            // If both logged in today and streak not updated today
            if (newJonasLastActiveDate == todayStr && newOwamiLastActiveDate == todayStr && lastStreakDate != todayStr) {
                if (lastStreakDate == yesterdayStr || lastStreakDate.isEmpty()) {
                    currentStreak++
                } else {
                    currentStreak = 1
                }
                streakState.put("streak", currentStreak)
                streakState.put("lastStreakDate", todayStr)
                changed = true
            }

            if (changed) {
                // Upload new streakState
                val updates = JSONObject()
                updates.put("streakState", streakState)

                val reqBodyStr = updates.toString()
                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val reqBody = reqBodyStr.toRequestBody(mediaType)

                val postReq = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer auth_token_jonas_owami_secure_2024")
                    .post(reqBody)
                    .build()

                client.newCall(postReq).execute() // Execute in background
            }

            // Extract Mood
            val partnerState = globalState.optJSONObject(partnerName)
            val partnerMood = partnerState?.optString("mood", "--") ?: "--"

            // Extract Distance
            var distanceStr = "-- km"
            val myState = globalState.optJSONObject(localUserName)
            if (myState != null && partnerState != null) {
                val myLoc = myState.optJSONObject("location")
                val partnerLoc = partnerState.optJSONObject("location")

                if (myLoc != null && partnerLoc != null) {
                    val lat1 = myLoc.optDouble("lat", Double.NaN)
                    val lng1 = myLoc.optDouble("lng", Double.NaN)
                    val lat2 = partnerLoc.optDouble("lat", Double.NaN)
                    val lng2 = partnerLoc.optDouble("lng", Double.NaN)

                    if (!lat1.isNaN() && !lng1.isNaN() && !lat2.isNaN() && !lng2.isNaN()) {
                        val R = 6371.0
                        val dLat = Math.toRadians(lat2 - lat1)
                        val dLon = Math.toRadians(lng2 - lng1)
                        val a = sin(dLat / 2) * sin(dLat / 2) +
                                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                                sin(dLon / 2) * sin(dLon / 2)
                        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
                        val distance = R * c
                        distanceStr = String.format(Locale.US, "%.0f km", distance)
                    }
                }
            }

            // Save to SharedPreferences for Widget
            with(sharedPref.edit()) {
                putString("widget_distance", distanceStr)
                putString("widget_mood", partnerMood)
                putString("widget_streak", currentStreak.toString())
                apply()
            }

            // Broadcast update to widget
            val updateIntent = Intent(applicationContext, TogetherWidgetProvider::class.java).apply {
                action = android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            applicationContext.sendBroadcast(updateIntent)

        } catch (e: Exception) {
            Log.e("CoupleService", "Error updating streak and widget", e)
        }
    }

    private fun checkAndNotify(partnerName: String, currentState: JSONObject, lastState: JSONObject) {
        val currentActivities = currentState.optJSONArray("activities")
        if (currentActivities != null && currentActivities.length() > 0) {
            val lastActivities = lastState.optJSONArray("activities")

            val latestActivity = currentActivities.optJSONObject(currentActivities.length() - 1)
            if (latestActivity != null) {
                val type = latestActivity.optString("type", "")
                val timestamp = latestActivity.optString("timestamp", "")

                var isNew = true
                if (lastActivities != null && lastActivities.length() > 0) {
                    val lastLatestActivity = lastActivities.optJSONObject(lastActivities.length() - 1)
                    if (lastLatestActivity != null && lastLatestActivity.optString("timestamp", "") == timestamp && lastLatestActivity.optString("type", "") == type) {
                        isNew = false
                    }
                }

                if (isNew && type.isNotEmpty()) {
                    val formattedType = formatActivityType(type)
                    sendNotification("$partnerName is $formattedType")
                }
            }
        }

        if (currentState.has("mood")) {
            val currentMood = currentState.optString("mood", "")
            val lastMood = lastState.optString("mood", "")
            if (currentMood != lastMood && currentMood.isNotEmpty()) {
                sendNotification("$partnerName is feeling $currentMood")
            }
        }

        val currentLogs = currentState.optJSONArray("studyLogs")
        if (currentLogs != null) {
            val lastLogs = lastState.optJSONArray("studyLogs")
            val lastLogsCount = lastLogs?.length() ?: 0

            if (currentLogs.length() > lastLogsCount) {
                sendNotification("$partnerName finished studying! 📚")
            }
        }

        val currentGameData = currentState.optJSONObject("gameData")
        if (currentGameData != null) {
            val currentTotalScore = currentGameData.optInt("totalScore", 0)

            var lastTotalScore = 0
            val lastGameData = lastState.optJSONObject("gameData")
            if (lastGameData != null) {
                lastTotalScore = lastGameData.optInt("totalScore", 0)
            }

            if (currentTotalScore > lastTotalScore) {
                sendNotification("$partnerName is playing games and scored points!")
            }
        }

        val currentMessages = currentState.optJSONArray("messages")
        if (currentMessages != null) {
            val lastMessages = lastState.optJSONArray("messages")
            val lastMessagesCount = lastMessages?.length() ?: 0

            if (currentMessages.length() > lastMessagesCount) {
                sendNotification("$partnerName send you a message")
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
            val importance = NotificationManager.IMPORTANCE_HIGH
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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        with(NotificationManagerCompat.from(applicationContext)) {
            try {
                notify(System.currentTimeMillis().toInt(), builder.build())
            } catch (e: SecurityException) {
                Log.e("CoupleService", "Notification permission not granted", e)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
    }
}
