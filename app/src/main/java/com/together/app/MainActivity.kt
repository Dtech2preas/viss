package com.together.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.content.Intent
import android.util.Log
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var executor: Executor
    private lateinit var biometricPrompt: BiometricPrompt
    private lateinit var promptInfo: BiometricPrompt.PromptInfo

    private var isUnlocked = false
    private var isShowingPrompt = false

    // Register the permissions callback for multiple permissions
    private val requestMultiplePermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissions.entries.forEach {
                Log.d("MainActivity", "Permission ${it.key} granted: ${it.value}")
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

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
                        };

                        // Check on load in case it's already set
                        var profile = localStorage.getItem('togetherProfile');
                        if (profile && window.AndroidBridge) {
                            window.AndroidBridge.saveProfile(profile);
                        }

                        // Polling fallback in case localStorage was written before injection
                        var lastProfile = profile;
                        setInterval(function() {
                            var currentProfile = localStorage.getItem('togetherProfile');
                            if (currentProfile && currentProfile !== lastProfile && window.AndroidBridge) {
                                lastProfile = currentProfile;
                                window.AndroidBridge.saveProfile(currentProfile);
                            }
                        }, 2000);
                    })();
                    """.trimIndent(), null
                )
            }
        }

        // Add Javascript Interface
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")
    }

    override fun onResume() {
        super.onResume()
        if (!isUnlocked && !isShowingPrompt) {
            webView.visibility = View.INVISIBLE
            setupBiometricAuth()
        }
    }

    override fun onStop() {
        super.onStop()
        isUnlocked = false
        webView.visibility = View.INVISIBLE
    }

    private fun setupBiometricAuth() {
        isShowingPrompt = true
        val biometricManager = BiometricManager.from(this)
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                Log.d("MainActivity", "App can authenticate using biometrics.")
                showBiometricPrompt()
            }
            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                Log.e("MainActivity", "No biometric features available on this device.")
                handleBiometricFallback()
            }
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> {
                Log.e("MainActivity", "Biometric features are currently unavailable.")
                handleBiometricFallback()
            }
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                Log.e("MainActivity", "The user hasn't associated any biometric credentials with their account.")
                handleBiometricFallback()
            }
            else -> {
                handleBiometricFallback()
            }
        }
    }

    private fun handleBiometricFallback() {
        isShowingPrompt = false
        isUnlocked = true
        webView.visibility = View.VISIBLE
        loadWebView()
    }

    private fun showBiometricPrompt() {
        executor = ContextCompat.getMainExecutor(this)
        biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    isShowingPrompt = false
                    Toast.makeText(applicationContext, "Authentication error: $errString", Toast.LENGTH_SHORT).show()
                    // If they fail or cancel, close the app
                    finish()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    isShowingPrompt = false
                    isUnlocked = true
                    webView.visibility = View.VISIBLE
                    Toast.makeText(applicationContext, "Authentication succeeded!", Toast.LENGTH_SHORT).show()

                    // Mark as active today for streak tracking
                    val sharedPref = applicationContext.getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                    with(sharedPref.edit()) {
                        putString("last_active_date", today)
                        apply()
                    }

                    loadWebView()
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    Toast.makeText(applicationContext, "Authentication failed", Toast.LENGTH_SHORT).show()
                }
            })

        promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Together")
            .setSubtitle("Use your biometric credential to unlock")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun loadWebView() {
        // Load the initial HTML file only if it hasn't been loaded
        if (webView.url == null) {
            webView.loadUrl("https://together.preasx24.co.za/you.html")
        }
    }

    private fun askForPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val ungrantedPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (ungrantedPermissions.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(ungrantedPermissions.toTypedArray())
        }
    }

    // Handle back button for WebView navigation
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    class WebAppInterface(private val context: Context) {
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
