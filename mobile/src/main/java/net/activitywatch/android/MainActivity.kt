package net.activitywatch.android

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.navigation.NavigationView
import androidx.core.view.GravityCompat
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import android.util.Log
import net.activitywatch.android.databinding.ActivityMainBinding
import net.activitywatch.android.fragments.TestFragment
import net.activitywatch.android.fragments.WebUIFragment
import net.activitywatch.android.watcher.UsageStatsWatcher

private const val TAG = "MainActivity"

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener, WebUIFragment.OnFragmentInteractionListener {

    private lateinit var binding: ActivityMainBinding

    private val baseURL: String
        get() {
            val remote = AWPreferences(this).getRemoteServerUrl()
            return if (remote.isNotBlank()) remote else "http://127.0.0.1:5600"
        }

    val version: String
        get() {
            return packageManager.getPackageInfo(packageName, 0).versionName
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
        }

        // Set up UI
        binding = ActivityMainBinding.inflate(layoutInflater)
        val view = binding.root
        setContentView(view)

        // Set up alarm to send heartbeats
        val usw = UsageStatsWatcher(this)
        usw.setupAlarm()

        binding.navView.setNavigationItemSelectedListener(this)

        setSupportActionBar(binding.appBar.toolbar)
        val toggle = ActionBarDrawerToggle(
            this, binding.drawerLayout, binding.appBar.toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        binding.drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        if (savedInstanceState != null) {
            return
        }
        val firstFragment = WebUIFragment.newInstance(baseURL)
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

    private fun showRemoteServerDialog() {
        val prefs = AWPreferences(this)
        val currentUrl = prefs.getRemoteServerUrl()
        val currentUser = prefs.getRemoteServerUsername()
        val currentPass = prefs.getRemoteServerPassword()

        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        val padding = (16 * resources.displayMetrics.density).toInt()
        layout.setPadding(padding, padding, padding, 0)

        val inputUrl = EditText(this)
        inputUrl.hint = "http://your-server-ip:5600"
        inputUrl.setText(currentUrl)
        layout.addView(inputUrl)

        val inputUser = EditText(this)
        inputUser.hint = "Username (optional)"
        inputUser.setText(currentUser)
        layout.addView(inputUser)

        val inputPass = EditText(this)
        inputPass.hint = "Password (optional)"
        inputPass.setText(currentPass)
        inputPass.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        layout.addView(inputPass)

        AlertDialog.Builder(this)
            .setTitle("Remote ActivityWatch Server")
            .setMessage("Leave empty to use local server only (127.0.0.1:5600).")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val url = inputUrl.text.toString().trim()
                val user = inputUser.text.toString().trim()
                val pass = inputPass.text.toString().trim()
                prefs.setRemoteServerUrl(url)
                prefs.setRemoteServerUsername(user)
                prefs.setRemoteServerPassword(pass)
                val msg = if (url.isBlank()) "Remote server set to: (local)" else "Remote server set to: $url"
                Snackbar.make(binding.coordinatorLayout, msg, Snackbar.LENGTH_LONG).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
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
        return when (item.itemId) {
            R.id.action_settings -> {
                Snackbar.make(binding.coordinatorLayout, "The settings button was clicked, but it's not yet implemented!", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
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
                url = "$baseURL/#/activity/unknown/"
            }
            R.id.nav_buckets -> {
                fragmentClass = WebUIFragment::class.java
                url = "$baseURL/#/buckets/"
            }
            R.id.nav_settings -> {
                fragmentClass = WebUIFragment::class.java
                url = "$baseURL/#/settings/"
            }
            R.id.nav_remote_server -> {
                showRemoteServerDialog()
            }
            R.id.nav_share -> {
                Snackbar.make(binding.coordinatorLayout, "The share button was clicked, but it's not yet implemented!", Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show()
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
}
