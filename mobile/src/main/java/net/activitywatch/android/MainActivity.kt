package net.activitywatch.android

import android.app.usage.UsageStats
import android.net.Uri
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.design.widget.NavigationView
import android.support.v4.view.GravityCompat
import android.support.v7.app.ActionBarDrawerToggle
import android.support.v7.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import kotlinx.android.synthetic.main.activity_main.*
import kotlinx.android.synthetic.main.app_bar_main.*
import android.support.v4.app.Fragment
import android.util.Log
import net.activitywatch.android.fragments.Bucket
import net.activitywatch.android.fragments.BucketListFragment
import net.activitywatch.android.fragments.TestFragment
import net.activitywatch.android.fragments.WebUIFragment
import net.activitywatch.android.watcher.UsageStatsWatcher

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener,
    BucketListFragment.OnListFragmentInteractionListener, WebUIFragment.OnFragmentInteractionListener {

    private val TAG = "MainActivity"

    val version: String
        get() {
            return packageManager.getPackageInfo(packageName, 0).versionName
        }

    override fun onListFragmentInteraction(item: Bucket?) {
        Log.w(TAG, "Bucket onInteraction listener not implemented")
    }

    override fun onFragmentInteraction(item: Uri) {
        Log.w(TAG, "URI onInteraction listener not implemented")
    }

    override fun onAttachFragment(fragment: Fragment) {
        if (fragment is BucketListFragment) {
            fragment.onAttach(this)
        }
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

        val ri = RustInterface(this)
        ri.startServerTask(this)

        val usw = UsageStatsWatcher(this)
        usw.setupAlarm()

        val firstFragment = TestFragment()
        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, firstFragment).commit()
    }

    override fun onResume() {
        super.onResume()

        // Ensures data is always fresh when app is opened,
        // even if it was up to an hour since the last logging-alarm was triggered.
        val usw = UsageStatsWatcher(this)
        usw.sendHeartbeats()
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
        var fragmentClass: Class<out Fragment>? = null
        // Handle navigation view item clicks here.
        when (item.itemId) {
            R.id.nav_dashboard -> {
                fragmentClass = TestFragment::class.java
            }
            R.id.nav_webui -> {
                fragmentClass = WebUIFragment::class.java
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

        val fragment: Fragment? = try {
            fragmentClass?.newInstance()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }

        if(fragment != null) {
            // Insert the fragment by replacing any existing fragment
            val fragmentManager = supportFragmentManager
            fragmentManager.beginTransaction().replace(R.id.fragment_container, fragment).commit()
        }

        drawer_layout.closeDrawer(GravityCompat.START)
        return true
    }
}
