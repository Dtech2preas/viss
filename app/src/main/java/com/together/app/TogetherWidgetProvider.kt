package com.together.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import org.json.JSONObject

class TogetherWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == AppWidgetManager.ACTION_APPWIDGET_UPDATE) {
            // Update all widgets
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = android.content.ComponentName(context, TogetherWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            for (appWidgetId in appWidgetIds) {
                updateAppWidget(context, appWidgetManager, appWidgetId)
            }
        } else if (intent.action == ACTION_STUDY_TOGGLE) {
            // Send intent to start or wake the service to handle the toggle safely without IllegalStateException
            // For Android 8.0+, we can't always start a background service from here, so we do the API call right here.
            val pendingResult = goAsync()
            kotlin.concurrent.thread {
                try {
                    CoupleService.handleStudyToggleStatic(context)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    companion object {
        const val ACTION_STUDY_TOGGLE = "com.together.app.ACTION_STUDY_TOGGLE"

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val sharedPref = context.getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
            val distanceStr = sharedPref.getString("widget_distance", "--")
            val distance = distanceStr?.replace(" km", "") ?: "--"
            val mood = sharedPref.getString("widget_mood", "🎭")
            val streak = sharedPref.getString("widget_streak", "0")
            val activity = sharedPref.getString("widget_activity", "--")
            val points = sharedPref.getInt("widget_points", 0)
            val isStudying = sharedPref.getBoolean("widget_is_studying", false)

            val profileJson = sharedPref.getString("togetherProfile", null)
            var partnerName = "Partner"
            var myName = "Me"
            if (!profileJson.isNullOrEmpty()) {
                try {
                    val profile = JSONObject(profileJson)
                    myName = profile.optString("name", "Me")
                    myName = myName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }

                    val partnerObj = profile.optJSONObject("partner")
                    if (partnerObj != null) {
                        partnerName = partnerObj.optString("name", "Partner")
                        partnerName = partnerName.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val views = RemoteViews(context.packageName, R.layout.widget_together)

            // Setup Header
            views.setTextViewText(R.id.widgetTitle, "🌟 $myName & $partnerName 🌟")

            // Top Grid Values
            views.setTextViewText(R.id.widgetDistanceValue, distance)
            views.setTextViewText(R.id.widgetStreakValue, streak)
            views.setTextViewText(R.id.widgetMoodEmoji, mood)

            // Middle section
            views.setTextViewText(R.id.widgetPoints, "💎 $points PTS 💎")

            // Activity status
            val activityText = if (activity != "--") "$partnerName is $activity" else "$partnerName status: --"
            views.setTextViewText(R.id.widgetActivity, activityText)

            // Study Button UI Setup
            if (isStudying) {
                views.setTextViewText(R.id.widgetStudyText, "STOP STUDY")
            } else {
                views.setTextViewText(R.id.widgetStudyText, "START STUDY")
            }

            // Create Intent for general widget click (Launch Main App)
            val appIntent = Intent(context, MainActivity::class.java)
            val appPendingIntent = PendingIntent.getActivity(
                context,
                0,
                appIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Apply main click listeners to header/bg
            views.setOnClickPendingIntent(R.id.widgetContainer, appPendingIntent)

            // Create Intent for Study Toggle Background Action
            val studyIntent = Intent(context, TogetherWidgetProvider::class.java).apply {
                action = ACTION_STUDY_TOGGLE
            }
            val studyPendingIntent = PendingIntent.getBroadcast(
                context,
                1,
                studyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Attach toggle click
            views.setOnClickPendingIntent(R.id.widgetStudyBtn, studyPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
