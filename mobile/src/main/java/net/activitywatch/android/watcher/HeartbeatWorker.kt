package net.activitywatch.android.watcher

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import net.activitywatch.android.RustInterface
import net.activitywatch.android.models.Event
import org.json.JSONObject
import org.threeten.bp.DateTimeUtils
import org.threeten.bp.Duration
import org.threeten.bp.Instant
import java.text.ParseException
import java.text.SimpleDateFormat

class HeartbeatWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        const val TAG = "HeartbeatWorker"
        const val WORK_NAME = "heartbeat_work"
    }

    private val ri = RustInterface(applicationContext)
    private val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

    override fun doWork(): Result {
        Log.i(TAG, "HeartbeatWorker started")

        if (!UsageStatsWatcher.isUsageAllowed(applicationContext)) {
            Log.w(TAG, "Usage stats not allowed, skipping")
            return Result.success()
        }

        return try {
            ri.createBucketHelper(bucket_id, "currentwindow")
            ri.createBucketHelper(unlock_bucket_id, "os.lockscreen.unlocks")

            var lastUpdated = getLastEventTime()
            Log.w(TAG, "lastUpdated from server: ${lastUpdated?.toString() ?: "never"}")

            val now = Instant.now()
            if (lastUpdated != null && Duration.between(lastUpdated, now).toDays() > 7) {
                Log.w(TAG, "Server data is too old (>7 days), using now-1hour instead")
                lastUpdated = now.minus(Duration.ofHours(1))
            }

            val usm = applicationContext.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            if (usm == null) {
                Log.e(TAG, "UsageStatsManager not available")
                return Result.failure()
            }

            var heartbeatsSent = 0
            val usageEvents = usm.queryEvents(lastUpdated?.toEpochMilli() ?: 0L, Long.MAX_VALUE)

            while (usageEvents.hasNextEvent()) {
                val event = UsageEvents.Event()
                usageEvents.getNextEvent(event)

                if (event.eventType !in arrayListOf(
                        UsageEvents.Event.ACTIVITY_RESUMED,
                        UsageEvents.Event.ACTIVITY_PAUSED
                    )
                ) {
                    if (event.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) {
                        val timestamp = DateTimeUtils.toInstant(java.util.Date(event.timeStamp))
                        ri.heartbeatHelper(unlock_bucket_id, timestamp, 0.0, JSONObject(), 0.0)
                    }
                    continue
                }

                val awEvent = Event.fromUsageEvent(event, applicationContext, includeClassname = true)
                val pulsetime = when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> 1.0
                    UsageEvents.Event.ACTIVITY_PAUSED -> 24 * 60 * 60.0
                    else -> continue
                }

                ri.heartbeatHelper(bucket_id, awEvent.timestamp, awEvent.duration, awEvent.data, pulsetime)
                heartbeatsSent++
            }

            Log.w(TAG, "HeartbeatWorker finished, sent $heartbeatsSent events")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "HeartbeatWorker error: ${e.message}", e)
            Result.retry()
        }
    }

    private fun getLastEvent(): JSONObject? {
        val events = ri.getEventsJSON(bucket_id, limit = 1)
        return if (events.length() == 1) {
            events[0] as JSONObject
        } else {
            Log.w(TAG, "Unexpected event count when getting last event: ${events.length()}")
            null
        }
    }

    private fun getLastEventTime(): Instant? {
        val lastEvent = getLastEvent()
        return if (lastEvent != null) {
            val timestampString = lastEvent.getString("timestamp")
            try {
                val timeCreatedDate = isoFormatter.parse(timestampString)
                DateTimeUtils.toInstant(timeCreatedDate)
            } catch (e: ParseException) {
                Log.e(TAG, "Unable to parse timestamp: $timestampString")
                null
            }
        } else {
            null
        }
    }
}
