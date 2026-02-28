package com.together.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Register the permissions callback, which handles the user's response to the
    // system permissions dialog.
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                Log.d("MainActivity", "Notification permission granted.")
            } else {
                Log.d("MainActivity", "Notification permission denied.")
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        // Request notification permission for Android 13+
        askNotificationPermission()

        // Enable Javascript and DOM storage
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true

        // Set WebViewClient to handle redirects within the WebView
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Inject JavaScript to intercept localStorage profile changes
                view?.evaluateJavascript(
                    """
                    (function() {
                        var originalSetItem = localStorage.setItem;
                        localStorage.setItem = function(key, value) {
                            var event = new Event('itemInserted');
                            event.value = value;
                            event.key = key;
                            document.dispatchEvent(event);
                            originalSetItem.apply(this, arguments);
                            if (key === 'togetherProfile') {
                                AndroidBridge.saveProfile(value);
                            }
                        };

                        // Also check on load in case it's already set
                        var profile = localStorage.getItem('togetherProfile');
                        if (profile) {
                            AndroidBridge.saveProfile(profile);
                        }
                    })();
                    """.trimIndent(), null
                )
            }
        }

        // Add Javascript Interface
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        // Load the initial HTML file
        webView.loadUrl("file:///android_asset/you.html")
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                // Permission is already granted
            } else if (shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                // You can show an educational UI here, then request the permission.
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                // Directly ask for the permission
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
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
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val pollRequest = PeriodicWorkRequestBuilder<CoupleWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "CoupleWorker",
                ExistingPeriodicWorkPolicy.UPDATE,
                pollRequest
            )
        }
    }
}
