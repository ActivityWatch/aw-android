package net.activitywatch.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import net.activitywatch.android.R
import net.activitywatch.android.watcher.UsageStatsWatcher

private const val TAG = "CategoryTimeWidget"
private const val ACTION_REFRESH = "net.activitywatch.android.widget.ACTION_REFRESH"

/**
 * Widget provider for displaying category time stats.
 * Updates are handled every 30 minutes via both Android's widget update mechanism
 * and WorkManager for reliability. Also supports manual refresh via button click.
 */
class CategoryTimeWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        Log.d(TAG, "onUpdate called for ${appWidgetIds.size} widgets")
        
        for (appWidgetId in appWidgetIds) {
            updateWidgetWithRefreshButton(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        
        if (intent.action == ACTION_REFRESH) {
            Log.d(TAG, "Refresh button clicked - re-parsing usage events and updating widgets")
            
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, CategoryTimeWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            
            // Show loading indicator first
            for (appWidgetId in appWidgetIds) {
                showLoadingState(context, appWidgetManager, appWidgetId)
            }
            
            // Re-parse usage events
            try {
                val usageStatsWatcher = UsageStatsWatcher(context)
                usageStatsWatcher.sendHeartbeats()
                Log.d(TAG, "Triggered usage events re-parsing")
            } catch (e: Exception) {
                Log.e(TAG, "Error re-parsing usage events", e)
            }
            
            // Then update with fresh data
            for (appWidgetId in appWidgetIds) {
                updateWidgetWithRefreshButton(context, appWidgetManager, appWidgetId)
            }
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

    companion object {
        /**
         * Show loading state - show spinner
         */
        private fun showLoadingState(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_category_time)
            views.setViewVisibility(R.id.widget_loading_indicator, View.VISIBLE)
            appWidgetManager.updateAppWidget(appWidgetId, views)
            Log.d(TAG, "Showing loading indicator for widget $appWidgetId")
        }

        /**
         * Update widget data and set up tap-to-refresh on the whole widget
         */
        fun updateWidgetWithRefreshButton(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            // updateSingleWidget handles data, click handler, and loading state
            CategoryTimeWidgetWorker.updateSingleWidget(context, appWidgetManager, appWidgetId)
        }
    }
}
