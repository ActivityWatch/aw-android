package net.activitywatch.android.workers

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import net.activitywatch.android.AWPreferences
import net.activitywatch.android.BuildConfig
import net.activitywatch.android.RustInterface
import net.activitywatch.android.autoExportFilename
import net.activitywatch.android.copyExportCancellable
import net.activitywatch.android.ensureDashboardApiKey
import net.activitywatch.android.exportsToPrune
import net.activitywatch.android.recoverInterruptedExports
import net.activitywatch.android.tempExportsToClear
import net.activitywatch.android.stableExportKey
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "AutoExportWorker"

/** A non-transient failure (e.g. a 4xx from the export endpoint) that retrying cannot fix. */
private class PermanentExportException(message: String) : Exception(message)

/**
 * Scheduled export (aw-android#141): streams the same full export the webui
 * "Export all buckets" action uses (`GET /api/0/export`) straight into the
 * user's chosen SAF folder, then prunes older scheduled exports.
 */
class AutoExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = AWPreferences(applicationContext)
        val dirUri = prefs.getAutoExportDirUri()
        if (dirUri == null) {
            record(prefs, "no export folder chosen")
            return@withContext Result.failure()
        }
        try {
            val dir = DocumentFile.fromTreeUri(applicationContext, Uri.parse(dirUri))
            if (dir == null || !dir.canWrite()) {
                // A SAF tree can be transiently unavailable (SD card unmounted, provider
                // not yet mounted at boot, permission temporarily revoked). Result.failure()
                // is terminal for periodic work — it would silently disable automatic
                // export until the user re-enables it. Retry with backoff instead; the run
                // succeeds once the folder is reachable again.
                record(prefs, "export folder is no longer writable — choose it again")
                return@withContext Result.retry()
            }
            val host = stableExportKey(applicationContext)
            recoverInterruptedExports(dir.listFiles().mapNotNull { it.name }, host) { backupName, finalName ->
                val saved = dir.findFile(backupName) ?: error("missing $backupName")
                if (dir.findFile(finalName) != null) {
                    // Promotion is rename-only, so a final file beside a backup is a completed
                    // export, never a partial copy: the backup only survives here when a
                    // superseded copy could not be deleted (or a rollback could not remove it).
                    // Keep the visible export and drop the stale backup — never delete the
                    // final, which would let an older backup overwrite a newer export or leave
                    // the folder with no visible export at all.
                    try {
                        if (!saved.delete()) Log.w(TAG, "Could not remove stale backup ${saved.name}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not remove stale backup ${saved.name}", e)
                    }
                    true
                } else {
                    // Interrupted promotion: the completed export survives only as the backup.
                    saved.renameTo(finalName)
                }
            }
            val name = autoExportFilename(host, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))

            // Write to a temp file first so an interrupted download doesn't leave a partial
            // file with the final name (which would look like a complete backup).
            // The existing export is kept until its replacement is complete.
            // Clear any temp files left by previous runs on any date (process death across
            // days would otherwise leave stale partials outside the retention window).
            tempExportsToClear(dir.listFiles().mapNotNull { it.name }, host).forEach { stale ->
                dir.findFile(stale)?.delete()
            }
            val tempName = "$name.tmp"
            val tempFile = dir.createFile("application/json", tempName)
                ?: error("could not create $tempName")
            // Set aside today's earlier export (if any) instead of deleting it, so a failed
            // promotion never costs the last good backup.
            var backup: DocumentFile? = null
            try {
                download(tempFile)
                currentCoroutineContext().ensureActive()
                // Download succeeded — promote temp file to final name.
                val existing = dir.findFile(name)
                if (existing != null) {
                    val backupName = "$name.bak"
                    check(existing.renameTo(backupName)) { "could not preserve $name before replacement" }
                    backup = existing
                }
                val renamed = try {
                    DocumentsContract.renameDocument(
                        applicationContext.contentResolver, tempFile.uri, name)
                } catch (_: Exception) {
                    null // some SAF providers throw instead of returning null
                }
                // Never copy into the completed filename: process death could leave a partial
                // export there without a backup to recover. Providers must support rename.
                check(renamed != null) { "export folder does not support safe promotion by rename" }
                currentCoroutineContext().ensureActive()
            } catch (e: Exception) {
                tempFile.delete()
                // Promotion failed: drop any half-written final file and restore the previous export.
                if (backup != null) {
                    val partial = dir.findFile(name)
                    check(partial == null || partial.delete()) { "could not remove incomplete $name" }
                    check(backup.renameTo(name)) { "could not restore $name; complete export retained as ${backup.name}" }
                }
                throw e
            }
            // Promotion succeeded, so the previous export is superseded. Deleting it is
            // best-effort: a provider that refuses the delete must not cause us to discard a
            // successfully downloaded export (the old code rolled back on this failure, so
            // exports never advanced). A leftover .bak is harmless — it is outside the
            // retention namespace and the next run overwrites it.
            backup?.let { saved ->
                try {
                    if (!saved.delete()) {
                        Log.w(TAG, "Could not remove superseded export ${saved.name}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not remove superseded export ${saved.name}", e)
                }
            }
            exportsToPrune(dir.listFiles().mapNotNull { it.name }, host).forEach { oldName ->
                currentCoroutineContext().ensureActive()
                val old = dir.findFile(oldName)
                check(old == null || old.delete()) { "could not prune $oldName" }
            }
            currentCoroutineContext().ensureActive()
            record(prefs, "ok")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: PermanentExportException) {
            Log.e(TAG, "Scheduled export failed permanently", e)
            record(prefs, e.message ?: e.javaClass.simpleName)
            // Not transient (e.g. an expired API key): retrying forever would only drain the
            // battery. Fail the run and surface the error in the settings status line.
            Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Scheduled export failed", e)
            record(prefs, e.message ?: e.javaClass.simpleName)
            // Transient (server not up yet, network-less loopback hiccup): let WorkManager back off.
            Result.retry()
        }
    }

    private suspend fun download(target: DocumentFile) {
        // Create the API key before starting the server so the server loads it during
        // initialisation and the first export request carries a valid token.
        val token = ensureDashboardApiKey(applicationContext)
        RustInterface(applicationContext).startServerTask()
        val url = URL("http://127.0.0.1:${BuildConfig.SERVER_PORT}/api/0/export")
        var lastError: Exception? = null
        repeat(10) { attempt ->
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 120_000
                if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    // A 5xx while the Rust server is still booting is transient and worth
                    // retrying; a 4xx (e.g. 401) is not and should propagate immediately.
                    if (code in 500..599) throw java.io.IOException("export HTTP $code")
                    // A 4xx (e.g. 401 expired key) will not succeed on retry; surface it as
                    // permanent so the worker fails instead of retrying forever.
                    throw PermanentExportException("export HTTP $code")
                }
                val out = applicationContext.contentResolver.openOutputStream(target.uri, "wt")
                    ?: error("cannot open ${target.name}")
                out.use { o -> connection.inputStream.use { copyExportCancellable(it, o) } }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                // Covers ConnectException, SocketTimeoutException and 5xx: all can occur
                // while the server is still starting. Retrying only ConnectException left
                // the startup backoff ineffective for the other failure modes.
                lastError = e
                delay(1_000L * (attempt + 1)) // server still booting
            } finally {
                connection.disconnect()
            }
        }
        throw lastError ?: error("export server unreachable")
    }

    private fun record(prefs: AWPreferences, message: String) =
        prefs.setAutoExportLastResult(System.currentTimeMillis(), message)
}
