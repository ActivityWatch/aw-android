package net.activitywatch.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.RemoteViews
import net.activitywatch.android.R

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
            Log.d(TAG, "Refresh button clicked - updating all widgets")
            
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, CategoryTimeWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            
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
         * Update widget and set up the refresh button click handler
         */
        fun updateWidgetWithRefreshButton(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            // First update the widget data
            CategoryTimeWidgetWorker.updateSingleWidget(context, appWidgetManager, appWidgetId)
            
            // Then set up the refresh button click handler
            val views = RemoteViews(context.packageName, R.layout.widget_category_time)
            
            val refreshIntent = Intent(context, CategoryTimeWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh_button, refreshPendingIntent)
            
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
