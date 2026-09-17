package net.activitywatch.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import net.activitywatch.android.databinding.ActivityMainBinding
import net.activitywatch.android.fragments.TestFragment
import net.activitywatch.android.fragments.WebUIFragment
import net.activitywatch.android.watcher.UsageStatsWatcher

private const val TAG = "MainActivity"

val baseURL = "http://127.0.0.1:${BuildConfig.SERVER_PORT}"

// Same destination as the drawer "Activity" item. Notification taps set
// EXTRA_OPEN_ACTIVITY_VIEW so we land here instead of dashboard home.
// Hostname must match the sanitized device name used for Android buckets —
// `/#/activity/unknown/` is a sentinel, not a host (see DeviceHostname.kt).
const val EXTRA_OPEN_ACTIVITY_VIEW = "net.activitywatch.android.extra.OPEN_ACTIVITY_VIEW"

internal fun activityViewUrl(hostname: String, baseUrl: String = baseURL): String =
    "$baseUrl/#/activity/${sanitizeDeviceHostname(hostname)}/"

internal fun initialWebUiUrl(
    openActivityView: Boolean,
    hostname: String,
    baseUrl: String = baseURL,
): String = if (openActivityView) activityViewUrl(hostname, baseUrl) else baseUrl

internal fun shouldOpenActivityViewImmediately(openActivityView: Boolean, isResumed: Boolean): Boolean =
    openActivityView && isResumed

/** Native Home lives in MainActivity, so it inherits the last WebView chrome unless reset. */
internal fun shouldResetChromeForNativeDestination(isWebUiDestination: Boolean): Boolean =
    !isWebUiDestination

/**
 * Chrome to apply on configuration change, or null to keep the last page-reported
 * scheme. Native surfaces stay light; WebUI follows system night only until the
 * page reports.
 */
internal fun chromeOnConfigurationChange(
    showingWebUi: Boolean,
    webUiSchemeFromPage: Boolean,
    systemNight: Boolean,
): Boolean? = when {
    showingWebUi && webUiSchemeFromPage -> null
    showingWebUi -> systemNight
    else -> false
}


class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener, WebUIFragment.OnFragmentInteractionListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dashboardApiKey: String
    private var webUiSchemeFromPage = false
    private var showingWebUi = true

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) {
                Log.i(TAG, "POST_NOTIFICATIONS denied; foreground service notification will be suppressed on Android 13+")
            }
        }

    val version: String
        get() {
            return packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        }

    private fun authenticatedUrl(url: String = baseURL): String {
        return buildDashboardUrl(url, dashboardApiKey)
    }

    private fun openDashboardInBrowser(url: String = baseURL) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authenticatedUrl(url))))
        } catch (e: ActivityNotFoundException) {
            Snackbar.make(binding.root, R.string.no_browser_found, Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onFragmentInteraction(item: Uri) {
        Log.w(TAG, "URI onInteraction listener not implemented")
    }

    override fun onWebUiColorSchemeChanged(dark: Boolean) {
        if (!showingWebUi) return
        webUiSchemeFromPage = true
        applyWebUiChrome(dark)
    }

    private fun applyWebUiChrome(dark: Boolean) {
        applySystemBarAppearance(dark)
        val bg = ContextCompat.getColor(this, if (dark) R.color.chrome_dark else R.color.chrome_light)
        val fg = ContextCompat.getColor(this, if (dark) R.color.chrome_on_dark else R.color.chrome_on_light)
        val accent = ContextCompat.getColor(this, R.color.colorAccent)
        binding.root.setBackgroundColor(bg)
        val nav = binding.navView
        nav.setBackgroundColor(bg)
        val itemColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(accent, fg),
        )
        nav.itemTextColor = itemColors
        nav.itemIconTintList = itemColors
        nav.getHeaderView(0)?.let { header ->
            header.setBackgroundColor(bg)
            tintTextTree(header, fg)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If first time, or usage not allowed, show onboarding activity
        val prefs = AWPreferences(this)
        if (prefs.isFirstTime() || !UsageStatsWatcher.isUsageAllowed(this)) {
            Log.i(TAG, "First time or usage not allowed, starting onboarding activity")
            val intent = Intent(this, OnboardingActivity::class.java)
            startActivity(intent)
            finish()
            return
        }

        // Request POST_NOTIFICATIONS once on Android 13+ so the foreground service
        // notification is visible without nagging users who decline the prompt.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED &&
            !prefs.hasRequestedNotificationPermission()
        ) {
            prefs.setNotificationPermissionRequested()
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Set up UI
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)
        applySafeWindowInsets()
        // Best-effort match for webui theme=auto until the page reports the stored setting.
        applyWebUiChrome(isSystemNightMode())

        // Set up alarm to send heartbeats
        val usw = UsageStatsWatcher(this)
        usw.setupAlarm()

        binding.navView.setNavigationItemSelectedListener(this)

        // Ensure API key exists in config before the server starts so it picks it up at init.
        dashboardApiKey = ensureDashboardApiKey(this)
        // Start background service to keep server and sync running
        val serviceIntent = Intent(this, BackgroundService::class.java).apply {
            putExtra(BackgroundService.EXTRA_START_ORIGIN, BackgroundService.START_ORIGIN_ACTIVITY)
        }
        startForegroundService(serviceIntent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    finish()
                }
            }
        })

        if (savedInstanceState != null) {
            showingWebUi = supportFragmentManager.findFragmentById(R.id.fragment_container) is WebUIFragment
            if (shouldResetChromeForNativeDestination(showingWebUi)) {
                webUiSchemeFromPage = false
                applyWebUiChrome(false)
            }
            return
        }
        // Cold start: pick the right first fragment so we don't flash dashboard
        // home before onResume. Consume the extra here; onResume is the path
        // for reused instances (onNewIntent) and process-death restore.
        val openActivityView = intent.getBooleanExtra(EXTRA_OPEN_ACTIVITY_VIEW, false)
        showWebUi(initialWebUiUrl(openActivityView, deviceHostname(this)), replace = false)
        if (openActivityView) {
            intent.removeExtra(EXTRA_OPEN_ACTIVITY_VIEW)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val isResumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (shouldOpenActivityViewImmediately(
                intent.getBooleanExtra(EXTRA_OPEN_ACTIVITY_VIEW, false),
                isResumed,
            )
        ) {
            openPendingActivityView()
        }
        // A stopped activity cannot safely commit here because its fragment
        // state may already be saved. onResume consumes the intent instead.
    }

    private fun takeOpenActivityView(intent: Intent): Boolean {
        val open = intent.getBooleanExtra(EXTRA_OPEN_ACTIVITY_VIEW, false)
        if (open) {
            intent.removeExtra(EXTRA_OPEN_ACTIVITY_VIEW)
        }
        return open
    }

    private fun showWebUi(url: String, replace: Boolean) {
        showingWebUi = true
        val fragment = WebUIFragment.newInstance(authenticatedUrl(url))
        val transaction = supportFragmentManager.beginTransaction()
        if (replace) {
            transaction.replace(R.id.fragment_container, fragment)
        } else {
            transaction.add(R.id.fragment_container, fragment)
        }
        transaction.commit()
    }

    private fun activityViewUrlForDevice(): String = activityViewUrl(deviceHostname(this))

    private fun openPendingActivityView() {
        if (takeOpenActivityView(intent)) {
            showWebUi(activityViewUrlForDevice(), replace = true)
        }
    }

    override fun onResume() {
        super.onResume()

        // Notification tap on a stopped/restored instance: replace after the
        // FragmentManager is ready. Cold start and resumed delivery already
        // consumed the extra, so this is a no-op in those cases.
        openPendingActivityView()

        // Ensures data is always fresh when app is opened,
        // even if it was up to an hour since the last logging-alarm was triggered.
        val usw = UsageStatsWatcher(this)
        val mode = if (usw.isUsingDiscreteEvents()) "discrete event insertion" else "heartbeat merging"
        Log.i("MainActivity", "Using $mode mode for event tracking")
        lifecycleScope.launch { usw.sendHeartbeatsSuspend() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // configChanges keeps this activity (and the WebView) alive across rotation.
        when (newConfig.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> Log.i(TAG, "Screen orientation changed to landscape")
            Configuration.ORIENTATION_PORTRAIT -> Log.i(TAG, "Screen orientation changed to portrait")
        }
        chromeOnConfigurationChange(
            showingWebUi,
            webUiSchemeFromPage,
            isSystemNightMode(newConfig),
        )?.let { applyWebUiChrome(it) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, AuthSettingsActivity::class.java))
                true
            }
            R.id.action_sync_settings -> {
                startActivity(Intent(this, SyncSettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        var fragmentClass: Class<out Fragment>? = null
        var url: String? = null

        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.nav_dashboard -> {
                fragmentClass = TestFragment::class.java
            }
            R.id.nav_activity -> {
                fragmentClass = WebUIFragment::class.java
                url = authenticatedUrl(activityViewUrlForDevice())
            }
            R.id.nav_buckets -> {
                fragmentClass = WebUIFragment::class.java
                url = authenticatedUrl("$baseURL/#/buckets/")
            }
            R.id.nav_settings -> {
                fragmentClass = WebUIFragment::class.java
                url = authenticatedUrl("$baseURL/#/settings/")
            }
            R.id.nav_auth_settings -> {
                startActivity(Intent(this, AuthSettingsActivity::class.java))
            }
            R.id.nav_sync_settings -> {
                startActivity(Intent(this, SyncSettingsActivity::class.java))
            }
            R.id.nav_share -> {
                openDashboardInBrowser()
            }
            R.id.nav_send -> {
                Snackbar.make(binding.coordinatorLayout, "The send button was clicked, but it's not yet implemented!", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
            }
        }

        val fragment: Fragment? = try {
            if (fragmentClass === WebUIFragment::class.java && url != null) {
                WebUIFragment.newInstance(url)
            } else {
                fragmentClass?.newInstance()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        if(fragment != null) {
            val isWebUi = fragment is WebUIFragment
            showingWebUi = isWebUi
            if (shouldResetChromeForNativeDestination(isWebUi)) {
                webUiSchemeFromPage = false
                applyWebUiChrome(false)
            }
            // Insert the fragment by replacing any existing fragment
            val fragmentManager = supportFragmentManager
            fragmentManager.beginTransaction().replace(R.id.fragment_container, fragment).commit()
        }

        binding.drawerLayout.closeDrawer(GravityCompat.START)
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

private fun tintTextTree(view: View, color: Int) {
    if (view is TextView) {
        view.setTextColor(color)
    }
    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            tintTextTree(view.getChildAt(i), color)
        }
    }
}
