package com.together.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
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

    private lateinit var webView: WebView

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
            checkBatteryOptimization()
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
                    checkBatteryOptimization()
                }
            } else {
                checkBatteryOptimization()
            }
        }

    private fun checkBatteryOptimization() {
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

                        // Polling fallback in case localStorage was written before injection
                        var lastProfile = profile;
                        var lastToken = token;
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
                        }, 2000);
                    })();
                    """.trimIndent(), null
                )
            }
        }

        // Add Javascript Interface
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        // Authenticate before loading URL
        authenticateUser()
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
                    checkBatteryOptimization()
                }
            } else {
                checkBatteryOptimization()
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
