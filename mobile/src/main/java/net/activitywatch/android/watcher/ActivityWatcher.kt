package net.activitywatch.android.watcher

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import net.activitywatch.android.RustInterface
import org.json.JSONObject
import org.threeten.bp.Instant

class ActivityWatcher : AccessibilityService() {

    private val TAG = "ActivityWatcher"
    private val bucket_id = "aw-watcher-android-realtime"

    private var ri: RustInterface? = null
    private var lastApp: String? = null
    private var lastAppTimestamp: Instant? = null

    override fun onCreate() {
        super.onCreate()
        ri = RustInterface(applicationContext)
        ri?.createBucketHelper(bucket_id, "currentwindow")
        Log.i(TAG, "ActivityWatcher created")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // Skip own app to avoid infinite loop
        if (packageName.startsWith("net.activitywatch.android")) return

        // Skip system UI
        if (packageName == "com.android.systemui") return

        if (packageName != lastApp) {
            val now = Instant.now()

            // Log the previous app's duration
            if (lastApp != null && lastAppTimestamp != null) {
                val duration = org.threeten.bp.Duration.between(lastAppTimestamp, now)
                if (duration.seconds > 0) {
                    logAppUsage(lastApp!!, lastAppTimestamp!!, duration.seconds.toDouble())
                }
            }

            // Update current app
            lastApp = packageName
            lastAppTimestamp = now

            Log.d(TAG, "Switched to: $packageName / $className")
        }
    }

    private fun logAppUsage(appPackage: String, start: Instant, duration: Double) {
        try {
            val data = JSONObject()
            data.put("app", getAppName(appPackage))
            data.put("package", appPackage)

            ri?.heartbeatHelper(bucket_id, start, duration, data, 1.0)
            Log.d(TAG, "Logged: ${getAppName(appPackage)} for ${duration}s")
        } catch (e: Exception) {
            Log.e(TAG, "logAppUsage error: ${e.message}")
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val pm = applicationContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "ActivityWatcher interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Flush the last app's duration
        if (lastApp != null && lastAppTimestamp != null) {
            val duration = org.threeten.bp.Duration.between(lastAppTimestamp, Instant.now())
            if (duration.seconds > 0) {
                logAppUsage(lastApp!!, lastAppTimestamp!!, duration.seconds.toDouble())
            }
        }
        Log.i(TAG, "ActivityWatcher destroyed")
    }
}
