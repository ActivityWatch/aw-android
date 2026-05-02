package net.activitywatch.android.watcher

import android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ServiceTestRule
import net.activitywatch.android.RustInterface
import net.activitywatch.android.watcher.utils.MAX_CONDITION_WAIT_TIME_MILLIS
import net.activitywatch.android.watcher.utils.PAGE_MAX_WAIT_TIME_MILLIS
import net.activitywatch.android.watcher.utils.PAGE_VISIT_TIME_MILLIS
import net.activitywatch.android.watcher.utils.createCustomTabsWrapper
import org.awaitility.Awaitility.await
import org.hamcrest.CoreMatchers.not
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.TypeSafeMatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.time.Duration.Companion.milliseconds

private const val BUCKET_NAME = "aw-watcher-android-web"

@LargeTest
@RunWith(AndroidJUnit4::class)
class WebWatcherTest {

    @get:Rule
    val serviceTestRule: ServiceTestRule = ServiceTestRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val applicationContext = ApplicationProvider.getApplicationContext<Context>()

    private val testWebPages = listOf(
        WebPage("https://example.com", "Example Domain"),
        WebPage("https://example.org", "Example Domain"),
        WebPage("https://example.net", "Example Domain"),
        WebPage("https://w3.org", "W3C"),
    )

    @Test
    fun registerWebActivities() {
        val ri = RustInterface(context)

        Intent(applicationContext, WebWatcher::class.java)
            .also { serviceTestRule.bindService(it) }
            .also { enableAccessibilityService(serviceName = it.component!!.flattenToString()) }

        val browsers = getAvailableBrowsers()
            .also { assertThat(it, not(emptyList())) }

        browsers.forEach { browser ->
            openUris(uris = testWebPages.map { it.url }, browser = browser)
            openHome() // to commit last event

            val matchers = testWebPages.map { it.toMatcher(browser) }

            await("expected events for: $browser").atMost(MAX_CONDITION_WAIT_TIME_MILLIS, MILLISECONDS).until {
                val rawEvents = ri.getEvents(BUCKET_NAME, 100)
                val events = JSONArray(rawEvents).asListOfJsonObjects()
                    .filter { it.getJSONObject("data").getString("browser") == browser }

                matchers.all { matcher -> events.any { matcher.matches(it) } }
            }
        }
    }

    private fun enableAccessibilityService(serviceName: String) {
        executeShellCmd("settings put secure enabled_accessibility_services $serviceName")
        executeShellCmd("settings put secure accessibility_enabled 1")
    }

    private fun executeShellCmd(cmd: String) {
        InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand(cmd)
    }

    private fun getAvailableBrowsers() : List<String> {
        val activityIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://"))
        return context.packageManager
            .queryIntentActivities(activityIntent, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName.toString() }
    }

    private fun openUris(uris: List<String>, browser: String) {
        val customTabs = createCustomTabsWrapper(browser, context)
        uris.forEach { uri -> customTabs.openAndWait(
            uri,
            pageVisitTime = PAGE_VISIT_TIME_MILLIS.milliseconds,
            maxWaitTime = PAGE_MAX_WAIT_TIME_MILLIS.milliseconds
        )}
    }

    private fun openHome() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        context.startActivity(intent)
    }
}

private fun JSONArray.asListOfJsonObjects() = this.let {
    jsonArray -> (0 until jsonArray.length()).map { jsonArray.get(it) as JSONObject }
}

data class WebPage(val url: String, val title: String) {
    fun toMatcher(expectedBrowser: String): WebWatcherEventMatcher = WebWatcherEventMatcher(
        expectedUrl = url.removePrefix("https://"),
        expectedTitle = title.takeIf { shouldMatchTitle(expectedBrowser) },
        expectedBrowser = expectedBrowser,
    )

    // Samsung Internet does not match title at all as no android.webkit.WebView node is present
    private fun shouldMatchTitle(browser: String) = browser != "com.sec.android.app.sbrowser"
}

class WebWatcherEventMatcher(
    private val expectedUrl: String,
    private val expectedTitle: String?,
    private val expectedBrowser: String
) : TypeSafeMatcher<JSONObject>() {

    override fun describeTo(description: org.hamcrest.Description?) {
        description?.appendText("event with url=$expectedUrl registered by: $expectedBrowser")
    }

    override fun matchesSafely(obj: JSONObject): Boolean {
        val timestamp = obj.optString("timestamp", "")

        val duration = obj.optLong("duration", -1)
        val data = obj.optJSONObject("data")

        val url = data?.optString("url")
        val title = data?.optString("title")
        val browser = data?.optString("browser")

        return timestamp.isNotBlank()
                && duration >= 0
                && url?.startsWith(expectedUrl) ?: false
                && expectedTitle?.let { it == title } ?: true
                && browser == expectedBrowser
    }
}