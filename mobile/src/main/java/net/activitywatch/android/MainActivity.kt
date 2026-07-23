package net.activitywatch.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar
import net.activitywatch.android.databinding.ActivityMainBinding
import net.activitywatch.android.fragments.TestFragment
import net.activitywatch.android.fragments.WebUIFragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.activitywatch.android.watcher.UsageStatsWatcher

private const val TAG = "MainActivity"

const val baseURL = "http://127.0.0.1:5600"


class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener, WebUIFragment.OnFragmentInteractionListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var dashboardApiKey: String

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

        // Set up alarm to send heartbeats
        val usw = UsageStatsWatcher(this)
        usw.setupAlarm()

        binding.navView.setNavigationItemSelectedListener(this)

        // Ensure API key exists in config before the server starts so it picks it up at init.
        dashboardApiKey = ensureDashboardApiKey(this)
        // Start background service to keep server and sync running
        val serviceIntent = Intent(this, BackgroundService::class.java)
        startForegroundService(serviceIntent)

        if (savedInstanceState != null) {
            return
        }
        val firstFragment = WebUIFragment.newInstance(authenticatedUrl())
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, firstFragment).commit()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    finish()
                }
            }
        })

    }

    override fun onResume() {
        super.onResume()

        // Ensures data is always fresh when app is opened,
        // even if it was up to an hour since the last logging-alarm was triggered.
        val usw = UsageStatsWatcher(this)
        val mode = if (usw.isUsingDiscreteEvents()) "discrete event insertion" else "heartbeat merging"
        Log.i("MainActivity", "Using $mode mode for event tracking")
        lifecycleScope.launch { usw.sendHeartbeatsSuspend() }
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
                url = authenticatedUrl("$baseURL/#/activity/unknown/")
            }
            R.id.nav_buckets -> {
                fragmentClass = WebUIFragment::class.java
                url = authenticatedUrl("$baseURL/#/buckets/")
            }
            R.id.nav_settings -> {
                fragmentClass = WebUIFragment::class.java
                url = authenticatedUrl("$baseURL/#/settings/")
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
