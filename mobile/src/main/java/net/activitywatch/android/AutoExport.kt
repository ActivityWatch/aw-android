package net.activitywatch.android

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
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
