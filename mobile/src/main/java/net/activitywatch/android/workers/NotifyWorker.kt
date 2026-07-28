package net.activitywatch.android.workers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.jakewharton.threetenabp.AndroidThreeTen
import net.activitywatch.android.R
import net.activitywatch.android.RustInterface
import org.json.JSONArray
import org.json.JSONException
import org.threeten.bp.LocalDate
import org.threeten.bp.LocalDateTime
import org.threeten.bp.ZoneId
import org.threeten.bp.format.DateTimeFormatter

private const val TAG = "NotifyWorker"
private const val CHANNEL_ID = "aw_notify_channel"
private const val PREFS_NAME = "aw_notify_prefs"
private const val DEFAULT_START_OF_DAY_HOUR = 4

// Mirrors desktop aw-notify CategoryAlert semantics.
// positive=true → "Goal reached!" title; false → "Time spent"
internal data class CategoryAlert(
    val category: String?,   // null = aggregate "All" time
    val label: String,
    val thresholdMinutes: List<Int>,
    val positive: Boolean = false
)

private val DEFAULT_ALERTS = listOf(
    CategoryAlert(null,       "All",        listOf(60, 240, 480)),
    CategoryAlert("Work",     "💼 Work",    listOf(60, 120, 240), positive = true),
    CategoryAlert("Twitter",  "🐦 Twitter", listOf(15, 60)),
    CategoryAlert("YouTube",  "📺 YouTube", listOf(30, 60)),
)

internal fun logicalDayDate(now: LocalDateTime, startOfDayHour: Int): LocalDate =
    if (now.hour < startOfDayHour) now.toLocalDate().minusDays(1) else now.toLocalDate()

internal fun parseAlerts(json: String): List<CategoryAlert> {
    val arr = JSONArray(json)
    return (0 until arr.length()).mapNotNull { i ->
        val obj = arr.getJSONObject(i)
        val category = if (obj.isNull("category")) null else obj.optString("category").ifEmpty { null }
        val label = obj.optString("label").ifEmpty { return@mapNotNull null }
        val thresholdsArr = obj.optJSONArray("thresholdMinutes") ?: return@mapNotNull null
        val thresholds = (0 until thresholdsArr.length()).map { thresholdsArr.getInt(it) }
        val positive = obj.optBoolean("positive", false)
        CategoryAlert(category, label, thresholds, positive)
    }
}

// Include thresholds in the pref key so state resets when configuration changes.
// A lowered threshold mid-day would otherwise be silently skipped because the old
// triggered value is higher than all new thresholds.
internal fun alertConfigHash(alert: CategoryAlert): Int =
    (alert.thresholdMinutes.toString() + alert.positive.toString())
        .hashCode().and(0x3FFFFFFF)

class NotifyWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        Log.i(TAG, "Starting category time notification check")
        AndroidThreeTen.init(applicationContext)

        val ri = RustInterface(applicationContext)

        // Check server availability before querying
        try {
            ri.getBucketsJSON()
        } catch (e: JSONException) {
            Log.w(TAG, "Server not reachable; retrying later")
            return Result.retry()
        }

        return try {
            val zone = ZoneId.systemDefault()
            val startOfDayHour = fetchStartOfDayHour()
            val now = LocalDateTime.now(zone)
            val categorySeconds = getCategorySecondsToday(ri, now, zone, startOfDayHour)
            val alerts = fetchAlerts()
            checkAndNotify(categorySeconds, logicalDayDate(now, startOfDayHour), alerts)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking notification thresholds", e)
            Result.retry()
        }
    }

    private fun getCategorySecondsToday(
        ri: RustInterface,
        now: LocalDateTime,
        zone: ZoneId,
        startOfDayHour: Int,
    ): Map<String?, Double> {
        val formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val logicalDate = logicalDayDate(now, startOfDayHour)
        val startOfDay = logicalDate.atStartOfDay(zone).plusHours(startOfDayHour.toLong())
        val endOfDay = startOfDay.plusDays(1)

        val timeperiod = "[\"${formatter.format(startOfDay)}/${formatter.format(endOfDay)}\"]"
        Log.d(TAG, "Querying timeperiod: $timeperiod")

        return parseCategorySeconds(ri.androidQuery(timeperiod))
    }

    private fun parseCategorySeconds(jsonResult: String): Map<String?, Double> {
        val categories = mutableMapOf<String?, Double>()
        var totalDuration = 0.0

        try {
            val resultArray = JSONArray(jsonResult)
            if (resultArray.length() == 0) return emptyMap()

            val periodResult = resultArray.getJSONObject(0)
            val catEvents = periodResult.optJSONArray("cat_events") ?: return emptyMap()

            for (i in 0 until catEvents.length()) {
                val event = catEvents.getJSONObject(i)
                val duration = event.optDouble("duration", 0.0)
                val data = event.optJSONObject("data") ?: continue
                val categoryArray = data.optJSONArray("\$category")
                if (categoryArray == null || categoryArray.length() == 0) continue

                val topLevel = categoryArray.optString(0, "Uncategorized")
                categories[topLevel] = (categories[topLevel] ?: 0.0) + duration
                totalDuration += duration
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing category JSON", e)
        }

        categories[null] = totalDuration  // aggregate "All" key
        return categories
    }

    private fun checkAndNotify(
        categorySeconds: Map<String?, Double>,
        logicalDate: LocalDate,
        alerts: List<CategoryAlert> = DEFAULT_ALERTS,
    ) {
        ensureNotificationChannel()

        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dayKey = logicalDate.toString()

        for (alert in alerts) {
            val seconds = categorySeconds[alert.category] ?: 0.0
            val minutes = seconds / 60.0

            // Suppress re-firing thresholds already triggered this logical day.
            // Config hash ensures a new lower threshold isn't silently skipped when
            // the user reconfigures mid-day — the new hash produces a fresh key.
            val configHash = alertConfigHash(alert)
            val prefKey = "triggered_${alert.category}_${configHash}_$dayKey"
            val alreadyTriggeredMinutes = prefs.getInt(prefKey, 0)

            // Find the highest newly-crossed threshold
            val nextThreshold = alert.thresholdMinutes
                .filter { it > alreadyTriggeredMinutes && minutes >= it }
                .maxOrNull() ?: continue

            sendNotification(alert, nextThreshold, minutes)
            prefs.edit().putInt(prefKey, nextThreshold).apply()
            Log.i(TAG, "Fired alert: ${alert.label} at ${nextThreshold}min (actual: ${minutes.toInt()}min)")
        }
    }

    private fun sendNotification(alert: CategoryAlert, thresholdMinutes: Int, actualMinutes: Double) {
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val thresholdStr = formatDuration(thresholdMinutes)
        val actualStr = formatDuration(actualMinutes.toInt())
        val body = "${alert.label}: $thresholdStr" +
            if (thresholdStr != actualStr) "  ($actualStr)" else ""

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(if (alert.positive) "Goal reached!" else "Time spent")
            .setContentText(body)
            .setSmallIcon(R.mipmap.aw_launcher_round)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        // Stable ID per alert so notifications update in-place rather than stacking
        val notifId = 1000 + (alert.category?.hashCode() ?: 0).and(0xFFFF)
        nm.notify(notifId, notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Activity Time Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Alerts when you reach time thresholds for activities"
            }
            (applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun fetchStartOfDayHour(): Int {
        return try {
            val url = java.net.URL("http://127.0.0.1:5600/api/0/settings/startOfDay")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 1_000
            conn.readTimeout = 1_000
            if (conn.responseCode == 200) {
                parseStartOfDayHour(conn.inputStream.bufferedReader().readText())
            } else {
                DEFAULT_START_OF_DAY_HOUR
            }
        } catch (e: Exception) {
            DEFAULT_START_OF_DAY_HOUR
        }
    }

    private fun fetchAlerts(): List<CategoryAlert> {
        return try {
            val url = java.net.URL("http://127.0.0.1:5600/api/0/settings/aw-notify")
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 1_000
            conn.readTimeout = 1_000
            if (conn.responseCode == 200) {
                val text = conn.inputStream.bufferedReader().readText().trim()
                if (text == "null" || text.isBlank()) DEFAULT_ALERTS
                else parseAlerts(text).takeIf { it.isNotEmpty() } ?: DEFAULT_ALERTS
            } else {
                DEFAULT_ALERTS
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not fetch alert config from server, using defaults")
            DEFAULT_ALERTS
        }
    }

    private fun parseStartOfDayHour(response: String): Int {
        val v = response.trim()
        return when {
            v == "null" -> DEFAULT_START_OF_DAY_HOUR
            v.startsWith("\"") -> v.trim('"').split(":").firstOrNull()?.toIntOrNull()
                ?: DEFAULT_START_OF_DAY_HOUR
            else -> v.toIntOrNull() ?: DEFAULT_START_OF_DAY_HOUR
        }
    }

    private fun formatDuration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }
}
