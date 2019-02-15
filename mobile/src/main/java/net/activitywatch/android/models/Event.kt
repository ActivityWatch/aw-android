package net.activitywatch.android.models

import android.app.usage.UsageEvents
import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONObject
import org.threeten.bp.DateTimeUtils
import org.threeten.bp.Instant

data class Event(val timestamp: Instant, val duration: Double = 0.0, val data: JSONObject) {
    companion object {
        fun fromUsageEvent(usageEvent: UsageEvents.Event, context: Context): Event {
            val timestamp = DateTimeUtils.toInstant(java.util.Date(usageEvent.timeStamp))
            val pm = context.packageManager
            val appName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(usageEvent.packageName, PackageManager.GET_META_DATA))
            } catch(e: PackageManager.NameNotFoundException) {
                "Unknown"
            }
            return Event(
                timestamp = timestamp,
                duration = 0.0,
                data = JSONObject("""{"app": "$appName", "package": "${usageEvent.packageName}", "classname": "${usageEvent.className}"}""")
            )
        }
    }

    override fun toString(): String {
        return """{"timestamp": "$timestamp", "duration": $duration, "data": $data}"""
    }
}