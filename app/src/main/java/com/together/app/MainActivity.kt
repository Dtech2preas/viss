package com.together.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.app.AlarmManager
import android.os.Bundle

import android.database.Cursor
import android.provider.OpenableColumns
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.webkit.JavascriptInterface

import android.util.Log
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.webkit.ValueCallback
import android.app.Activity
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import android.provider.MediaStore
import android.os.Environment
import java.io.File
import androidx.core.content.FileProvider


import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private val audioPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            val uri: Uri? = result.data?.data
            uri?.let {
                contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val sharedPref = getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
                sharedPref.edit().putString("alarm_ringtone_uri", it.toString()).apply()
                var displayName = "Custom Ringtone"
                val cursor: Cursor? = contentResolver.query(it, null, null, null, null)
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            displayName = c.getString(nameIndex)
                        }
                    }
                }
                webView.evaluateJavascript("javascript:if(window.onRingtoneSelected){window.onRingtoneSelected('$displayName');}", null)
            }
        }
    }


    private lateinit var webView: WebView

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null

    private var photoUri: Uri? = null


    private val fileChooserLauncher = registerForActivityResult(StartActivityForResult()) { result ->
        if (fileUploadCallback == null) return@registerForActivityResult

        var results: Array<Uri>? = null
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = result.data
            if (intent == null || intent.data == null) {
                // If intent is null or intent.data is null, it might be the camera returning the image we gave it the URI for
                if (photoUri != null) {
                    results = arrayOf(photoUri!!)
                }
            } else {
                val dataString = intent.dataString
                if (dataString != null) {
                    results = arrayOf(Uri.parse(dataString))
                } else if (intent.clipData != null) {
                    val numSelectedFiles = intent.clipData!!.itemCount
                    results = Array(numSelectedFiles) { i ->
                        intent.clipData!!.getItemAt(i).uri
                    }
                }
            }
        }
        fileUploadCallback?.onReceiveValue(results)
        fileUploadCallback = null
        photoUri = null
    }


    private val partnerLocationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "PARTNER_LOCATION_UPDATE") {
                val lat = intent.getDoubleExtra("lat", Double.NaN)
                val lng = intent.getDoubleExtra("lng", Double.NaN)
                val timestamp = intent.getLongExtra("timestamp", 0L)

                if (!lat.isNaN() && !lng.isNaN()) {
                    val jsPayload = JSONObject()
                    jsPayload.put("lat", lat)
                    jsPayload.put("lng", lng)
                    jsPayload.put("timestamp", timestamp)

                    runOnUiThread {
                        webView.evaluateJavascript(
                            """
                            if (typeof updatePartnerLocationDisplay === 'function') {
                                updatePartnerLocationDisplay(${jsPayload.toString()});
                            }
                            """.trimIndent(), null
                        )
                    }
                }
            }
        }
    }


    private val debugLogReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.together.app.DEBUG_LOG") {
                val tag = intent.getStringExtra("tag") ?: "Unknown"
                val message = intent.getStringExtra("message") ?: ""
                val timestamp = intent.getLongExtra("timestamp", 0L)

                val jsPayload = JSONObject()
                jsPayload.put("tag", tag)
                jsPayload.put("message", message)
                jsPayload.put("timestamp", timestamp)

                val jsLogStr = JSONObject.quote("[$tag] $message")

                runOnUiThread {
                    webView.evaluateJavascript(
                        """
                        if (typeof window.receiveAndroidLog === 'function') {
                            window.receiveAndroidLog(${jsPayload.toString()});
                        }
                        if (typeof window.pushAndroidLog === 'function') {
                            window.pushAndroidLog($jsLogStr);
                        }
                        """.trimIndent(), null
                    )
                }
            }
        }
    }

    private val requestBackgroundLocationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                Log.d("MainActivity", "Background location granted")
            } else {
                Log.d("MainActivity", "Background location denied")
            }
            checkBatteryOptimizationAndAuthenticate()
        }

    // Register the permissions callback for multiple permissions
    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissions.entries.forEach {
                Log.d("MainActivity", "Permission ${it.key} granted: ${it.value}")
            }

            // After foreground location is requested, we can ask for background location
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Please select 'Allow all the time' for background updates.", Toast.LENGTH_LONG).show()
                    requestBackgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    checkBatteryOptimizationAndAuthenticate()
                }
            } else {
                checkBatteryOptimizationAndAuthenticate()
            }
        }

    private fun checkBatteryOptimizationAndAuthenticate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = android.net.Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to request battery optimization ignore", e)
                }
            }
        }



        // Authentication now happens after all permissions are processed
        authenticateUser()
    }

    @SuppressLint("SetJavaScriptEnabled", "UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        webView.visibility = View.INVISIBLE // Hide initially
        setContentView(webView)

        // Register Broadcast Receiver for Partner Location Updates
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(partnerLocationReceiver, IntentFilter("PARTNER_LOCATION_UPDATE"), Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(debugLogReceiver, IntentFilter("com.together.app.DEBUG_LOG"), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(partnerLocationReceiver, IntentFilter("PARTNER_LOCATION_UPDATE"))
            registerReceiver(debugLogReceiver, IntentFilter("com.together.app.DEBUG_LOG"))
        }

        // Request necessary permissions
        askForPermissions()

        // Enable Javascript and DOM storage
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        webSettings.setGeolocationEnabled(true)

        webView.webChromeClient = object : WebChromeClient() {

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback

                var takePictureIntent: Intent? = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                if (takePictureIntent?.resolveActivity(packageManager) != null) {
                    var photoFile: File? = null
                    try {
                        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                        photoFile = File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)

                        takePictureIntent.putExtra("PhotoPath", photoFile.absolutePath)
                    } catch (ex: Exception) {
                        Log.e("MainActivity", "Unable to create Image File", ex)
                    }

                    if (photoFile != null) {
                        photoUri = FileProvider.getUriForFile(
                            this@MainActivity,
                            "com.together.app.fileprovider",
                            photoFile
                        )
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                    } else {
                        takePictureIntent = null
                    }
                } else {
                    takePictureIntent = null
                }

                val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT)
                contentSelectionIntent.addCategory(Intent.CATEGORY_OPENABLE)
                contentSelectionIntent.type = "image/*"

                val intentArray: Array<Intent> = if (takePictureIntent != null) arrayOf(takePictureIntent) else emptyArray()

                val chooserIntent = Intent(Intent.ACTION_CHOOSER)
                chooserIntent.putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                chooserIntent.putExtra(Intent.EXTRA_TITLE, "Select an action")
                chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray)

                try {
                    fileChooserLauncher.launch(chooserIntent)
                    return true
                } catch (e: Exception) {
                    fileUploadCallback = null
                    return false
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback
            ) {
                // Grant geolocation permissions to the WebView
                callback.invoke(origin, true, false)
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                val requestedResources = request.resources
                val grantedResources = mutableListOf<String>()
                for (resource in requestedResources) {
                    if (resource == PermissionRequest.RESOURCE_VIDEO_CAPTURE || resource == PermissionRequest.RESOURCE_AUDIO_CAPTURE) {
                        grantedResources.add(resource)
                    }
                }
                if (grantedResources.isNotEmpty()) {
                    request.grant(grantedResources.toTypedArray())
                } else {
                    request.deny()
                }
            }
        }

        // Set WebViewClient to handle redirects within the WebView
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Inject JavaScript to intercept localStorage profile changes
                // We use setInterval because the page might redirect, load slowly, or the JS app might
                // not have set the value yet.
                view?.evaluateJavascript(
                    """
                    (function() {
                        if (window.androidBridgeInitialized) return;
                        window.androidBridgeInitialized = true;

                        var originalSetItem = localStorage.setItem;
                        localStorage.setItem = function(key, value) {
                            var event = new Event('itemInserted');
                            event.value = value;
                            event.key = key;
                            document.dispatchEvent(event);
                            originalSetItem.apply(this, arguments);
                            if (key === 'togetherProfile' && window.AndroidBridge) {
                                window.AndroidBridge.saveProfile(value);
                            }
                            if (key === 'together_auth_token' && window.AndroidBridge) {
                                window.AndroidBridge.saveAuthToken(value);
                            }
                            if (key === 'firebase_secret_path' && window.AndroidBridge) {
                                window.AndroidBridge.saveFirebaseSecretPath(value);
                            }
                        };

                        // Check on load in case it's already set
                        var profile = localStorage.getItem('togetherProfile');
                        if (profile && window.AndroidBridge) {
                            window.AndroidBridge.saveProfile(profile);
                        }
                        var token = localStorage.getItem('together_auth_token');
                        if (token && window.AndroidBridge) {
                            window.AndroidBridge.saveAuthToken(token);
                        }
                        var firebaseSecret = localStorage.getItem('firebase_secret_path');
                        if (firebaseSecret && window.AndroidBridge) {
                            window.AndroidBridge.saveFirebaseSecretPath(firebaseSecret);
                        }

                        // Polling fallback in case localStorage was written before injection
                        var lastProfile = profile;
                        var lastToken = token;
                        var lastFirebaseSecret = firebaseSecret;
                        setInterval(function() {
                            var currentProfile = localStorage.getItem('togetherProfile');
                            if (currentProfile && currentProfile !== lastProfile && window.AndroidBridge) {
                                lastProfile = currentProfile;
                                window.AndroidBridge.saveProfile(currentProfile);
                            }
                            var currentToken = localStorage.getItem('together_auth_token');
                            if (currentToken && currentToken !== lastToken && window.AndroidBridge) {
                                lastToken = currentToken;
                                window.AndroidBridge.saveAuthToken(currentToken);
                            }
                            var currentFirebaseSecret = localStorage.getItem('firebase_secret_path');
                            if (currentFirebaseSecret && currentFirebaseSecret !== lastFirebaseSecret && window.AndroidBridge) {
                                lastFirebaseSecret = currentFirebaseSecret;
                                window.AndroidBridge.saveFirebaseSecretPath(currentFirebaseSecret);
                            }
                        }, 2000);
                    })();
                    """.trimIndent(), null
                )
            }
        }

        // Add Javascript Interface
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        // Authenticate before loading URL
        }


    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(partnerLocationReceiver)
        unregisterReceiver(debugLogReceiver)
    }

    private fun authenticateUser() {
        val biometricManager = BiometricManager.from(this)
        val canAuthenticate = biometricManager.canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)

        if (canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS) {
            val executor = ContextCompat.getMainExecutor(this)
            val biometricPrompt = BiometricPrompt(this, executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        Toast.makeText(applicationContext, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                        finish() // Close app if authentication fails/is canceled
                    }

                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        onAuthenticationSuccessful()
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        Toast.makeText(applicationContext, "Authentication failed", Toast.LENGTH_SHORT).show()
                    }
                })

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Together")
                .setSubtitle("Use your biometric credential or PIN to unlock")
                .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
                .build()

            biometricPrompt.authenticate(promptInfo)
        } else {
            // Biometrics/PIN not enrolled or available, bypass
            onAuthenticationSuccessful()
        }
    }

    private fun onAuthenticationSuccessful() {
        webView.visibility = View.VISIBLE

        // Save today's date to mark user as active today
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = dateFormat.format(Date())

        val sharedPref = getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putString("myLastActiveDate", today)
            apply()
        }

        // Check if intent specifies a page to open, otherwise load you.html
        val openPage = intent.getStringExtra("openPage")
        if (openPage != null) {
            webView.loadUrl("https://together.preasx24.co.za/$openPage")
        } else {
            webView.loadUrl("https://together.preasx24.co.za/you.html")
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent) // update the original intent

        val openPage = intent?.getStringExtra("openPage")
        if (openPage != null && webView.visibility == View.VISIBLE) {
            webView.loadUrl("https://together.preasx24.co.za/$openPage")
        }
    }

    private fun askForPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val ungrantedPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungrantedPermissions.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(ungrantedPermissions.toTypedArray())
        } else {
            // Foreground permissions are already granted, check background
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "Please select 'Allow all the time' for reliable background tracking.", Toast.LENGTH_LONG).show()
                    requestBackgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    checkBatteryOptimizationAndAuthenticate()
                }
            } else {
                checkBatteryOptimizationAndAuthenticate()
            }
        }
    }

    // Handle back button for WebView navigation
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun hasExactAlarmPermission(): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                return alarmManager.canScheduleExactAlarms()
            }
            return true
        }

        @JavascriptInterface
        fun requestExactAlarmPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    intent.data = android.net.Uri.parse("package:${context.packageName}")
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e("WebAppInterface", "Failed to request exact alarm permission", e)
                }
            }
        }

        @JavascriptInterface
        fun selectAlarmRingtone() {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
            }
            (context as MainActivity).audioPickerLauncher.launch(intent)
        }

        @JavascriptInterface
        fun rescheduleAlarms(time: String, enabled: Boolean) {
            val sharedPref = context.getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
            sharedPref.edit().putString("alarm_time", time).putBoolean("alarm_enabled", enabled).apply()
            val intent = Intent(context, CoupleService::class.java).apply {
                action = "UPDATE_ALARM"
            }
            context.startService(intent)
        }
        @JavascriptInterface
        fun getBackgroundLogs(): String {
            val sharedPref = context.getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
            val logs = sharedPref.getString("background_logs", "[]") ?: "[]"
            // Clear after reading so we don't load same logs twice
            sharedPref.edit().putString("background_logs", "[]").apply()
            return logs
        }

        @JavascriptInterface
        fun saveProfile(profileJson: String) {
            Log.d("WebAppInterface", "Profile saved: $profileJson")
            val sharedPref = context.getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putString("togetherProfile", profileJson)
                apply()
            }

            // Start or restart WorkManager since we have a new profile
            startBackgroundWork(context)
        }

        @JavascriptInterface
        fun saveAuthToken(token: String) {
            Log.d("WebAppInterface", "Auth token saved")
            val sharedPref = context.getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putString("together_auth_token", token)
                apply()
            }
        }

        @JavascriptInterface
        fun saveFirebaseSecretPath(secretPath: String) {
            Log.d("WebAppInterface", "Firebase secret path saved")
            val sharedPref = context.getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
            with(sharedPref.edit()) {
                putString("firebase_secret_path", secretPath)
                apply()
            }
        }

        private fun startBackgroundWork(context: Context) {
            val serviceIntent = Intent(context, CoupleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
