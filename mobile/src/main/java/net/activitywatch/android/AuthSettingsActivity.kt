package net.activitywatch.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AuthSettingsActivity : AppCompatActivity() {

    private lateinit var configManager: ConfigManager

    private lateinit var tvApiKey: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnCopy: Button
    private lateinit var btnRegenerate: Button
    private lateinit var switchAuthEnabled: Switch

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

        refreshUI()

        btnCopy.setOnClickListener {
            val key = configManager.readAuthConfig().apiKey ?: return@setOnClickListener
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("API key", key))
            Toast.makeText(this, "API key copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        btnRegenerate.setOnClickListener {
            val newKey = configManager.generateAndSetApiKey()
            tvApiKey.text = newKey
            switchAuthEnabled.isChecked = true
            tvStatus.text = "Authentication enabled (restart app to apply)"
            Toast.makeText(this, "New API key generated. Restart app to apply.", Toast.LENGTH_LONG).show()
        }

        switchAuthEnabled.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            if (isChecked) {
                // Enable auth — generate a key if none exists
                val current = configManager.readAuthConfig()
                if (!current.isEnabled) {
                    val newKey = configManager.generateAndSetApiKey()
                    tvApiKey.text = newKey
                }
                tvStatus.text = "Authentication enabled (restart app to apply)"
            } else {
                configManager.clearApiKey()
                tvApiKey.text = "(none)"
                tvStatus.text = "Authentication disabled (restart app to apply)"
            }
            Toast.makeText(this, "Setting saved. Restart app to apply.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshUI() {
        val auth = configManager.readAuthConfig()
        if (auth.isEnabled) {
            tvApiKey.text = auth.apiKey
            tvStatus.text = "Authentication is enabled"
            switchAuthEnabled.isChecked = true
            btnCopy.visibility = View.VISIBLE
        } else {
            tvApiKey.text = "(none — authentication disabled)"
            tvStatus.text = "Authentication is disabled"
            switchAuthEnabled.isChecked = false
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
