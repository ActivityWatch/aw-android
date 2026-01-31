package net.activitywatch.android.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.util.Log

private const val TAG = "CategoryTimeWidget"

/**
 * Widget provider for displaying category time stats.
 * Updates are handled every 30 minutes via both Android's widget update mechanism
 * and WorkManager for reliability.
 */
class CategoryTimeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "onUpdate called for ${appWidgetIds.size} widgets")
        
        for (appWidgetId in appWidgetIds) {
            CategoryTimeWidgetWorker.updateSingleWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onEnabled(context: Context) {
        Log.d(TAG, "Widget enabled - scheduling 30-minute background updates")
        CategoryTimeWidgetWorker.schedulePeriodicUpdates(context)
    }

    override fun onDisabled(context: Context) {
        Log.d(TAG, "Widget disabled - cancelling background updates")
        CategoryTimeWidgetWorker.cancelPeriodicUpdates(context)
    }
}
