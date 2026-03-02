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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.concurrent.thread

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
            val localUserName = profile.getString("name")
            val partnerName = profile.getJSONObject("partner").getString("name")

            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer auth_token_jonas_owami_secure_2024")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) return

            val responseBody = response.body?.string()
            if (!responseBody.isNullOrEmpty()) {
                val globalState = JSONObject(responseBody)

                // Track root level secret additions (bucketList & rouletteState)
                var lastBucketCount = sharedPref.getInt("lastBucketCount_$partnerName", -1)
                if (globalState.has("bucketList")) {
                    val bucketList = globalState.getJSONArray("bucketList")
                    if (lastBucketCount != -1 && bucketList.length() > lastBucketCount) {
                        sendNotification("$partnerName added a new item to the bucket list! ✨")
                    }
                    with(sharedPref.edit()) {
                        putInt("lastBucketCount_$partnerName", bucketList.length())
                        apply()
                    }
                }

                var lastRouletteProposalsCount = sharedPref.getInt("lastRouletteProposalsCount_$partnerName", -1)
                if (globalState.has("rouletteState")) {
                    val rouletteState = globalState.getJSONObject("rouletteState")
                    if (rouletteState.has("proposals")) {
                        val proposals = rouletteState.getJSONArray("proposals")
                        var partnerProposalsCount = 0
                        for (i in 0 until proposals.length()) {
                            val proposal = proposals.getJSONObject(i)
                            if (proposal.has("author") && proposal.getString("author") == partnerName) {
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

                if (globalState.has("couponState")) {
                    val couponStateStr = globalState.getJSONObject("couponState").toString()
                    val lastCouponStateStr = sharedPref.getString("lastCouponState", "{}") ?: "{}"

                    if (couponStateStr != lastCouponStateStr) {
                        val currentCouponState = JSONObject(couponStateStr)
                        val lastCouponState = JSONObject(lastCouponStateStr)

                        // Check if points changed
                        if (currentCouponState.has("balances") && lastCouponState.has("balances")) {
                            val currentBalances = currentCouponState.getJSONObject("balances")
                            val lastBalances = lastCouponState.getJSONObject("balances")

                            if (currentBalances.has(localUserName) && lastBalances.has(localUserName)) {
                                val currentUserPoints = currentBalances.getInt(localUserName)
                                val lastUserPoints = lastBalances.getInt(localUserName)

                                if (currentUserPoints > lastUserPoints) {
                                    val difference = currentUserPoints - lastUserPoints
                                    sendNotification("$partnerName had awarded you $difference points")
                                }
                            }
                        }

                        // Check if partner's inventory decreased (they redeemed a coupon)
                        if (currentCouponState.has("inventory") && lastCouponState.has("inventory")) {
                            val currentInventory = currentCouponState.getJSONObject("inventory")
                            val lastInventory = lastCouponState.getJSONObject("inventory")

                            if (currentInventory.has(partnerName) && lastInventory.has(partnerName)) {
                                val currentPartnerInv = currentInventory.getJSONArray(partnerName)
                                val lastPartnerInv = lastInventory.getJSONArray(partnerName)
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

                if (globalState.has(partnerName)) {
                    val partnerStateStr = globalState.getJSONObject(partnerName).toString()
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
                sendNotification("$partnerName finished studying! 📚")
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

        if (currentState.has("messages")) {
            val currentMessages = currentState.getJSONArray("messages")
            val lastMessagesCount = if (lastState.has("messages")) lastState.getJSONArray("messages").length() else 0

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
