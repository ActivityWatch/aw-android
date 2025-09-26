package net.activitywatch.android.watcher.utils

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsCallback.NAVIGATION_FINISHED
import androidx.browser.customtabs.CustomTabsCallback.NAVIGATION_STARTED
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import org.awaitility.Awaitility.await
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.toJavaDuration

private const val FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK + Intent.FLAG_ACTIVITY_NO_HISTORY

fun createCustomTabsWrapper(browser: String, context: Context) : CustomTabsWrapper {
    val navigationEventsQueue = LinkedBlockingQueue<Int>()

    val customTabsCallback = object : CustomTabsCallback() {
        override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
            if (navigationEvent == NAVIGATION_STARTED || navigationEvent == NAVIGATION_FINISHED) {
                navigationEventsQueue.offer(navigationEvent)
            }
        }
    }

    val customTabsIntent =
        createCustomTabsIntentWithCallback(context, browser, customTabsCallback)
            ?: createFallbackCustomTabsIntent(browser)

    return CustomTabsWrapper(customTabsIntent, context, navigationEventsQueue)
}

private fun createCustomTabsIntentWithCallback(context: Context, browser: String, callback: CustomTabsCallback) : CustomTabsIntent? {
    val customTabsIntent: CompletableFuture<CustomTabsIntent> = CompletableFuture()

    return CustomTabsClient.bindCustomTabsService(context, browser, object : CustomTabsServiceConnection() {
        override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
            val session = client.newSession(callback)
            client.warmup(0)

            customTabsIntent.complete(
                CustomTabsIntent.Builder(session).build().also {
                    it.intent.setPackage(browser)
                    it.intent.addFlags(FLAGS)
                }
            )
        }

        override fun onServiceDisconnected(name: ComponentName) {}
    }).takeIf { it }?.run {
        customTabsIntent.get(CUSTOM_TABS_SERVICE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
    }
}

private fun createFallbackCustomTabsIntent(browser: String) = CustomTabsIntent.Builder().build()
    .also {
        it.intent.setPackage(browser)
        it.intent.addFlags(FLAGS)
    }

class CustomTabsWrapper(
    private val customTabsIntent: CustomTabsIntent,
    private val context: Context,
    navigationEventsQueue: LinkedBlockingQueue<Int>?
) {

    private val navigationCompletionAwaiter : NavigationCompletionAwaiter;

    init {
        val fallback = FallbackNavigationCompletionAwaiter()
        navigationCompletionAwaiter = navigationEventsQueue?.let {
            EventBasedNavigationCompletionAwaiter(it, fallback)
        } ?: fallback
    }

    fun openAndWait(uri: String, pageVisitTime: Duration, maxWaitTime: Duration) {
        customTabsIntent.launchUrl(context, Uri.parse(uri))
        navigationCompletionAwaiter.waitForNavigationCompleted(pageVisitTime, maxWaitTime)
    }
}

private interface NavigationCompletionAwaiter {
    fun waitForNavigationCompleted(pageVisitTime: Duration, maxWaitTime: Duration)
}

private class EventBasedNavigationCompletionAwaiter(
    private val navigationEventsQueue: LinkedBlockingQueue<Int>,
    private val fallback: NavigationCompletionAwaiter,
) : NavigationCompletionAwaiter {

    private var useFallback = false

    private fun waitForNavigationStarted() : Boolean {
        val event = navigationEventsQueue.poll(NAVIGATION_STARTED_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        return event == NAVIGATION_STARTED
    }

    override fun waitForNavigationCompleted(
        pageVisitTime: Duration,
        maxWaitTime: Duration
    ) {
        if (!useFallback && waitForNavigationStarted()) {
            await()
                .pollDelay(pageVisitTime.toJavaDuration())
                .atMost(maxWaitTime.toJavaDuration())
                .until { navigationEventsQueue.peek() == NAVIGATION_FINISHED }
            navigationEventsQueue.peek()
        } else {
            useFallback = true
            fallback.waitForNavigationCompleted(pageVisitTime, maxWaitTime)
        }
    }
}

private class FallbackNavigationCompletionAwaiter : NavigationCompletionAwaiter {
    override fun waitForNavigationCompleted(
        pageVisitTime: Duration,
        maxWaitTime: Duration
    ) {
        await()
            .pollDelay(pageVisitTime.toJavaDuration())
            .atMost(maxWaitTime.toJavaDuration())
            .until { true } // just wait page visit time as no callback is available
    }

}