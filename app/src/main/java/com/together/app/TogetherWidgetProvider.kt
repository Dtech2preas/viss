package com.together.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

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
        }
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val sharedPref = context.getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
            val distance = sharedPref.getString("widget_distance", "-- km")
            val mood = sharedPref.getString("widget_mood", "--")
            val streak = sharedPref.getString("widget_streak", "--")
            val activity = sharedPref.getString("widget_activity", "--")
            val points = sharedPref.getInt("widget_points", 0)

            val profileJson = sharedPref.getString("togetherProfile", null)
            var partnerName = "Partner"
            if (!profileJson.isNullOrEmpty()) {
                try {
                    val profile = org.json.JSONObject(profileJson)
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

            // Just use the distance output from CoupleService (e.g. "12 km" or "-- km")
            // Wait, CoupleService creates distanceStr as "X km". Let's format it properly.
            views.setTextViewText(R.id.widgetDistance, "📍 $distance")
            views.setTextViewText(R.id.widgetMood, "🎭 $mood")
            views.setTextViewText(R.id.widgetStreak, "🔥 $streak days")
            views.setTextViewText(R.id.widgetPoints, "💰 $points pts")

            val activityText = if (activity != "--") "$partnerName is $activity" else "$partnerName's activity: --"
            views.setTextViewText(R.id.widgetActivity, activityText)

            // Intent to launch app when clicking the widget
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Make the entire widget clickable
            views.setOnClickPendingIntent(R.id.widgetContainer, pendingIntent)
            views.setOnClickPendingIntent(R.id.widgetTitle, pendingIntent)
            views.setOnClickPendingIntent(R.id.widgetDistance, pendingIntent)
            views.setOnClickPendingIntent(R.id.widgetMood, pendingIntent)
            views.setOnClickPendingIntent(R.id.widgetStreak, pendingIntent)
            views.setOnClickPendingIntent(R.id.widgetPoints, pendingIntent)
            views.setOnClickPendingIntent(R.id.widgetActivity, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
