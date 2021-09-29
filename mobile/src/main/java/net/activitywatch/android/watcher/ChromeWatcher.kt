package net.activitywatch.android.watcher

import android.accessibilityservice.AccessibilityService
import android.annotation.TargetApi
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.*
import android.provider.Settings
import android.support.annotation.RequiresApi
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import net.activitywatch.android.R
import net.activitywatch.android.RustInterface
import net.activitywatch.android.models.Event
import org.json.JSONObject
import org.threeten.bp.DateTimeUtils
import org.threeten.bp.Duration
import org.threeten.bp.Instant
import java.net.URL
import java.text.ParseException
import java.text.SimpleDateFormat

class ChromeWatcher : AccessibilityService() {

    private val TAG = "ChromeWatcher"
    private val bucket_id = "aw-watcher-android-web-chrome"

    private var ri : RustInterface? = null

    var lastUrlTimestamp : Instant? = null
    var lastUrl : String? = null
    var lastTitle : String? = null

    override fun onCreate() {
        ri = RustInterface(applicationContext)
        ri?.createBucketHelper(bucket_id, "web.tab.current")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {

        // TODO: This method is called very often, which might affect performance. Future optimizations needed.

        // Only track Chrome and System events
        if (event.packageName != "com.android.chrome" && event.packageName != "com.android.systemui") {
            onUrl(null)
            return
        }

        try {
            if (event != null && event.source != null) {
                // Get URL
                val urlBars = event.source.findAccessibilityNodeInfosByViewId("com.android.chrome:id/url_bar")
                if (urlBars.any()) {
                    val newUrl = "http://" + urlBars[0].text.toString() // TODO: We can't access the URI scheme, so we assume HTTP.
                    onUrl(newUrl)
                }

                // Get title
                var webView = findWebView(event.source)
                if (webView != null) {
                    lastTitle = webView.text.toString()
                    Log.i(TAG, "Title: ${lastTitle}")
                }
            }
        }
        catch(ex : Exception) {
            Log.e(TAG, ex.message)
        }
    }

    fun findWebView(info : AccessibilityNodeInfo) : AccessibilityNodeInfo? {
        if(info == null)
            return null

        if(info.className == "android.webkit.WebView" && info.text != null)
            return info

        for (i in 0 until info.childCount) {
            val child = info.getChild(i)
            val webView = findWebView(child)
            if (webView != null) {
                return webView
            }
            if(child != null){
                child.recycle()
            }
        }

        return null
    }

    fun onUrl(newUrl : String?) {
        Log.i(TAG, "Url: ${newUrl}")
        if (newUrl != lastUrl) { // URL changed
            if (lastUrl != null) {
                // Log last URL and title as a completed browser event.
                // We wait for the event to complete (marked by a change in URL) to ensure that
                // we had a chance to receive the title of the page, which often only arrives after
                // the page loads completely and/or the user interacts with the page.
                logBrowserEvent(lastUrl!!, lastTitle ?: "", lastUrlTimestamp!!)
            }

            lastUrlTimestamp = Instant.ofEpochMilli(System.currentTimeMillis())
            lastUrl = newUrl
            lastTitle = null
        }
    }

    fun logBrowserEvent(url: String, title: String, lastUrlTimestamp : Instant) {
        val now = Instant.ofEpochMilli(System.currentTimeMillis())
        val start = lastUrlTimestamp
        val end = now
        val duration = Duration.between(start, end)

        val data = JSONObject()
        data.put("url", url)
        data.put("title", title)
        data.put("audible", false) // TODO
        data.put("incognito", false) // TODO

        ri?.heartbeatHelper(bucket_id, start, duration.seconds.toDouble(), data, 1.0)
    }

    override fun onInterrupt() {
        TODO("not implemented")
    }
}