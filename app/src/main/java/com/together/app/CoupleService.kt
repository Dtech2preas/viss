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
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import okhttp3.Response
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
import android.location.Location
import android.os.Bundle
import android.os.PowerManager
import android.app.AlarmManager
import android.os.Looper
import android.annotation.SuppressLint
import android.os.Handler
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationResult
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

class CoupleService : Service() {

    private fun broadcastDebugLog(tag: String, message: String) {
        Log.d(tag, message)
        val fullMessage = "[$tag] $message"
        val intent = Intent("com.together.app.DEBUG_LOG")
        intent.setPackage(packageName)
        intent.putExtra("tag", tag)
        intent.putExtra("message", message)
        intent.putExtra("timestamp", System.currentTimeMillis())
        sendBroadcast(intent)

        // Save to SharedPreferences for offline reading when app opens
        try {
            val sharedPref = getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
            val logsStr = sharedPref.getString("background_logs", "[]") ?: "[]"
            val logsArray = JSONArray(logsStr)
            val time = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            logsArray.put("[$time] $fullMessage")

            // Keep last 100 logs
            if (logsArray.length() > 100) {
                logsArray.remove(0)
            }

            sharedPref.edit().putString("background_logs", logsArray.toString()).apply()
        } catch (e: Exception) {
            Log.e("CoupleService", "Failed to save log to prefs", e)
        }
    }


    private var lastLocationUpdateTime: Long = 0

    private val apiUrl = "https://shrill-base-9781.dtechxpreas.workers.dev/api/couple"
    private val firebaseUrl = "https://dtech-75e26-default-rtdb.firebaseio.com"
    private var isRunning = false
    private val client = OkHttpClient()
    private var permanentWakeLock: PowerManager.WakeLock? = null
    private var forceUpdateListener: ValueEventListener? = null
    private var firebaseDb: FirebaseDatabase? = null
    private var forceUpdateRef: com.google.firebase.database.DatabaseReference? = null
    private var heartbeatHandler: Handler? = null

    // Continuous location listener to keep GPS radio warm
    private var continuousLocationCallback: LocationCallback? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()

        // Acquire permanent wake lock if we don't have it
        if (permanentWakeLock == null || permanentWakeLock?.isHeld != true) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            permanentWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Together:PermanentWakeLock")
            // No timeout, keeping it forever to guarantee background updates
            permanentWakeLock?.acquire()
        }

        if (!isRunning) {
            isRunning = true
            initFirebase()
            startPolling()
        }

        if (intent?.action == "com.together.app.ACTION_POLL") {
            thread {
                try {
                    pollForUpdates()
                } catch (e: Exception) {
                    broadcastDebugLog("CoupleService", "Error polling: ${e.message}"); Log.e("CoupleService", "Error polling", e)
                }
            }
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

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    private fun initFirebase() {
        if (FirebaseApp.getApps(applicationContext).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApiKey("AIzaSyBlHyneuosm2zVsAVF_QPKNE5SsWNDUMyc")
                .setApplicationId("1:101292842193:web:d12190895fa7a6b330b9f0")
                .setDatabaseUrl("https://dtech-75e26-default-rtdb.firebaseio.com")
                .setProjectId("dtech-75e26")
                .build()
            FirebaseApp.initializeApp(applicationContext, options)
        }
        firebaseDb = FirebaseDatabase.getInstance()
    }

    private fun startHeartbeat() {
        if (heartbeatHandler == null) {
            heartbeatHandler = Handler(Looper.getMainLooper())
        }
        heartbeatHandler?.postDelayed(object : Runnable {
            override fun run() {
                if (!isRunning) return
                try {
                    firebaseDb?.getReference(".info/connected")?.get()?.addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.d("CoupleService", "Heartbeat: Firebase connection OK")
                        } else {
                            Log.e("CoupleService", "Heartbeat: Firebase connection check failed")
                            startForceUpdateListener()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CoupleService", "Heartbeat failed", e)
                }
                heartbeatHandler?.postDelayed(this, 60000)
            }
        }, 60000)
    }

    private fun startPolling() {
        startContinuousLocationUpdates()
        startForceUpdateListener()
        startHeartbeat()
        thread {
            pollForUpdates()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startContinuousLocationUpdates() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Request updates every 5 minutes just to keep the subsystem warm
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 5 * 60 * 1000L)
            .setMinUpdateIntervalMillis(5 * 60 * 1000L)
            .build()

        continuousLocationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                // We don't necessarily need to push this location to the server.
                // The explicit polling mechanism (fetchAndPushLocation) handles the strict 15min/15sec updates.
                // This is just to keep the OS aware that we still actively want location.
                Log.d("CoupleService", "Continuous location update received")
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                continuousLocationCallback!!,
                Looper.getMainLooper()
            )
        } catch (e: Exception) {
            Log.e("CoupleService", "Failed to start continuous location updates", e)
        }
    }

    private fun startForceUpdateListener() {
        val sharedPref = applicationContext.getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
        val profileJson = sharedPref.getString("togetherProfile", null)
        if (profileJson.isNullOrEmpty()) return

        try {
            val profile = JSONObject(profileJson)
            val localUserName = profile.optString("name", "")
            if (localUserName.isEmpty()) return

            // sseUrl no longer needed

            if (forceUpdateRef != null && forceUpdateListener != null) {
                forceUpdateRef?.removeEventListener(forceUpdateListener!!)
            }

            forceUpdateRef = firebaseDb?.getReference("forceUpdate/$localUserName")

            forceUpdateListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    try {
                        val forceReqId = snapshot.child("requestId").getValue(Long::class.java)

                        var shouldUpdate = false
                        if (forceReqId != null && forceReqId != -1L) {
                            val lastReqId = sharedPref.getLong("lastForceUpdateReqId", -1L)
                            if (forceReqId != lastReqId) {
                                sharedPref.edit().putLong("lastForceUpdateReqId", forceReqId).apply()
                                shouldUpdate = true
                            }
                        } else {
                            val legacyValue = snapshot.getValue(Boolean::class.java)
                            if (legacyValue == true) {
                                shouldUpdate = true
                            }
                        }

                        if (shouldUpdate) {
                            Log.d("CoupleService", "Force update triggered via Firebase Listener!")
                            broadcastDebugLog("CoupleService", "Force update triggered via Firebase Listener! requestId: $forceReqId")
                            val authToken = sharedPref.getString("together_auth_token", "") ?: ""

                            thread {
                                try {
                                    val stateReq = Request.Builder()
                                        .url(apiUrl)
                                        .addHeader("Authorization", "Bearer $authToken")
                                        .build()
                                    val stateRes = client.newCall(stateReq).execute()
                                    if (stateRes.isSuccessful) {
                                        val bodyStr = stateRes.body?.string()
                                        if (!bodyStr.isNullOrEmpty()) {
                                            val globalState = JSONObject(bodyStr)
                                            val myState = globalState.optJSONObject(localUserName) ?: JSONObject()
                                            fetchAndPushLocation(localUserName, authToken, myState)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("CoupleService", "Failed fetching state for force update", e)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CoupleService", "Error in force update listener", e)
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("CoupleService", "Firebase listener cancelled", error.toException())
                }
            }

            forceUpdateRef?.addValueEventListener(forceUpdateListener!!)

        } catch (e: Exception) {
            Log.e("CoupleService", "Error setting up SSE listener", e)
        }
    }

    companion object {
        fun handleStudyToggleStatic(context: Context) {
            val sharedPref = context.getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
            val profileJson = sharedPref.getString("togetherProfile", null)
            if (profileJson.isNullOrEmpty()) return

            try {
                val profile = JSONObject(profileJson)
                val localUserName = profile.optString("name", "")
                if (localUserName.isEmpty()) return

                // Retrieve the actual token instead of hardcoding
                // But notice that previously this file had the token hardcoded in pollForUpdates.
                // Looking at memory and auth-check.js, the token should be fetched.
                // For this widget update, let's use the hardcoded one that was already here, OR extract it.
                // The worker API allows "Bearer auth_token_jonas_owami_secure_2024" or checks localStorage.
                // Wait, the memory specifically says: "The Cloudflare Worker backend requires authentication. Do not hallucinate fake Bearer tokens (like `auth_token_jonas_owami_secure_2024`) for `/api/couple` requests in Android native code; ensure the correct token is dynamically retrieved from a valid source such as SharedPreferences to prevent 401 Unauthorized errors."
                // Wait, CoupleService.kt ALREADY has the hardcoded token in pollForUpdates. Let's fix that too.
                val authToken = sharedPref.getString("together_auth_token", "") ?: ""

                val client = OkHttpClient()
                val apiUrl = "https://shrill-base-9781.dtechxpreas.workers.dev/api/couple"

                // Fetch current global state first
                val getRequest = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $authToken")
                    .build()
                val response = client.newCall(getRequest).execute()
                if (!response.isSuccessful) return

                val responseBody = response.body?.string()
                if (responseBody.isNullOrEmpty()) return

                val globalState = JSONObject(responseBody)
                val userState = globalState.optJSONObject(localUserName) ?: JSONObject()

                val isCurrentlyStudying = userState.optBoolean("isStudying", false)
                val newStudyingState = !isCurrentlyStudying

                userState.put("isStudying", newStudyingState)

                if (newStudyingState) {
                    // Starting study
                    userState.put("studyStartTime", System.currentTimeMillis())
                } else {
                    // Stopping study - create a log
                    val startTime = userState.optLong("studyStartTime", 0)
                    if (startTime > 0) {
                        val durationSeconds = ((System.currentTimeMillis() - startTime) / 1000).toInt()

                        val studyLogs = userState.optJSONArray("studyLogs") ?: JSONArray()
                        val newLog = JSONObject().apply {
                            put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
                            put("duration", durationSeconds)
                        }
                        studyLogs.put(newLog)
                        userState.put("studyLogs", studyLogs)
                        userState.remove("studyStartTime")
                    }
                }

                globalState.put(localUserName, userState)

                // Save update locally to be fast for widget
                sharedPref.edit().putBoolean("widget_is_studying", newStudyingState).apply()

                // Broadcast update to widget immediately for snappy UI
                val updateIntent = Intent(context, TogetherWidgetProvider::class.java).apply {
                    action = android.appwidget.AppWidgetManager.ACTION_APPWIDGET_UPDATE
                }
                context.sendBroadcast(updateIntent)

                // Push to server
                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val body = globalState.toString().toRequestBody(mediaType)
                val postRequest = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $authToken")
                    .post(body)
                    .build()

                client.newCall(postRequest).execute()

            } catch (e: Exception) {
                Log.e("CoupleService", "Failed to toggle study", e)
            }
        }
    }

    private fun pollForUpdates() {
        broadcastDebugLog("CoupleService", "pollForUpdates triggered")
        // Immediately schedule the next poll to ensure it happens even if this run crashes
        scheduleNextPoll()

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

            val authToken = sharedPref.getString("together_auth_token", "") ?: ""

            val request = Request.Builder()
                .url(apiUrl)
                .addHeader("Authorization", "Bearer $authToken")
                .build()

            val response = client.newCall(request).execute()
            broadcastDebugLog("CoupleService", "pollForUpdates network response code: ${response.code}")
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
                        sendNotification("$partnerName added a new item to the bucket list! ✨", "bucket.html")
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
                            sendNotification("$partnerName added a suggestion to roulette", "roulette.html")
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
                                    sendNotification("$partnerName had awarded you $difference points", "index.html")
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
                                    sendNotification("$partnerName redeemed a coupon!", "coupons.html")
                                } else if (currentPartnerInv.length() > lastPartnerInv.length()) {
                                    sendNotification("$partnerName got a new coupon!", "coupons.html")
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
                    var lastPartnerStateStr = sharedPref.getString("lastPartnerState_$partnerName", "{}") ?: "{}"

                    // Fallback to server-acknowledged state if local state is empty to prevent skipped notifications
                    if (lastPartnerStateStr == "{}" || lastPartnerStateStr.isEmpty()) {
                        val serverAckStateObj = globalState.optJSONObject("ackState_$localUserName")
                        if (serverAckStateObj != null) {
                            lastPartnerStateStr = serverAckStateObj.toString()
                            broadcastDebugLog("CoupleService", "Local state empty. Restored from server ackState.")
                        }
                    }

                    if (partnerStateStr != lastPartnerStateStr) {
                        val currentPartnerState = JSONObject(partnerStateStr)
                        val lastPartnerState = if (lastPartnerStateStr.isEmpty()) JSONObject() else JSONObject(lastPartnerStateStr)

                        checkAndNotify(partnerName, currentPartnerState, lastPartnerState)

                        with(sharedPref.edit()) {
                            putString("lastPartnerState_$partnerName", partnerStateStr)
                            apply()
                        }

                        // Push the newly acknowledged partner state to the server to persist it
                        thread {
                            try {
                                val ackUpdateObj = JSONObject().apply {
                                    put("ackState_$localUserName", JSONObject(partnerStateStr))
                                }
                                val ackReqBody = okhttp3.RequestBody.create(
                                    "application/json; charset=utf-8".toMediaTypeOrNull(),
                                    ackUpdateObj.toString()
                                )
                                val ackRequest = Request.Builder()
                                    .url(apiUrl)
                                    .addHeader("Authorization", "Bearer $authToken")
                                    .post(ackReqBody)
                                    .build()
                                client.newCall(ackRequest).execute().close()
                            } catch (e: Exception) {
                                Log.e("CoupleService", "Failed to sync ackState", e)
                            }
                        }
                    } else {
                        broadcastDebugLog("CoupleService", "Partner state hasn't changed. Skipping notification check.")
                    }
                }

                // Handle Incoming Calls
                val callStateObj = globalState.optJSONObject("callState")
                if (callStateObj != null) {
                    val status = callStateObj.optString("status", "")
                    val target = callStateObj.optString("target", "")
                    val caller = callStateObj.optString("caller", "")
                    val type = callStateObj.optString("type", "voice")

                    val lastCallTimestamp = sharedPref.getLong("lastCallTimestamp", 0L)
                    val currentCallTimestamp = callStateObj.optLong("timestamp", 0L)

                    // If a new incoming call is directed at this user
                    if (status == "calling" && target == localUserName && currentCallTimestamp > lastCallTimestamp) {
                        sendNotification("Incoming $type call from $caller \uD83D\uDCDE", "call.html?incoming=true")
                        with(sharedPref.edit()) {
                            putLong("lastCallTimestamp", currentCallTimestamp)
                            apply()
                        }
                    }
                }

                val myStateObj = globalState.optJSONObject(localUserName)

                var shouldUpdateLocation = false
                var isTripMode = false

                // Check for force location update flag and isTripMode in Firebase
                try {
                    val locRef = Request.Builder()
                        .url("$firebaseUrl/locations/$localUserName.json")
                        .build()
                    val locRes = client.newCall(locRef).execute()
                    if (locRes.isSuccessful) {
                        val locBody = locRes.body?.string()
                        if (locBody != null && locBody != "null") {
                            val locJson = JSONObject(locBody)
                            isTripMode = locJson.optBoolean("isTripMode", false)
                        }
                    }

                    val forceReq = Request.Builder()
                        .url("$firebaseUrl/forceUpdate/$localUserName.json")
                        .build()
                    val forceRes = client.newCall(forceReq).execute()
                    if (forceRes.isSuccessful) {
                        val forceBody = forceRes.body?.string()
                        if (forceBody != null && forceBody != "null") {
                            val forceJson = JSONObject(forceBody)
                            if (forceJson.has("requestId")) {
                                val forceReqId = forceJson.optLong("requestId", -1L)
                                val lastReqId = sharedPref.getLong("lastForceUpdateReqId", -1L)
                                if (forceReqId != -1L && forceReqId != lastReqId) {
                                    sharedPref.edit().putLong("lastForceUpdateReqId", forceReqId).apply()
                                    shouldUpdateLocation = true
                                }
                            } else if (forceBody == "true") {
                                shouldUpdateLocation = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CoupleService", "Failed to check forceUpdate/tripMode", e)
                }

                // Fetch Partner Location and broadcast to UI
                try {
                    val partnerLocReq = Request.Builder().url("$firebaseUrl/locations/$partnerName.json").build()
                    val partnerLocRes = client.newCall(partnerLocReq).execute()
                    if (partnerLocRes.isSuccessful) {
                        val locBody = partnerLocRes.body?.string()
                        if (locBody != null && locBody != "null") {
                            val locJson = JSONObject(locBody)
                            val lat = locJson.optDouble("lat", Double.NaN)
                            val lng = locJson.optDouble("lng", Double.NaN)
                            val lastUpdated = locJson.optLong("lastUpdated", 0L)

                            val lastBroadcastedUpdate = sharedPref.getLong("lastBroadcastedPartnerLocation", 0L)
                            if (lastUpdated > lastBroadcastedUpdate || lastBroadcastedUpdate == 0L) {
                                val intent = Intent("PARTNER_LOCATION_UPDATE")
                                intent.setPackage(packageName)
                                intent.putExtra("lat", lat)
                                intent.putExtra("lng", lng)
                                intent.putExtra("timestamp", lastUpdated)
                                sendBroadcast(intent)

                                sharedPref.edit().putLong("lastBroadcastedPartnerLocation", lastUpdated).apply()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("CoupleService", "Failed to broadcast partner location", e)
                }

                // Update location based on interval (15 mins normal, 15 secs trip mode)
                val currentTime = System.currentTimeMillis()
                val interval = if (isTripMode) 15 * 1000 else 15 * 60 * 1000

                // Add a tolerance because AlarmManager is inexact and might fire up to 2 mins early
                // If we are within 2 mins of the 15 min interval, count it as passing.
                // For trip mode (15s), no tolerance is needed or just 5s.
                val tolerance = if (isTripMode) 5 * 1000 else 120 * 1000

                if (currentTime - lastLocationUpdateTime >= (interval - tolerance)) {
                    shouldUpdateLocation = true
                }

                if (shouldUpdateLocation) {
                    fetchAndPushLocation(localUserName, authToken, myStateObj ?: JSONObject())
                }

                // Check 100km distance change notification
                if (myStateObj != null && partnerStateObj != null) {
                    var myLoc: JSONObject? = null
                    var partnerLoc: JSONObject? = null

                    try {
                        val locReq = Request.Builder().url("$firebaseUrl/locations/$localUserName.json").build()
                        val locRes = client.newCall(locReq).execute()
                        if (locRes.isSuccessful) {
                            val body = locRes.body?.string()
                            if (body != null && body != "null") myLoc = JSONObject(body)
                        }
                    } catch (e: Exception) {}

                    try {
                        val locReq = Request.Builder().url("$firebaseUrl/locations/$partnerName.json").build()
                        val locRes = client.newCall(locReq).execute()
                        if (locRes.isSuccessful) {
                            val body = locRes.body?.string()
                            if (body != null && body != "null") partnerLoc = JSONObject(body)
                        }
                    } catch (e: Exception) {}

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

                            val lastNotifiedDistance = sharedPref.getFloat("lastNotifiedDistance", -1f)

                            if (lastNotifiedDistance == -1f) {
                                // First time, just save
                                sharedPref.edit().putFloat("lastNotifiedDistance", distance.toFloat()).apply()
                            } else {
                                val distanceDiff = distance - lastNotifiedDistance
                                if (Math.abs(distanceDiff) >= 100) {
                                    val direction = if (distanceDiff > 0) "moved away" else "moved closer"
                                    val absDiff = Math.abs(distanceDiff).toInt()
                                    sendNotification("$partnerName has $direction by ~${absDiff}km", "location.html")

                                    sharedPref.edit().putFloat("lastNotifiedDistance", distance.toFloat()).apply()
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            broadcastDebugLog("CoupleService", "Error polling for updates: ${e.message}"); Log.e("CoupleService", "Error polling for updates", e)
        } finally {
            // scheduleNextPoll() moved to the start of pollForUpdates to ensure reliability
        }
    }



    private fun scheduleNextPoll() {
        if (!isRunning) return

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, PollReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Android severely throttles alarms. However, we MUST check location every 15 minutes.
        // For trip mode, the interval is 15s. If trip mode is active, we try to schedule 15s.
        val sharedPref = applicationContext.getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
        val profileJson = sharedPref.getString("togetherProfile", null)
        var isTripMode = false
        if (!profileJson.isNullOrEmpty()) {
            try {
                val profile = org.json.JSONObject(profileJson)
                val localUserName = profile.optString("name", "")
                val locRef = okhttp3.Request.Builder()
                    .url("https://dtech-75e26-default-rtdb.firebaseio.com/locations/$localUserName.json")
                    .build()
                val locRes = OkHttpClient().newCall(locRef).execute()
                if (locRes.isSuccessful) {
                    val locBody = locRes.body?.string()
                    if (locBody != null && locBody != "null") {
                        val locJson = org.json.JSONObject(locBody)
                        isTripMode = locJson.optBoolean("isTripMode", false)
                    }
                }
            } catch (e: Exception) {}
        }

        val intervalMs = if (isTripMode) 15000L else 15L * 60L * 1000L
        broadcastDebugLog("CoupleService", "Scheduling next poll in ${intervalMs}ms. TripMode: $isTripMode")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var canUseExact = true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                canUseExact = alarmManager.canScheduleExactAlarms()
            }
            if (canUseExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + intervalMs, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + intervalMs, pendingIntent)
            }
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + intervalMs, pendingIntent)
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchAndPushLocation(userName: String, authToken: String, myStateObj: JSONObject) {
        broadcastDebugLog("CoupleService", "fetchAndPushLocation called for user: $userName")
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            broadcastDebugLog("CoupleService", "Location permission missing in fetchAndPushLocation")
            return
        }
        // Always update the time so we don't spam requests every 15 seconds if it fails
        lastLocationUpdateTime = System.currentTimeMillis()

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        try {
            val updateLocationOnServer = { loc: Location ->
                broadcastDebugLog("CoupleService", "Updating location on server: ${loc.latitude}, ${loc.longitude}")
                thread {
                    try {
                        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()

                        // Fetch existing location data to preserve metadata
                        val getLocReq = Request.Builder().url("$firebaseUrl/locations/$userName.json").build()
                        val getLocRes = client.newCall(getLocReq).execute()
                        val currentLocData = if (getLocRes.isSuccessful) {
                            val body = getLocRes.body?.string()
                            if (body != null && body != "null") JSONObject(body) else JSONObject()
                        } else JSONObject()

                        val timestamp = System.currentTimeMillis()
                        val newLocation = JSONObject().apply {
                            put("lat", loc.latitude)
                            put("lng", loc.longitude)
                            put("timestamp", timestamp)
                            put("isTripMode", currentLocData.optBoolean("isTripMode", false))
                        }

                        // Update metadata if partner location is available
                        val profileJson = applicationContext.getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE).getString("togetherProfile", null)
                        if (!profileJson.isNullOrEmpty()) {
                            val profile = JSONObject(profileJson)
                            val partnerObj = profile.optJSONObject("partner")
                            val partnerName = partnerObj?.optString("name", "") ?: ""
                            if (partnerName.isNotEmpty()) {
                                val partLocReq = Request.Builder().url("$firebaseUrl/locations/$partnerName.json").build()
                                val partLocRes = client.newCall(partLocReq).execute()
                                if (partLocRes.isSuccessful) {
                                    val partBody = partLocRes.body?.string()
                                    if (partBody != null && partBody != "null") {
                                        val partLoc = JSONObject(partBody)
                                        val lat2 = partLoc.optDouble("lat", Double.NaN)
                                        val lng2 = partLoc.optDouble("lng", Double.NaN)

                                        if (!lat2.isNaN() && !lng2.isNaN()) {
                                            val R = 6371.0
                                            val dLat = Math.toRadians(lat2 - loc.latitude)
                                            val dLon = Math.toRadians(lng2 - loc.longitude)
                                            val a = sin(dLat / 2) * sin(dLat / 2) +
                                                    cos(Math.toRadians(loc.latitude)) * cos(Math.toRadians(lat2)) *
                                                    sin(dLon / 2) * sin(dLon / 2)
                                            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
                                            val distance = R * c

                                            // Clear force flag in Firebase for this user now that we have data
                                            val forceReqBodyClear = "false".toRequestBody(mediaType)
                                            val forcePostReqClear = Request.Builder()
                                                .url("$firebaseUrl/forceUpdate/$userName.json")
                                                .put(forceReqBodyClear)
                                                .build()
                                            client.newCall(forcePostReqClear).execute()

                                            val currentClosest = currentLocData.optDouble("closestDistance", Double.MAX_VALUE)
                                            if (distance < currentClosest) {
                                                newLocation.put("closestDistance", distance)
                                            } else if (currentLocData.has("closestDistance")) {
                                                newLocation.put("closestDistance", currentLocData.get("closestDistance"))
                                            }

                                            val currentFurthest = currentLocData.optDouble("furthestDistance", 0.0)
                                            if (distance > currentFurthest) {
                                                newLocation.put("furthestDistance", distance)
                                            } else if (currentLocData.has("furthestDistance")) {
                                                newLocation.put("furthestDistance", currentLocData.get("furthestDistance"))
                                            }

                                            var wasFarApart = currentLocData.optBoolean("wasFarApart", false)
                                            if (distance > 150) {
                                                wasFarApart = true
                                            }
                                            newLocation.put("wasFarApart", wasFarApart)
                                        }
                                    }
                                }
                            }
                        }

                        // Push location
                        val locReqBody = newLocation.toString().toRequestBody(mediaType)
                        val locPostReq = Request.Builder()
                            .url("$firebaseUrl/locations/$userName.json")
                            .put(locReqBody)
                            .build()
                        client.newCall(locPostReq).execute()

                        // Push history (matching Firebase push behavior)
                        val histReqBody = newLocation.toString().toRequestBody(mediaType)
                        val histPostReq = Request.Builder()
                            .url("$firebaseUrl/history/$userName.json")
                            .post(histReqBody)
                            .build()
                        client.newCall(histPostReq).execute()

                        // Always clear force flag after updating
                        val ts = System.currentTimeMillis()
                        val forceReqBody = "{\"requestId\":-1,\"timestamp\":$ts}".toRequestBody(mediaType)
                        val forcePostReq = Request.Builder()
                            .url("$firebaseUrl/forceUpdate/$userName.json")
                            .put(forceReqBody)
                            .build()
                        client.newCall(forcePostReq).execute()

                        lastLocationUpdateTime = System.currentTimeMillis()
                        broadcastDebugLog("CoupleService", "Location successfully updated on Firebase")
                    } catch (e: Exception) {
                        Log.e("CoupleService", "Failed to update location on Firebase", e)
                        broadcastDebugLog("CoupleService", "Failed to update location on Firebase: ${e.message}")
                    }
                }
            }

            broadcastDebugLog("CoupleService", "Requesting current location from FusedLocationClient...")
            // We request location updates, but also add getCurrentLocation with CancellationToken as a backup
            // since some devices restrict background callbacks.
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                .addOnSuccessListener { loc ->
                    broadcastDebugLog("CoupleService", "getCurrentLocation returned: ${if (loc != null) "success" else "null"}")
                    if (loc != null) {
                        updateLocationOnServer(loc)
                    } else {
                        broadcastDebugLog("CoupleService", "getCurrentLocation returned null, attempting fallback location callbacks")
                        // Fallback to normal request updates
                        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
                            .setMaxUpdates(1)
                            .build()

                        val locationCallback = object : LocationCallback() {
                            override fun onLocationResult(locationResult: LocationResult) {
                                fusedLocationClient.removeLocationUpdates(this)
                                val location = locationResult.lastLocation
                                if (location != null) {
                                    updateLocationOnServer(location)
                                } else {
                                    // Fallback to last location
                                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc: Location? ->
                                        if (lastLoc != null) {
                                            updateLocationOnServer(lastLoc)
                                        } else {
                                            clearForceFlag(userName, authToken, myStateObj)
                                        }
                                    }.addOnFailureListener {
                                        clearForceFlag(userName, authToken, myStateObj)
                                    }
                                }
                            }
                        }

                        fusedLocationClient.requestLocationUpdates(
                            locationRequest,
                            locationCallback,
                            Looper.getMainLooper()
                        ).addOnFailureListener {
                            broadcastDebugLog("CoupleService", "requestLocationUpdates failed, trying lastLocation")
                            fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc: Location? ->
                                if (lastLoc != null) {
                                    updateLocationOnServer(lastLoc)
                                } else {
                                    broadcastDebugLog("CoupleService", "All fallback location attempts failed (lastLoc null)")
                                    clearForceFlag(userName, authToken, myStateObj)
                                }
                            }.addOnFailureListener {
                                broadcastDebugLog("CoupleService", "lastLocation also failed")
                                clearForceFlag(userName, authToken, myStateObj)
                            }
                        }
                    }
                }
                .addOnFailureListener { e ->
                    broadcastDebugLog("CoupleService", "getCurrentLocation failed: ${e.message}, falling back to lastLocation")
                    // Fallback to last location
                    fusedLocationClient.lastLocation.addOnSuccessListener { lastLoc: Location? ->
                        if (lastLoc != null) {
                            updateLocationOnServer(lastLoc)
                        } else {
                            clearForceFlag(userName, authToken, myStateObj)
                        }
                    }.addOnFailureListener {
                        clearForceFlag(userName, authToken, myStateObj)
                    }
                }

        } catch (e: Exception) {
            Log.e("CoupleService", "Error pushing location", e)
            broadcastDebugLog("CoupleService", "Fatal error pushing location: ${e.message}")
            clearForceFlag(userName, authToken, myStateObj)
        }
    }

    private fun clearForceFlag(userName: String, authToken: String, myStateObj: JSONObject) {
        thread {
            try {
                val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                val forceReqBody = "false".toRequestBody(mediaType)
                val forcePostReq = Request.Builder()
                    .url("$firebaseUrl/forceUpdate/$userName.json")
                    .put(forceReqBody)
                    .build()
                client.newCall(forcePostReq).execute()
            } catch (e: Exception) {
                Log.e("CoupleService", "Error clearing force flag", e)
            }
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

                val authToken = sharedPref.getString("together_auth_token", "") ?: ""

                val postReq = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Authorization", "Bearer $authToken")
                    .post(reqBody)
                    .build()

                client.newCall(postReq).execute() // Execute in background
            }

            // Extract Mood
            val partnerState = globalState.optJSONObject(partnerName)
            val partnerMood = partnerState?.optString("mood", "--") ?: "--"

            // Extract Activity
            var partnerActivity = "--"
            val activities = partnerState?.optJSONArray("activities")
            if (activities != null && activities.length() > 0) {
                val latestActivity = activities.optJSONObject(activities.length() - 1)
                if (latestActivity != null) {
                    val type = latestActivity.optString("type", "")
                    if (type.isNotEmpty()) {
                        partnerActivity = formatActivityType(type)
                    }
                }
            }

            // Extract Points
            var myPoints = 0
            val couponStateObj = globalState.optJSONObject("couponState")
            if (couponStateObj != null) {
                val balances = couponStateObj.optJSONObject("balances")
                if (balances != null) {
                    myPoints = balances.optInt(localUserName, 0)
                }
            }

            // Extract Distance
            var distanceStr = "-- km"
            val myState = globalState.optJSONObject(localUserName)
            if (myState != null && partnerState != null) {
                var myLoc: JSONObject? = null
                var partnerLoc: JSONObject? = null

                try {
                    val locReq = Request.Builder().url("$firebaseUrl/locations/$localUserName.json").build()
                    val locRes = client.newCall(locReq).execute()
                    if (locRes.isSuccessful) {
                        val body = locRes.body?.string()
                        if (body != null && body != "null") myLoc = JSONObject(body)
                    }
                } catch (e: Exception) {}

                try {
                    val locReq = Request.Builder().url("$firebaseUrl/locations/$partnerName.json").build()
                    val locRes = client.newCall(locReq).execute()
                    if (locRes.isSuccessful) {
                        val body = locRes.body?.string()
                        if (body != null && body != "null") partnerLoc = JSONObject(body)
                    }
                } catch (e: Exception) {}

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

            val myIsStudying = myState?.optBoolean("isStudying", false) ?: false

            // Extract Game Scores
            var myGameScore = 0
            var partnerGameScore = 0
            val myGameData = myState?.optJSONObject("gameData")
            if (myGameData != null) {
                myGameScore = myGameData.optInt("totalScore", 0)
            }
            val partnerGameData = partnerState?.optJSONObject("gameData")
            if (partnerGameData != null) {
                partnerGameScore = partnerGameData.optInt("totalScore", 0)
            }

            // Save to SharedPreferences for Widget
            with(sharedPref.edit()) {
                putString("widget_distance", distanceStr)
                putString("widget_mood", partnerMood)
                putString("widget_streak", currentStreak.toString())
                putString("widget_activity", partnerActivity)
                putInt("widget_points", myPoints)
                putBoolean("widget_is_studying", myIsStudying)
                putInt("widget_my_game_score", myGameScore)
                putInt("widget_partner_game_score", partnerGameScore)
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
        broadcastDebugLog("CoupleService", "checkAndNotify called for $partnerName")

        // Skip notification if lastState is completely empty (first run) to prevent notification spam on startup
        if (lastState.length() == 0) {
            broadcastDebugLog("CoupleService", "Skipping checkAndNotify due to empty lastState")
            return
        }

        broadcastDebugLog("CoupleService", "Comparing current vs last state for notifications.")

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
                    sendNotification("$partnerName is $formattedType", "location.html")
                }
            }
        }

        if (currentState.has("mood")) {
            val currentMood = currentState.optString("mood", "")
            val lastMood = lastState.optString("mood", "")
            if (currentMood != lastMood && currentMood.isNotEmpty()) {
                sendNotification("$partnerName is feeling $currentMood", "mood.html")
            }
        }

        val currentLogs = currentState.optJSONArray("studyLogs")
        if (currentLogs != null) {
            val lastLogs = lastState.optJSONArray("studyLogs")
            val lastLogsCount = lastLogs?.length() ?: 0

            if (currentLogs.length() > lastLogsCount) {
                sendNotification("$partnerName finished studying! 📚", "study.html")
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
                sendNotification("$partnerName is playing games and scored points!", "games.html")
            }
        }

        val currentMessages = currentState.optJSONArray("messages")
        if (currentMessages != null) {
            val lastMessages = lastState.optJSONArray("messages")
            val lastMessagesCount = lastMessages?.length() ?: 0

            if (currentMessages.length() > lastMessagesCount) {
                sendNotification("$partnerName send you a message", "messages.html")
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


    private fun sendNotification(content: String, openPage: String? = null) {
        broadcastDebugLog("CoupleService", "Attempting to send notification: $content")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                broadcastDebugLog("CoupleService", "POST_NOTIFICATIONS permission not granted. Cannot send notification.")
                return
            }
        }

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
            if (openPage != null) {
                putExtra("openPage", openPage)
            }
        }
        val pendingIntentId = System.currentTimeMillis().toInt()
        val pendingIntent: PendingIntent = PendingIntent.getActivity(applicationContext, pendingIntentId, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

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
                broadcastDebugLog("CoupleService", "Notification permission not granted: ${e.message}"); Log.e("CoupleService", "Notification permission not granted", e)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("CoupleService", "App swiped away, scheduling restart via PollReceiver")

        // Schedule a quick restart via PollReceiver to keep background service alive
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, PollReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            this,
            1001,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent)
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1000, pendingIntent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false

        continuousLocationCallback?.let {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient.removeLocationUpdates(it)
        }

        heartbeatHandler?.removeCallbacksAndMessages(null)
        if (forceUpdateRef != null && forceUpdateListener != null) {
            forceUpdateRef?.removeEventListener(forceUpdateListener!!)
        }

        if (permanentWakeLock?.isHeld == true) {
            permanentWakeLock?.release()
        }
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, PollReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(this, 1001, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarmManager.cancel(pendingIntent)
    }
}
