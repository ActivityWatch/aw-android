package net.activitywatch.android

import android.content.Context
import android.provider.Settings
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream
import net.activitywatch.android.workers.AutoExportWorker
import java.util.concurrent.TimeUnit

/** How often the scheduled export (aw-android#141) runs. OFF is the default. */
enum class AutoExportInterval(val days: Long) {
    OFF(0),
    DAILY(1),
    WEEKLY(7);

    companion object {
        fun fromName(name: String?): AutoExportInterval =
            values().firstOrNull { it.name == name } ?: OFF
    }
}

/** Number of scheduled export files kept in the folder; older ones are deleted after a new export. */
internal const val AUTO_EXPORT_KEEP = 7

internal const val AUTO_EXPORT_WORK_NAME = "aw-auto-export"

private val EXPORT_DATE = Regex("""\d{4}-\d{2}-\d{2}""")

internal fun autoExportFilePrefix(hostname: String): String = "aw-export-$hostname-"

internal fun autoExportFilename(hostname: String, date: String): String =
    "${autoExportFilePrefix(hostname)}$date.json"

/**
 * Stable per-device key for the export file namespace.
 * Appends the first 6 chars of ANDROID_ID to the hostname so two devices with
 * the same user-visible name (same model, same configured device name) don't
 * write into each other's export files or retention window.
 */
internal fun stableExportKey(context: Context): String {
    val hostname = deviceHostname(context)
    val androidId = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ANDROID_ID)
        ?.takeIf { it.isNotEmpty() }
        ?.take(6) ?: return hostname
    return "$hostname-$androidId"
}

/**
 * Names (from [existing]) that should be deleted so only the newest [keep]
 * exports of this host remain. Only files that exactly match our naming scheme
 * are considered, so unrelated files in the user's folder are never touched.
 * ISO dates sort lexicographically, so name order is chronological order.
 */
internal fun exportsToPrune(existing: List<String>, hostname: String, keep: Int = AUTO_EXPORT_KEEP): List<String> {
    val prefix = autoExportFilePrefix(hostname)
    val ours = existing.filter {
        it.startsWith(prefix) && it.endsWith(".json") &&
            EXPORT_DATE.matches(it.removePrefix(prefix).removeSuffix(".json"))
    }.sorted()
    return ours.dropLast(keep.coerceAtLeast(1))
}

/** Restore this device's saved exports before any new download or retention pass. */
internal fun recoverInterruptedExports(
    existing: List<String>,
    hostname: String,
    restore: (backup: String, final: String) -> Boolean,
) {
    val prefix = autoExportFilePrefix(hostname)
    existing.forEach { backup ->
        val final = when {
            backup.endsWith(".json.bak.json") -> backup.removeSuffix(".bak.json")
            backup.endsWith(".json.bak") -> backup.removeSuffix(".bak")
            else -> return@forEach
        }
        if (final.startsWith(prefix) &&
            EXPORT_DATE.matches(final.removePrefix(prefix).removeSuffix(".json"))) {
            check(restore(backup, final)) { "could not restore $backup" }
        }
    }
}

/** Blocking reads remain timeout-bounded; cancellation prevents the next write. */
internal suspend fun copyExportCancellable(input: InputStream, output: OutputStream) {
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        currentCoroutineContext().ensureActive()
        if (count < 0) return
        output.write(buffer, 0, count)
    }
}

object AutoExportScheduler {
    /** (Re)apply [interval]: enqueue periodic work, or cancel it for OFF. */
    fun apply(context: Context, interval: AutoExportInterval) {
        val wm = WorkManager.getInstance(context)
        if (interval == AutoExportInterval.OFF) {
            wm.cancelUniqueWork(AUTO_EXPORT_WORK_NAME)
            return
        }
        val request = PeriodicWorkRequestBuilder<AutoExportWorker>(interval.days, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .setRequiresStorageNotLow(true)
                    .build()
            )
            .build()
        // UPDATE so switching daily <-> weekly takes effect without a duplicate chain.
        wm.enqueueUniquePeriodicWork(AUTO_EXPORT_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}
