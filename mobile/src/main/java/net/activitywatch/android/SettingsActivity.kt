package net.activitywatch.android

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.switchmaterial.SwitchMaterial

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        val prefs = AWPreferences(this)
        val switchNetworkAccess = findViewById<SwitchMaterial>(R.id.switchNetworkAccess)
        switchNetworkAccess.isChecked = prefs.isNetworkAccessEnabled()

        switchNetworkAccess.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Show security warning before enabling
                AlertDialog.Builder(this)
                    .setTitle(R.string.network_access_warning_title)
                    .setMessage(R.string.network_access_warning_message)
                    .setPositiveButton(R.string.network_access_warning_enable) { _, _ ->
                        prefs.setNetworkAccessEnabled(true)
                        showRestartNotice()
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        switchNetworkAccess.isChecked = false
                    }
                    .setOnCancelListener {
                        switchNetworkAccess.isChecked = false
                    }
                    .show()
            } else {
                prefs.setNetworkAccessEnabled(false)
                showRestartNotice()
            }
        }
    }

    private fun showRestartNotice() {
        Toast.makeText(this, R.string.network_access_restart_notice, Toast.LENGTH_LONG).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
