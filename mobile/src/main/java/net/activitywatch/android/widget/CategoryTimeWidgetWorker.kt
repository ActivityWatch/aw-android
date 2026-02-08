package net.activitywatch.android.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import net.activitywatch.android.R
import net.activitywatch.android.RustInterface
import com.jakewharton.threetenabp.AndroidThreeTen
import org.json.JSONArray
import org.threeten.bp.LocalDate
import org.threeten.bp.LocalDateTime
import org.threeten.bp.ZoneId
import org.threeten.bp.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

private const val TAG = "CategoryTimeWidget"
private const val WORK_NAME = "category_time_widget_update"

// Bar chart dimensions
private const val BAR_WIDTH = 400
private const val BAR_HEIGHT = 24
private const val BAR_CORNER_RADIUS = 12f

// Category colors (matching the dots)
private val CATEGORY_COLORS = intArrayOf(
    Color.parseColor("#00BFA5"),  // Teal - category 1
    Color.parseColor("#7986CB"),  // Purple - category 2
    Color.parseColor("#42A5F5"),  // Blue - category 3
    Color.parseColor("#424242")   // Gray - others
)

/**
 * Worker that updates the widget in the background every 30 minutes
 */
class CategoryTimeWidgetWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "WorkManager: Updating widget in background")
        
        return try {
            updateAllWidgets(context)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "WorkManager: Error updating widget", e)
            Result.retry()
        }
    }

    companion object {
        // App row IDs
        private val appRowIds = intArrayOf(
            R.id.app_row_1,
            R.id.app_row_2,
            R.id.app_row_3
        )

        private val appNameIds = intArrayOf(
            R.id.app_name_1,
            R.id.app_name_2,
            R.id.app_name_3
        )

        private val appTimeIds = intArrayOf(
            R.id.app_time_1,
            R.id.app_time_2,
            R.id.app_time_3
        )

        /**
         * Schedule periodic widget updates using WorkManager
         */
        fun schedulePeriodicUpdates(context: Context) {
            val workRequest = PeriodicWorkRequestBuilder<CategoryTimeWidgetWorker>(
                30, TimeUnit.MINUTES
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
            Log.d(TAG, "Scheduled periodic widget updates every 30 minutes")
        }

        /**
         * Cancel periodic widget updates
         */
        fun cancelPeriodicUpdates(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled periodic widget updates")
        }

        /**
         * Update all instances of the widget
         */
        fun updateAllWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, CategoryTimeWidgetProvider::class.java)
            val appWidgetIds = appWidgetManager.getAppWidgetIds(componentName)
            
            Log.d(TAG, "Updating ${appWidgetIds.size} widget instances")
            
            for (appWidgetId in appWidgetIds) {
                updateSingleWidget(context, appWidgetManager, appWidgetId)
            }
        }

        /**
         * Update a single widget instance
         */
        fun updateSingleWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            Log.d(TAG, "Updating widget $appWidgetId")

            val views = RemoteViews(context.packageName, R.layout.widget_category_time)

            try {
                // Initialize ThreeTenABP timezone data (required for widgets running in background)
                AndroidThreeTen.init(context)
                
                val ri = RustInterface(context)
                val categoryData = getCategoryTimesToday(ri)
                val totalMillis = categoryData.sumOf { it.second }

                // Update total time (hours and minutes separately)
                val totalSeconds = totalMillis / 1000
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                views.setTextViewText(R.id.widget_hours, hours.toString())
                views.setTextViewText(R.id.widget_minutes, minutes.toString())

                // Draw and set the bar chart
                val barChartBitmap = createBarChartBitmap(categoryData, totalMillis)
                views.setImageViewBitmap(R.id.widget_bar_chart, barChartBitmap)

                // Update top 3 apps
                val topApps = categoryData.take(3)
                
                for (i in 0 until 3) {
                    if (i < topApps.size) {
                        val (name, duration) = topApps[i]
                        views.setTextViewText(appNameIds[i], name)
                        views.setTextViewText(appTimeIds[i], formatDurationShort(duration))
                        views.setViewVisibility(appRowIds[i], View.VISIBLE)
                    } else {
                        views.setViewVisibility(appRowIds[i], View.GONE)
                    }
                }

                // Update timestamp
                val now = LocalDateTime.now()
                val timestampFormatter = DateTimeFormatter.ofPattern("M/d, HH:mm")
                views.setTextViewText(R.id.widget_timestamp, timestampFormatter.format(now))

                Log.d(TAG, "Widget updated successfully with ${topApps.size} apps, total: ${hours}h ${minutes}m")
            } catch (e: Exception) {
                Log.e(TAG, "Error updating widget", e)
                views.setTextViewText(R.id.widget_hours, "0")
                views.setTextViewText(R.id.widget_minutes, "0")
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }

        /**
         * Create a bitmap with the stacked bar chart
         */
        private fun createBarChartBitmap(
            categoryData: List<Pair<String, Long>>,
            totalMillis: Long
        ): Bitmap {
            val bitmap = Bitmap.createBitmap(BAR_WIDTH, BAR_HEIGHT, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint().apply {
                isAntiAlias = true
                style = Paint.Style.FILL
            }

            if (totalMillis == 0L || categoryData.isEmpty()) {
                // Draw empty bar with gray background
                paint.color = CATEGORY_COLORS[3]
                canvas.drawRoundRect(
                    RectF(0f, 0f, BAR_WIDTH.toFloat(), BAR_HEIGHT.toFloat()),
                    BAR_CORNER_RADIUS,
                    BAR_CORNER_RADIUS,
                    paint
                )
                return bitmap
            }

            // Calculate proportions for top 3 + others
            val top3 = categoryData.take(3)
            val top3Total = top3.sumOf { it.second }
            val othersTotal = totalMillis - top3Total
            
            // Draw segments
            var currentX = 0f
            
            for (i in top3.indices) {
                val proportion = top3[i].second.toFloat() / totalMillis
                val segmentWidth = proportion * BAR_WIDTH
                
                paint.color = CATEGORY_COLORS[i]
                
                // For first segment, include left rounded corners
                // For last segment (or if no others), include right rounded corners
                val isFirst = i == 0
                val isLast = i == top3.lastIndex && othersTotal <= 0
                
                if (isFirst && isLast) {
                    // Only segment - full rounded corners
                    canvas.drawRoundRect(
                        RectF(currentX, 0f, currentX + segmentWidth, BAR_HEIGHT.toFloat()),
                        BAR_CORNER_RADIUS,
                        BAR_CORNER_RADIUS,
                        paint
                    )
                } else if (isFirst) {
                    // First segment - rounded left corners
                    canvas.drawRoundRect(
                        RectF(currentX, 0f, currentX + segmentWidth + BAR_CORNER_RADIUS, BAR_HEIGHT.toFloat()),
                        BAR_CORNER_RADIUS,
                        BAR_CORNER_RADIUS,
                        paint
                    )
                    // Cover the right rounded corners with a rect
                    canvas.drawRect(
                        currentX + segmentWidth,
                        0f,
                        currentX + segmentWidth + BAR_CORNER_RADIUS,
                        BAR_HEIGHT.toFloat(),
                        paint
                    )
                } else if (isLast) {
                    // Last segment - rounded right corners
                    canvas.drawRoundRect(
                        RectF(currentX - BAR_CORNER_RADIUS, 0f, currentX + segmentWidth, BAR_HEIGHT.toFloat()),
                        BAR_CORNER_RADIUS,
                        BAR_CORNER_RADIUS,
                        paint
                    )
                    // Cover the left rounded corners with rect
                    canvas.drawRect(
                        currentX - BAR_CORNER_RADIUS,
                        0f,
                        currentX,
                        BAR_HEIGHT.toFloat(),
                        paint
                    )
                } else {
                    // Middle segment - no rounded corners
                    canvas.drawRect(
                        currentX,
                        0f,
                        currentX + segmentWidth,
                        BAR_HEIGHT.toFloat(),
                        paint
                    )
                }
                
                currentX += segmentWidth
            }
            
            // Draw "others" segment if there's remaining time
            if (othersTotal > 0) {
                val proportion = othersTotal.toFloat() / totalMillis
                val segmentWidth = proportion * BAR_WIDTH
                
                paint.color = CATEGORY_COLORS[3]
                
                // Last segment with rounded right corners
                canvas.drawRoundRect(
                    RectF(currentX - BAR_CORNER_RADIUS, 0f, currentX + segmentWidth, BAR_HEIGHT.toFloat()),
                    BAR_CORNER_RADIUS,
                    BAR_CORNER_RADIUS,
                    paint
                )
                // Cover left rounded corners
                canvas.drawRect(
                    currentX - BAR_CORNER_RADIUS,
                    0f,
                    currentX,
                    BAR_HEIGHT.toFloat(),
                    paint
                )
            }

            return bitmap
        }

        /**
         * Query today's category times using androidQuery (same as MainActivity.onCreate)
         */
        private fun getCategoryTimesToday(ri: RustInterface): List<Pair<String, Long>> {
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME

            val startOfDay = today.atStartOfDay(zone)
            val endOfDay = today.plusDays(1).atStartOfDay(zone)

            // Build time interval in the same format as RustInterface.test()
            val timeperiod = "[\"${formatter.format(startOfDay)}/${formatter.format(endOfDay)}\"]"
            Log.d(TAG, "Querying for timeperiod: $timeperiod")

            // Use androidQuery - the same function called in MainActivity.onCreate via RustInterface.test()
            val result = ri.androidQuery(timeperiod)
            Log.d(TAG, "Query result length: ${result.length}")

            return parseCategories(result)
        }

        /**
         * Parse category events from the androidQuery response
         */
        private fun parseCategories(jsonResult: String): List<Pair<String, Long>> {
            val categories = mutableMapOf<String, Double>()

            try {
                val resultArray = JSONArray(jsonResult)
                if (resultArray.length() == 0) return emptyList()

                val periodResult = resultArray.getJSONObject(0)
                val catEvents = periodResult.optJSONArray("cat_events") ?: return emptyList()

                for (i in 0 until catEvents.length()) {
                    val event = catEvents.getJSONObject(i)
                    val duration = event.optDouble("duration", 0.0)
                    val data = event.optJSONObject("data") ?: continue
                    
                    // Get category array from data
                    val categoryArray = data.optJSONArray("\$category")
                    if (categoryArray == null || categoryArray.length() == 0) continue

                    // Get top-level category (first element only)
                    val topLevelCategory = categoryArray.optString(0, "Uncategorized")
                    
                    // Aggregate time by top-level category
                    categories[topLevelCategory] = categories.getOrDefault(topLevelCategory, 0.0) + duration
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing categories", e)
            }

            // Sort by duration descending and convert to milliseconds
            return categories.entries
                .sortedByDescending { it.value }
                .map { Pair(it.key, (it.value * 1000).toLong()) }
        }

        /**
         * Format duration in short format (e.g., "4 h 23 m" or "15 m")
         */
        private fun formatDurationShort(millis: Long): String {
            val totalSeconds = millis / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60

            return when {
                hours > 0 -> "${hours} h ${minutes} m"
                minutes > 0 -> "${minutes} m"
                else -> "<1 m"
            }
        }
    }
}
