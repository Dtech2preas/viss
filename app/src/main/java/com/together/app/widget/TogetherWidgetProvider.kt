package com.together.app.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.together.app.R

class TogetherWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Iterate through all widgets (if user added multiple)
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "com.together.app.ACTION_UPDATE_WIDGET") {
            val distance = intent.getStringExtra("distance") ?: "Updating..."
            val mood = intent.getStringExtra("mood") ?: "Updating..."
            val streak = intent.getIntExtra("streak", 0)

            val appWidgetManager = AppWidgetManager.getInstance(context)
            val thisWidget = ComponentName(context, TogetherWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(thisWidget)

            for (appWidgetId in appWidgetIds) {
                val views = RemoteViews(context.packageName, R.layout.widget_together)
                views.setTextViewText(R.id.widget_distance, distance)
                views.setTextViewText(R.id.widget_mood, mood)
                views.setTextViewText(R.id.widget_streak, "$streak days 🔥")
                appWidgetManager.updateAppWidget(appWidgetId, views)
            }
        }
    }

    companion object {
        internal fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val sharedPref = context.getSharedPreferences("TogetherPrefs", Context.MODE_PRIVATE)
            val distance = sharedPref.getString("widget_distance", "Unknown")
            val mood = sharedPref.getString("widget_mood", "Unknown")
            val streak = sharedPref.getInt("widget_streak", 0)

            val views = RemoteViews(context.packageName, R.layout.widget_together)
            views.setTextViewText(R.id.widget_distance, distance)
            views.setTextViewText(R.id.widget_mood, mood)
            views.setTextViewText(R.id.widget_streak, "$streak days 🔥")

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
