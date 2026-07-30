package com.together.app

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import android.widget.Button
import android.widget.TextView
import android.widget.LinearLayout
import android.view.Gravity
import android.graphics.Color

class AlarmActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        showWhenLockedAndTurnScreenOn()
        super.onCreate(savedInstanceState)

        // Create UI programmatically
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#ff9a9e"))
        }

        val titleView = TextView(this).apply {
            text = "Together Alarm ⏰"
            textSize = 32f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 100)
        }

        val stopButton = Button(this).apply {
            text = "Stop Alarm"
            textSize = 24f
            setBackgroundColor(Color.WHITE)
            setTextColor(Color.parseColor("#ff9a9e"))
            setPadding(64, 32, 64, 32)
            setOnClickListener {
                stopAlarmAndFinish()
            }
        }

        layout.addView(titleView)
        layout.addView(stopButton)

        setContentView(layout)
    }

    private fun showWhenLockedAndTurnScreenOn() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
    }

    private fun stopAlarmAndFinish() {
        val serviceIntent = Intent(this, CoupleService::class.java).apply {
            action = "STOP_ALARM"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // If the activity is destroyed (e.g. swiped away), we still want to make sure the alarm stops
        val serviceIntent = Intent(this, CoupleService::class.java).apply {
            action = "STOP_ALARM"
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
