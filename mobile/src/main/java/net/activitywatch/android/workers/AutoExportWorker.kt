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
import kotlinx.coroutines.withContext
import net.activitywatch.android.AWPreferences
import net.activitywatch.android.BuildConfig
import net.activitywatch.android.RustInterface
import net.activitywatch.android.autoExportFilename
import net.activitywatch.android.ensureDashboardApiKey
import net.activitywatch.android.exportsToPrune
import net.activitywatch.android.stableExportKey
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "AutoExportWorker"

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
                record(prefs, "export folder is no longer writable — choose it again")
                return@withContext Result.failure()
            }
            val host = stableExportKey(applicationContext)
            val name = autoExportFilename(host, SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))

            // Write to a temp file first so an interrupted download doesn't leave a partial
            // file with the final name (which would look like a complete backup).
            // The existing export is kept until its replacement is complete.
            val tempName = "$name.tmp"
            dir.findFile(tempName)?.delete()
            dir.findFile("$tempName.json")?.delete() // some SAF providers append .json to the MIME type
            val tempFile = dir.createFile("application/json", tempName)
                ?: error("could not create $tempName")
            try {
                download(tempFile)
                // Download succeeded — promote temp file to final name.
                dir.findFile(name)?.delete()
                val renamed = try {
                    DocumentsContract.renameDocument(
                        applicationContext.contentResolver, tempFile.uri, name)
                } catch (_: Exception) {
                    null // some SAF providers throw instead of returning null
                }
                if (renamed == null) {
                    // Provider doesn't support rename; fall back to copy + delete.
                    val finalFile = dir.createFile("application/json", name)
                        ?: error("could not create $name after rename failure")
                    val inStream = applicationContext.contentResolver.openInputStream(tempFile.uri)
                        ?: error("cannot open input stream for temp file")
                    inStream.use { ins ->
                        val outStream = applicationContext.contentResolver.openOutputStream(finalFile.uri, "wt")
                            ?: error("cannot open output stream for $name")
                        outStream.use { ins.copyTo(it) }
                    }
                    tempFile.delete()
                }
            } catch (e: Exception) {
                tempFile.delete()
                throw e
            }
            exportsToPrune(dir.listFiles().mapNotNull { it.name }, host).forEach { dir.findFile(it)?.delete() }
            record(prefs, "ok")
            Result.success()
        } catch (e: CancellationException) {
            throw e
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
                if (code !in 200..299) error("export HTTP $code")
                val out = applicationContext.contentResolver.openOutputStream(target.uri, "wt")
                    ?: error("cannot open ${target.name}")
                out.use { o -> connection.inputStream.use { it.copyTo(o) } }
                return
            } catch (e: java.net.ConnectException) {
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
