package net.activitywatch.android

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.util.Log
import android.view.MenuItem
import android.widget.Button
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.appcompat.widget.SwitchCompat
import java.text.DateFormat
import java.util.Date

private const val TAG = "SyncSettingsActivity"

// While the screen is visible, "Next sync" is otherwise only refreshed by an explicit event
// (switch toggle, completed-sync broadcast). Without a periodic tick, a displayed deadline that
// passes while the user is looking at the screen stays stuck showing the old timestamp instead
// of flipping to "due now".
private const val NEXT_SYNC_REFRESH_INTERVAL_MS = 30 * 1000L

internal fun formatSyncStatus(status: SyncStatus?, dateFormat: DateFormat): String {
    if (status == null) return "Last sync: never"

    val whenText = dateFormat.format(Date(status.completedAt))
    val headline = if (status.success) {
        "Last sync succeeded at $whenText"
    } else {
        val error = SyncStatus.normalizeError(status.error)
        if (error != null) {
            "Last sync failed at $whenText: $error"
        } else {
            "Last sync failed at $whenText"
        }
    }
    // Runs recorded before the SyncReport crossed the JNI boundary carry no
    // counts; showing "pulled 0, pushed 0" for them would invent an answer.
    if (!status.hasReport) return headline
    return "$headline\n${formatSyncDetail(status)}"
}

/**
 * "When will it sync next?" — uses the scheduler's own recorded next-run time
 * (written by SyncScheduler.start() and after each completed sync) so the displayed
 * time matches what the scheduler actually has registered. Falls back to
 * lastCompletedAt + SYNC_INTERVAL_MS when no scheduler time is recorded (e.g. after
 * a fresh install before the first start() call).
 */
internal fun formatNextSyncStatus(
    enabled: Boolean,
    lastStatus: SyncStatus?,
    dateFormat: DateFormat,
    now: Long = System.currentTimeMillis(),
    schedulerNextRunAt: Long? = null,
): String {
    if (!enabled) return "Next sync: sync is disabled"
    if (lastStatus == null) {
        return "Next sync: shortly (first sync runs about a minute after ActivityWatch starts)"
    }
    val nextAt = schedulerNextRunAt?.takeIf { it > 0L }
        ?: (lastStatus.completedAt + SYNC_INTERVAL_MS)
    if (nextAt <= now) return "Next sync: due now"
    return "Next sync: ${dateFormat.format(Date(nextAt))}"
}

/**
 * The per-run facts line: what moved and which peers it came from. Without
 * this, a pass that transferred nothing is indistinguishable from one that
 * transferred everything — the failure mode of a boolean-only status.
 */
internal fun formatSyncDetail(status: SyncStatus): String {
    val peers = status.peersImported + status.peersSkipped + status.peersFailed
    val parts = mutableListOf("pulled ${status.eventsPulled}, pushed ${status.eventsPushed}")
    if (peers > 0) {
        var peerText = "peers ${status.peersImported}/$peers imported"
        if (status.peersSkipped > 0) peerText += ", ${status.peersSkipped} skipped"
        if (status.peersFailed > 0) peerText += ", ${status.peersFailed} failed"
        parts += peerText
    }
    val line = parts.joinToString(" · ")
    if (status.warnings.isEmpty()) return line
    return (listOf(line) + status.warnings).joinToString("\n")
}

class SyncSettingsActivity : AppCompatActivity() {

    private lateinit var prefs: AWPreferences

    private lateinit var switchSyncEnabled: SwitchCompat
    private lateinit var tvSyncDirStatus: TextView
    private lateinit var tvLastSyncStatus: TextView
    private lateinit var tvNextSyncStatus: TextView
    private lateinit var btnChooseDir: Button

    // Guards against the switch listener firing when we set isChecked programmatically
    private var isUpdatingSwitch = false

    private val syncStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AWPreferences.LAST_SYNC_STATUS_CHANGED_ACTION) {
                updateLastSyncStatus()
                updateNextSyncStatus()
            }
        }
    }

    private val nextSyncRefreshHandler = Handler(Looper.getMainLooper())
    private val nextSyncRefreshRunnable = object : Runnable {
        override fun run() {
            updateNextSyncStatus()
            nextSyncRefreshHandler.postDelayed(this, NEXT_SYNC_REFRESH_INTERVAL_MS)
        }
    }

    private val openDocumentTree =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val uri: Uri = result.data?.data ?: return@registerForActivityResult

                // Only persist the subset of flags the provider actually granted — passing
                // modes the provider didn't offer causes SecurityException.
                val grantedFlags = result.data?.flags ?: 0
                val persistableFlags = grantedFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

                // If no persistable flags were granted at all, the URI won't survive a reboot.
                // Abort so the previously-working directory config remains intact.
                if (persistableFlags == 0) {
                    Log.w(TAG, "Document provider granted no persistable flags — aborting directory update")
                    Toast.makeText(this, "Could not secure persistent access to selected directory", Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }

                // Take the NEW grant BEFORE releasing the old one. If takePersistableUriPermission
                // fails, we abort early so the previously-working grant remains intact.
                try {
                    contentResolver.takePersistableUriPermission(uri, persistableFlags)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Could not take persistable permission: ${e.message}")
                    Toast.makeText(this, "Could not secure persistent access to selected directory", Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }

                // New grant secured — now release a different old URI to stay within Android's
                // bounded persisted-grant allowance. Reselecting the current directory must not
                // release the grant we just took for that same URI.
                val oldUriStr = prefs.getSyncDirUri()
                if (oldUriStr != null) {
                    val oldUri = Uri.parse(oldUriStr)
                    if (oldUri != uri) {
                        try {
                            val releaseFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            contentResolver.releasePersistableUriPermission(oldUri, releaseFlags)
                        } catch (e: SecurityException) {
                            Log.w(TAG, "Could not release old URI grant: ${e.message}")
                        }
                    }
                }

                prefs.setSyncDirUri(uri.toString())
                updateSyncDirStatus()
                Toast.makeText(this, "Sync directory configured", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_settings)
        applySafeWindowInsets()

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = "Sync Settings"
        }

        prefs = AWPreferences(this)

        switchSyncEnabled = findViewById(R.id.switch_sync_enabled)
        tvSyncDirStatus = findViewById(R.id.tv_sync_dir_status)
        tvLastSyncStatus = findViewById(R.id.tv_last_sync_status)
        tvNextSyncStatus = findViewById(R.id.tv_next_sync_status)
        btnChooseDir = findViewById(R.id.btn_choose_sync_dir)

        refreshUI()

        switchSyncEnabled.setOnCheckedChangeListener { _: CompoundButton, isChecked: Boolean ->
            if (isUpdatingSwitch) return@setOnCheckedChangeListener
            prefs.setSyncEnabled(isChecked)
            // Notify the running BackgroundService so the scheduler starts/stops immediately
            // rather than waiting for the next service restart.
            startService(Intent(this, BackgroundService::class.java).apply {
                action = BackgroundService.ACTION_SYNC_ENABLED_CHANGED
                putExtra(BackgroundService.EXTRA_START_ORIGIN, BackgroundService.START_ORIGIN_SETTINGS)
            })
            updateNextSyncStatus()
        }

        btnChooseDir.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                // Suggest a sensible starting location (Downloads if available)
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownloads"))
            }
            openDocumentTree.launch(intent)
        }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this,
            syncStatusReceiver,
            IntentFilter(AWPreferences.LAST_SYNC_STATUS_CHANGED_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        nextSyncRefreshHandler.postDelayed(nextSyncRefreshRunnable, NEXT_SYNC_REFRESH_INTERVAL_MS)
    }

    override fun onResume() {
        super.onResume()
        if (::prefs.isInitialized) refreshUI()
    }

    override fun onStop() {
        unregisterReceiver(syncStatusReceiver)
        nextSyncRefreshHandler.removeCallbacks(nextSyncRefreshRunnable)
        super.onStop()
    }

    private fun refreshUI() {
        isUpdatingSwitch = true
        switchSyncEnabled.isChecked = prefs.isSyncEnabled()
        isUpdatingSwitch = false
        updateSyncDirStatus()
        updateLastSyncStatus()
        updateNextSyncStatus()
    }

    private fun updateLastSyncStatus() {
        tvLastSyncStatus.text = formatSyncStatus(
            prefs.getLastSyncStatus(),
            combinedDateTimeFormat(),
        )
    }

    private fun updateNextSyncStatus() {
        tvNextSyncStatus.text = formatNextSyncStatus(
            prefs.isSyncEnabled(),
            prefs.getLastSyncStatus(),
            combinedDateTimeFormat(),
            schedulerNextRunAt = prefs.getSchedulerNextRunAt().takeIf { it > 0L },
        )
    }

    private fun combinedDateTimeFormat(): DateFormat {
        val dateFormat = android.text.format.DateFormat.getMediumDateFormat(this)
        val timeFormat = android.text.format.DateFormat.getTimeFormat(this)
        return object : DateFormat() {
            override fun format(
                date: Date,
                toAppendTo: StringBuffer,
                fieldPosition: java.text.FieldPosition,
            ): StringBuffer = toAppendTo
                .append(dateFormat.format(date))
                .append(" ")
                .append(timeFormat.format(date))

            override fun parse(source: String, pos: java.text.ParsePosition): Date? = null
        }
    }

    private fun updateSyncDirStatus() {
        val uriStr = prefs.getSyncDirUri()
        tvSyncDirStatus.text = if (uriStr != null) {
            val displayName = resolveDisplayName(Uri.parse(uriStr)) ?: uriStr
            "Directory: $displayName"
        } else {
            "No sync directory configured. Tap \"Choose Directory\" to select one accessible to Syncthing or other sync tools."
        }
    }

    // Resolve a content:// tree URI to a human-readable path like "Downloads" or "Documents/aw-sync"
    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
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
