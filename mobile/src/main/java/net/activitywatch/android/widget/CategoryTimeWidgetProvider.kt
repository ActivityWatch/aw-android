package net.activitywatch.android.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.activitywatch.android.R
import net.activitywatch.android.watcher.UsageStatsWatcher

private const val TAG = "CategoryTimeWidget"
private const val ACTION_REFRESH = "net.activitywatch.android.widget.ACTION_REFRESH"
const val ACTION_PERIODIC_UPDATE = "net.activitywatch.android.widget.ACTION_PERIODIC_UPDATE"
private const val UPDATE_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

/**
 * Widget provider for displaying category time stats.
 * Updates are handled every 15 mins by the work manager and every 30 mins by
 * Android's widget update mechanism.
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
        when (intent.action) {
            ACTION_REFRESH -> {
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
            ACTION_PERIODIC_UPDATE -> {
                Log.d(TAG, "Periodic update triggered by AlarmManager")
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        CategoryTimeWidgetUpdater.updateAllWidgets(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error during periodic update", e)
                    } finally {
                        // Reschedule the next exact alarm
                        schedulePeriodicUpdates(context)
                        pendingResult.finish()
                    }
                }
            }
        }
    }

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        Log.d(TAG, "Widget enabled - scheduling 5-minute background updates")
        schedulePeriodicUpdates(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Log.d(TAG, "Widget disabled - cancelling background updates")
        cancelPeriodicUpdates(context)
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
            CategoryTimeWidgetUpdater.updateSingleWidget(context, appWidgetManager, appWidgetId)
        }

        private fun getUpdateIntent(context: Context): PendingIntent {
            val intent = Intent(context, CategoryTimeWidgetProvider::class.java).apply {
                action = ACTION_PERIODIC_UPDATE
            }
            return PendingIntent.getBroadcast(
                context,
                1,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun schedulePeriodicUpdates(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = getUpdateIntent(context)

            // Start exact alarm that fires once
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + UPDATE_INTERVAL_MS,
                    pendingIntent
                )
                Log.d(TAG, "Scheduled exact AlarmManager update in 5 minutes")
            } catch (e: SecurityException) {
                // In Android 14+, SCHEDULE_EXACT_ALARM might be revoked by user
                Log.e(TAG, "Cannot schedule exact alarm without permission, falling back to inexact", e)
                alarmManager.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + UPDATE_INTERVAL_MS,
                    UPDATE_INTERVAL_MS,
                    pendingIntent
                )
            }
        }

        fun cancelPeriodicUpdates(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = getUpdateIntent(context)
            alarmManager.cancel(pendingIntent)
            Log.d(TAG, "Cancelled AlarmManager periodic updates")
        }
    }
}
