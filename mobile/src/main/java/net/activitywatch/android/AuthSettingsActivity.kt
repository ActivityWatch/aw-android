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
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AuthSettingsActivity : AppCompatActivity() {

    private lateinit var configManager: ConfigManager

    private lateinit var tvApiKey: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvHelp: TextView
    private lateinit var btnCopy: Button
    private lateinit var btnRegenerate: Button
    private lateinit var switchAuthEnabled: SwitchCompat

    // Guards against the switch listener firing when we set isChecked programmatically
    private var isUpdatingSwitch = false

    // Serialize complete actions (IO and UI) in click order. Cancelling a coroutine
    // does not stop a synchronous config write already running on Dispatchers.IO.
    private val authActionMutex = AUTH_ACTION_MUTEX

    // A settings write is what the user asked for, so it must finish even when the
    // screen that started it goes away: on lifecycleScope a toggle still queued behind
    // a regenerate was cancelled by Back while the regenerate itself completed, which
    // left auth on after restart. Process-lifetime, not activity-lifetime.
    private val authScope = AUTH_SCOPE

    // Bumped on every user toggle. An action that had to wait for the mutex must not
    // write the switch back if a newer toggle already expressed a different intent.
    private var toggleGeneration = 0

    private val isScreenGone: Boolean
        get() = isFinishing || isDestroyed

    override fun onCreate(savedInstanceState: Bundle?) {
        applyPersistedWebUiNightMode()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auth_settings)
        applySafeWindowInsets(followNightMode = true)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "API Authentication"
        }

        configManager = ConfigManager(this)

        tvApiKey = findViewById(R.id.tv_api_key)
        tvStatus = findViewById(R.id.tv_status)
        tvHelp = findViewById(R.id.tv_help)
        tvHelp.text = (
            "When enabled, ActivityWatch clients (browser, desktop app) must include " +
            "this key to access data. Use the key in the URL:\n\n" +
            "http://localhost:${ServerPort.value}/?token=YOUR_KEY\n\n" +
            "Changes take effect after restarting the app."
        )
        btnCopy = findViewById(R.id.btn_copy_key)
        btnRegenerate = findViewById(R.id.btn_regenerate_key)
        switchAuthEnabled = findViewById(R.id.switch_auth_enabled)
        // The saved state is not on screen yet: a tap now would be overwritten by the
        // queued refresh, which reads the pre-tap state. Enable it once that lands.
        switchAuthEnabled.isEnabled = false

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
            launchAuthAction {
                // A new key also re-enables auth: the server enforces whatever key is in
                // config.toml, so the preference must agree or the dashboard loads
                // without a token after restart.
                val newKey = withContext(Dispatchers.IO) {
                    val key = configManager.generateAndSetApiKey()
                    if (key != null) AWPreferences(this@AuthSettingsActivity).setDashboardAuthEnabled(true)
                    key
                }
                if (newKey == null) {
                    if (!isScreenGone) {
                        scheduleRefreshUI()
                        Toast.makeText(this@AuthSettingsActivity, "Failed to save API key", Toast.LENGTH_LONG).show()
                    }
                    return@launchAuthAction
                }
                if (isScreenGone) return@launchAuthAction
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
            val generation = ++toggleGeneration
            launchAuthAction {
                if (isChecked) {
                    val current = withContext(Dispatchers.IO) { configManager.readAuthConfig() }
                    if (!current.isEnabled) {
                        // Key generation and preference write are in one IO block so
                        // a lifecycle cancel between them cannot leave the preference
                        // at true with no key in config.toml.
                        val newKey = withContext(Dispatchers.IO) {
                            val key = configManager.generateAndSetApiKey()
                            if (key != null) AWPreferences(this@AuthSettingsActivity).setDashboardAuthEnabled(true)
                            key
                        }
                        if (newKey == null) {
                            if (!isScreenGone) {
                                scheduleRefreshUI()
                                Toast.makeText(this@AuthSettingsActivity, "Failed to save API key", Toast.LENGTH_LONG).show()
                            }
                            return@launchAuthAction
                        }
                        if (isScreenGone) return@launchAuthAction
                        tvApiKey.text = newKey
                        // Only show the toast when a write actually happened
                        Toast.makeText(this@AuthSettingsActivity, "Setting saved. Restart app to apply.", Toast.LENGTH_SHORT).show()
                    } else {
                        withContext(Dispatchers.IO) { AWPreferences(this@AuthSettingsActivity).setDashboardAuthEnabled(true) }
                    }
                    if (isScreenGone) return@launchAuthAction
                    btnCopy.visibility = View.VISIBLE
                    tvStatus.text = "Authentication enabled (restart app to apply)"
                } else {
                    // Key clear and preference write are in one IO block so a lifecycle
                    // cancel between clearApiKey() and the preference write cannot leave
                    // config.toml empty while the preference still reads true.
                    val success = withContext(Dispatchers.IO) {
                        val cleared = configManager.clearApiKey()
                        if (cleared) AWPreferences(this@AuthSettingsActivity).setDashboardAuthEnabled(false)
                        cleared
                    }
                    if (!success) {
                        if (!isScreenGone) {
                            scheduleRefreshUI()
                            Toast.makeText(this@AuthSettingsActivity, "Failed to save API key setting", Toast.LENGTH_LONG).show()
                        }
                        return@launchAuthAction
                    }
                    if (isScreenGone) return@launchAuthAction
                    tvApiKey.text = "(none)"
                    btnCopy.visibility = View.GONE
                    tvStatus.text = "Authentication disabled (restart app to apply)"
                    Toast.makeText(this@AuthSettingsActivity, "Setting saved. Restart app to apply.", Toast.LENGTH_SHORT).show()
                }
                // Only the newest toggle owns the switch: an older save finishing late
                // must not flip it back to an intent the user has already replaced.
                if (generation == toggleGeneration && !isScreenGone) {
                    isUpdatingSwitch = true
                    switchAuthEnabled.isChecked = isChecked
                    isUpdatingSwitch = false
                }
            }
        }
    }

    private fun launchAuthAction(action: suspend () -> Unit) {
        authScope.launch {
            authActionMutex.withLock { action() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::configManager.isInitialized) {
            scheduleRefreshUI()
        }
    }

    // Queue refreshes with writes so a stale read cannot overwrite a newer action.
    private fun scheduleRefreshUI() {
        if (isScreenGone) return
        launchAuthAction {
            val auth = withContext(Dispatchers.IO) { configManager.readAuthConfig() }
            applyAuthToUI(auth)
        }
    }

    private fun applyAuthToUI(auth: ConfigManager.AuthConfig) {
        // The first applied state is the saved one; from here taps are meaningful.
        switchAuthEnabled.isEnabled = true
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

    private companion object {
        // One queue and one scope for the whole process: the state they protect
        // (config.toml plus the dashboard preference) is shared, and a save started by
        // a closed screen must not interleave with a save from a reopened one. Two
        // per-activity locks let a pending disable from the old screen land after the
        // new screen turned auth back on, which leaves the server running without a key.
        val AUTH_ACTION_MUTEX = Mutex()
        val AUTH_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
