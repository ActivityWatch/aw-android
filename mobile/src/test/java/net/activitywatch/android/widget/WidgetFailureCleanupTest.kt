package net.activitywatch.android.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import com.jakewharton.threetenabp.AndroidThreeTen
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.runBlocking
import net.activitywatch.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, shadows = [WidgetFailureCleanupTest.WidgetManager::class])
class WidgetFailureCleanupTest {
    /** Model a binder write that can stall before the host receives its RemoteViews. */
    @Implements(AppWidgetManager::class)
    class WidgetManager {
        data class Write(val id: Int, val views: RemoteViews, val partial: Boolean)
        val writes = CopyOnWriteArrayList<Write>()
        @Volatile var beforeWrite: () -> Unit = {}

        @Implementation
        fun getAppWidgetIds(component: ComponentName): IntArray = intArrayOf(1, 2)

        @Implementation
        fun updateAppWidget(id: Int, views: RemoteViews) {
            beforeWrite()
            writes.add(Write(id, views, false))
        }

        @Implementation
        fun partiallyUpdateAppWidget(id: Int, views: RemoteViews) {
            beforeWrite()
            writes.add(Write(id, views, true))
        }
    }

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val manager: AppWidgetManager get() = AppWidgetManager.getInstance(context)
    private val shadow: WidgetManager get() = Shadow.extract(manager)

    private fun renderFreshData() {
        AndroidThreeTen.init(context)
        val buildViews = CategoryTimeWidgetUpdater::class.java.getDeclaredMethod(
            "buildViews", Context::class.java, CategoryTimeWidgetUpdater.WidgetData::class.java
        ).apply { isAccessible = true }
        val views = (buildViews.invoke(
            CategoryTimeWidgetUpdater, context,
            CategoryTimeWidgetUpdater.WidgetData(listOf("Work" to 7 * 60 * 60 * 1000L), emptyMap())
        ) as RemoteViews).apply {
            setTextViewText(R.id.widget_timestamp, "fresh totals")
        }
        for (id in intArrayOf(1, 2)) manager.updateAppWidget(id, views)
    }

    private fun assertFreshDataRemains() {
        for (id in intArrayOf(1, 2)) {
            var host: View? = null
            for (write in shadow.writes.filter { it.id == id }) {
                if (write.partial) {
                    host?.let { write.views.reapply(context, it) }
                } else {
                    host = write.views.apply(context, FrameLayout(context))
                }
            }
            assertEquals("7", host!!.findViewById<TextView>(R.id.widget_hours).text.toString())
            assertEquals("fresh totals", host.findViewById<TextView>(R.id.widget_timestamp).text.toString())
            assertEquals(View.GONE, host.findViewById<View>(R.id.widget_loading_indicator).visibility)
        }
    }

    @Test
    fun failureAfterFreshRender_preservesTotalsForEveryWidget() {
        renderFreshData()
        CategoryTimeWidgetUpdater.onUpdateFailed(context, IllegalStateException("datastore unavailable"))
        assertFreshDataRemains()
    }

    @Test
    fun failureAlreadyInsideWrite_cannotOverwriteLaterRender() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        shadow.beforeWrite = {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val cleanup = AsyncBroadcast.detached {
            CategoryTimeWidgetUpdater.onUpdateFailed(context, IllegalStateException("datastore unavailable"))
        }
        try {
            assertTrue("cleanup did not enter binder write", started.await(5, TimeUnit.SECONDS))
            shadow.beforeWrite = {}
            renderFreshData()
        } finally {
            release.countDown()
            cleanup.await()
        }
        assertEquals(listOf(false, true), shadow.writes.filter { it.id == 1 }.map { it.partial })
        assertFreshDataRemains()
    }

    @Test
    fun failedManualRefresh_rebuiltWidgetKeepsTotalsAndTapBindings() {
        renderFreshData()
        val companion = CategoryTimeWidgetProvider.Companion
        val showLoading = companion.javaClass.getDeclaredMethod(
            "showLoadingState", Context::class.java, AppWidgetManager::class.java, Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        for (id in intArrayOf(1, 2)) showLoading.invoke(companion, context, manager, id)
        CategoryTimeWidgetUpdater.onUpdateFailed(context, IllegalStateException("refresh failed"))

        // Rebuild from the saved full update plus subsequent partial updates, not a
        // still-live host: a spinner-only full update discards totals and tap bindings.
        assertFreshDataRemains()
        for (id in intArrayOf(1, 2)) {
            val writes = shadow.writes.filter { it.id == id }
            val lastFull = writes.indexOfLast { !it.partial }
            val cached = writes[lastFull].views.clone()
            val merge = RemoteViews::class.java.getDeclaredMethod("mergeRemoteViews", RemoteViews::class.java)
            for (write in writes.drop(lastFull + 1)) merge.invoke(cached, write.views)
            val rebuilt = cached.apply(context, FrameLayout(context))
            assertEquals("7", rebuilt.findViewById<TextView>(R.id.widget_hours).text.toString())
            assertEquals("fresh totals", rebuilt.findViewById<TextView>(R.id.widget_timestamp).text.toString())
            assertEquals(View.GONE, rebuilt.findViewById<View>(R.id.widget_loading_indicator).visibility)
            assertTrue("refresh binding missing", rebuilt.findViewById<View>(R.id.widget_root).hasOnClickListeners())
            assertTrue("open-app binding missing", rebuilt.findViewById<View>(R.id.widget_open_button).hasOnClickListeners())
        }
    }

    @Test
    fun failureWithoutNewerSuccess_stillClearsSpinner() {
        renderFreshData()
        val loading = RemoteViews(context.packageName, R.layout.widget_category_time).apply {
            setViewVisibility(R.id.widget_loading_indicator, View.VISIBLE)
        }
        for (id in intArrayOf(1, 2)) manager.partiallyUpdateAppWidget(id, loading)
        CategoryTimeWidgetUpdater.onUpdateFailed(context, IllegalStateException("refresh failed"))
        assertFreshDataRemains()
    }

    @Test
    fun timeoutCleanup_alsoPreservesTotals() {
        renderFreshData()
        CategoryTimeWidgetUpdater.onUpdateFailed(context, TimeoutException("still running"))
        assertFreshDataRemains()
    }
}
