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

            val views = RemoteViews(context.packageName, R.layout.widget_together)
            views.setTextViewText(R.id.widgetDistance, "Distance: $distance")
            views.setTextViewText(R.id.widgetMood, "Mood: $mood")
            views.setTextViewText(R.id.widgetStreak, "Streak: $streak days")

            // Intent to launch app when clicking the widget
            val intent = Intent(context, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widgetTitle, pendingIntent)
            views.setOnClickPendingIntent(R.id.widgetDistance, pendingIntent)
            views.setOnClickPendingIntent(R.id.widgetMood, pendingIntent)
            views.setOnClickPendingIntent(R.id.widgetStreak, pendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
