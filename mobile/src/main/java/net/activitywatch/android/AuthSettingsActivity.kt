package net.activitywatch.android

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthSettingsActivity : AppCompatActivity() {

    private lateinit var configManager: ConfigManager

    private lateinit var tvApiKey: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnCopy: Button
    private lateinit var btnRegenerate: Button
    private lateinit var switchAuthEnabled: SwitchMaterial

    // Guards against the switch listener firing when we set isChecked programmatically
    private var isUpdatingSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth_settings)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "API Authentication"
        }

        configManager = ConfigManager(this)

        tvApiKey = findViewById(R.id.tv_api_key)
        tvStatus = findViewById(R.id.tv_status)
        btnCopy = findViewById(R.id.btn_copy_key)
        btnRegenerate = findViewById(R.id.btn_regenerate_key)
        switchAuthEnabled = findViewById(R.id.switch_auth_enabled)

        scheduleRefreshUI()

        btnCopy.setOnClickListener {
            lifecycleScope.launch {
                val key = withContext(Dispatchers.IO) { configManager.readAuthConfig().apiKey }
                    ?: return@launch
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("API key", key)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    clip.description.extras = PersistableBundle().also {
                        it.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                    }
                }
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@AuthSettingsActivity, "API key copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }

        btnRegenerate.setOnClickListener {
            lifecycleScope.launch {
                val newKey = withContext(Dispatchers.IO) { configManager.generateAndSetApiKey() }
                if (newKey == null) {
                    scheduleRefreshUI()
                    Toast.makeText(this@AuthSettingsActivity, "Failed to save API key", Toast.LENGTH_LONG).show()
                    return@launch
                }
                tvApiKey.text = newKey
                btnCopy.visibility = View.VISIBLE
                isUpdatingSwitch = true
                switchAuthEnabled.isChecked = true
                isUpdatingSwitch = false
                tvStatus.text = "Authentication enabled (restart app to apply)"
                Toast.makeText(this@AuthSettingsActivity, "New API key generated. Restart app to apply.", Toast.LENGTH_LONG).show()
            }
        }

        switchAuthEnabled.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener
            lifecycleScope.launch {
                if (isChecked) {
                    val current = withContext(Dispatchers.IO) { configManager.readAuthConfig() }
                    if (!current.isEnabled) {
                        val newKey = withContext(Dispatchers.IO) { configManager.generateAndSetApiKey() }
                        if (newKey == null) {
                            scheduleRefreshUI()
                            Toast.makeText(this@AuthSettingsActivity, "Failed to save API key", Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        tvApiKey.text = newKey
                        // Only show the toast when a write actually happened
                        Toast.makeText(this@AuthSettingsActivity, "Setting saved. Restart app to apply.", Toast.LENGTH_SHORT).show()
                    }
                    AWPreferences(this@AuthSettingsActivity).setDashboardAuthEnabled(true)
                    btnCopy.visibility = View.VISIBLE
                    tvStatus.text = "Authentication enabled (restart app to apply)"
                } else {
                    val success = withContext(Dispatchers.IO) { configManager.clearApiKey() }
                    if (!success) {
                        scheduleRefreshUI()
                        Toast.makeText(this@AuthSettingsActivity, "Failed to save API key setting", Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    AWPreferences(this@AuthSettingsActivity).setDashboardAuthEnabled(false)
                    tvApiKey.text = "(none)"
                    btnCopy.visibility = View.GONE
                    tvStatus.text = "Authentication disabled (restart app to apply)"
                    Toast.makeText(this@AuthSettingsActivity, "Setting saved. Restart app to apply.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::configManager.isInitialized) {
            scheduleRefreshUI()
        }
    }

    // Reads config on IO thread, then updates UI on main thread.
    private fun scheduleRefreshUI() {
        lifecycleScope.launch {
            val auth = withContext(Dispatchers.IO) { configManager.readAuthConfig() }
            applyAuthToUI(auth)
        }
    }

    private fun applyAuthToUI(auth: ConfigManager.AuthConfig) {
        if (auth.isEnabled) {
            tvApiKey.text = auth.apiKey
            tvStatus.text = "Authentication is enabled"
            isUpdatingSwitch = true
            switchAuthEnabled.isChecked = true
            isUpdatingSwitch = false
            btnCopy.visibility = View.VISIBLE
        } else {
            tvApiKey.text = "(none — authentication disabled)"
            tvStatus.text = "Authentication is disabled"
            isUpdatingSwitch = true
            switchAuthEnabled.isChecked = false
            isUpdatingSwitch = false
            btnCopy.visibility = View.GONE
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
