package net.activitywatch.android

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.design.widget.NavigationView
import android.support.v4.view.GravityCompat
import android.support.v7.app.ActionBarDrawerToggle
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.app_bar_main.*
import android.content.pm.PackageManager
import android.app.AppOpsManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.provider.Settings
import kotlinx.android.synthetic.main.content_main.*
import android.widget.TextView



class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener {
    private val TAG = "MainActivity"

    init {
        System.loadLibrary("aw_server");
    }

    fun getVersion(): String {
        return packageManager.getPackageInfo(packageName, 0).versionName;
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(toolbar)

        val toggle = ActionBarDrawerToggle(
            this, drawer_layout, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawer_layout.addDrawerListener(toggle)
        toggle.syncState()

        nav_view.setNavigationItemSelectedListener(this)

        button.setOnClickListener {
            queryUsage()
            testRust()
        }
    }

    private fun testRust() {
        RustGreetings(applicationContext).test()
    }

    private fun queryUsage() {
        val usageIsAllowed = isUsageAllowed()

        if (usageIsAllowed) {
            // Get UsageStatsManager stuff
            val usm: UsageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

            // Print per application
            val usageStats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, 0, Long.MAX_VALUE)
            Log.i(TAG, "usageStats.size=${usageStats.size}")
            for(e in usageStats) {
                Log.i(TAG, "${e.packageName}: ${e.totalTimeInForeground/1000}")
            }

            // Print each event
            val usageEvents = usm.queryEvents(0, Long.MAX_VALUE)
            val eventOut = UsageEvents.Event()
            while(usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(eventOut)
                Log.i(TAG, "timestamp=${eventOut.timeStamp}, ${eventOut.eventType}, ${eventOut.className}")
            }
        } else {
            Log.w(TAG, "Was not allowed access to UsageStats, enable in settings.")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    private fun isUsageAllowed(): Boolean {
        // https://stackoverflow.com/questions/27215013/check-if-my-application-has-usage-access-enabled
        val applicationInfo: ApplicationInfo = try {
            packageManager.getApplicationInfo(packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, e.toString())
            return false
        }

        val appOpsManager = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOpsManager.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            applicationInfo.uid,
            applicationInfo.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun onBackPressed() {
        if (drawer_layout.isDrawerOpen(GravityCompat.START)) {
            drawer_layout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
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
        when (item.itemId) {
            R.id.action_settings -> {
                Snackbar.make(coordinator_layout, "The settings button was clicked, but it's not yet implemented!", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.nav_dashboard -> {
                Snackbar.make(coordinator_layout, "The dashboard button was clicked, but it's not yet implemented!", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
            }
            R.id.nav_settings -> {
                Snackbar.make(coordinator_layout, "The settings button was clicked, but it's not yet implemented!", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
            }
            R.id.nav_share -> {
                Snackbar.make(coordinator_layout, "The share button was clicked, but it's not yet implemented!", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
            }
            R.id.nav_send -> {
                Snackbar.make(coordinator_layout, "The send button was clicked, but it's not yet implemented!", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
            }
        }

        drawer_layout.closeDrawer(GravityCompat.START)
        return true
    }
}
