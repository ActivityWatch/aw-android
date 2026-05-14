package net.activitywatch.android.watcher

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import net.activitywatch.android.RustInterface
import org.json.JSONObject
import org.threeten.bp.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class ActivityWatcher : AccessibilityService() {

    private val TAG = "ActivityWatcher"
    private val bucket_id = "aw-watcher-android-realtime"
    private val executor = Executors.newSingleThreadExecutor()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    private var ri: RustInterface? = null
    private var lastApp: String? = null
    private var lastAppTimestamp: Instant? = null
    private var bucketCreated = false
    private var refreshTask: ScheduledFuture<*>? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            ri = RustInterface(applicationContext)
            startPeriodicRefresh()
            Log.i(TAG, "ActivityWatcher service connected")
        } catch (e: Exception) {
            Log.e(TAG, "ActivityWatcher init error: ${e.message}")
        }
    }

    private fun startPeriodicRefresh() {
        refreshTask?.cancel(false)
        refreshTask = scheduler.scheduleAtFixedRate({
            try {
                if (lastApp != null && lastAppTimestamp != null) {
                    val now = Instant.now()
                    val duration = org.threeten.bp.Duration.between(lastAppTimestamp, now)
                    if (duration.seconds >= 60) {
                        val appPackage = lastApp!!
                        val start = lastAppTimestamp!!
                        val dur = duration.seconds.toDouble()
                        executor.execute {
                            logAppUsage(appPackage, start, dur)
                        }
                        // Reset timestamp to now so next refresh accumulates from here
                        lastAppTimestamp = now
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Periodic refresh error: ${e.message}")
            }
        }, 60, 60, TimeUnit.SECONDS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        if (packageName.startsWith("net.activitywatch.android")) return
        if (packageName == "com.android.systemui") return

        // Skip MIUI system overlays and input methods that trigger false events
        val skipPackages = setOf(
            "com.miui.contentextension",   // 传送门
            "com.miui.personalassistant",  // 个人助理（负一屏）
            "com.miui.home",               // 桌面（只在切换时短暂出现）
            "com.tencent.wetype"           // 微信输入法
        )
        if (packageName in skipPackages) return

        if (packageName != lastApp) {
            val now = Instant.now()

            if (lastApp != null && lastAppTimestamp != null) {
                val duration = org.threeten.bp.Duration.between(lastAppTimestamp, now)
                if (duration.seconds > 0) {
                    val appPackage = lastApp!!
                    val start = lastAppTimestamp!!
                    val dur = duration.seconds.toDouble()
                    executor.execute {
                        logAppUsage(appPackage, start, dur)
                    }
                }
            }

            lastApp = packageName
            lastAppTimestamp = now

            Log.d(TAG, "Switched to: $packageName / $className")
        }
    }

    private fun logAppUsage(appPackage: String, start: Instant, duration: Double) {
        try {
            if (!bucketCreated && ri != null) {
                ri?.createBucketHelper(bucket_id, "currentwindow")
                bucketCreated = true
                Log.i(TAG, "Bucket created: $bucket_id")
            }

            val data = JSONObject()
            data.put("app", getAppName(appPackage))
            data.put("package", appPackage)

            ri?.heartbeatHelper(bucket_id, start, duration, data, 60.0)
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
        refreshTask?.cancel(false)
        if (lastApp != null && lastAppTimestamp != null) {
            val duration = org.threeten.bp.Duration.between(lastAppTimestamp, Instant.now())
            if (duration.seconds > 0) {
                val appPackage = lastApp!!
                val start = lastAppTimestamp!!
                val dur = duration.seconds.toDouble()
                executor.execute {
                    logAppUsage(appPackage, start, dur)
                }
            }
        }
        executor.shutdown()
        scheduler.shutdown()
        Log.i(TAG, "ActivityWatcher destroyed")
    }
}
