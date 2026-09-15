package net.activitywatch.android

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import java.io.File

/**
 * One-shot repair for ActivityWatch/aw-android#272: sanitizing the device hostname
 * (PR #183) forked already-syncing devices onto a new folder name without moving
 * the old folder or rewriting bucket `hostname` rows.
 *
 * The sanitization itself stays. This migrates leftover identity so peers see one
 * device, not `POCO F8 Ultra/` plus `poco_f8_ultra/`.
 */
object SanitizedHostnameMigration {
    private const val TAG = "SanitizedHostnameMigration"

    sealed class FolderAction {
        data class RenameHostnameDir(val from: String, val to: String) : FolderAction()

        data class MoveDeviceDir(
            val fromHostname: String,
            val toHostname: String,
            val deviceId: String,
        ) : FolderAction()

        data class DeleteStaleDeviceDir(val hostname: String, val deviceId: String) : FolderAction()

        data class DeleteHostnameDir(val hostname: String) : FolderAction()
    }

    fun isSafeDirName(name: String): Boolean {
        if (name.isEmpty() || name == "." || name == "..") return false
        if (name.contains('\u0000')) return false
        return !name.contains('/') && !name.contains('\\')
    }

    fun legacyHostnames(current: String, deviceName: String?, model: String?): List<String> {
        val candidates =
            listOfNotNull(
                deviceName?.trim()?.takeIf { it.isNotEmpty() },
                model?.trim()?.takeIf { it.isNotEmpty() },
                "Unknown",
                "unknown",
            )
        return candidates.distinct().filter { it != current && isSafeDirName(it) }
    }

    fun hostnamesToRewrite(
        existingHostnames: Iterable<String>,
        current: String,
        legacy: Collection<String>,
    ): List<String> {
        val wanted = legacy.toSet()
        return existingHostnames.distinct().filter { it != current && it in wanted }
    }

    fun planFolderMigration(
        existingHostnameDirs: Set<String>,
        deviceIdsByHostname: Map<String, Set<String>>,
        currentHostname: String,
        legacyHostnames: Collection<String>,
        localDeviceId: String?,
    ): List<FolderAction> {
        if (!isSafeDirName(currentHostname)) return emptyList()
        val actions = mutableListOf<FolderAction>()
        val newExists = currentHostname in existingHostnameDirs
        val scopedId = localDeviceId?.takeIf { isSafeDirName(it) }

        for (legacy in legacyHostnames.distinct()) {
            if (legacy == currentHostname || !isSafeDirName(legacy)) continue
            if (legacy !in existingHostnameDirs) continue
            val legacyIds = deviceIdsByHostname[legacy].orEmpty()
            val newIds = deviceIdsByHostname[currentHostname].orEmpty()

            if (scopedId != null) {
                if (scopedId !in legacyIds) {
                    if (legacyIds.isEmpty()) {
                        actions.add(FolderAction.DeleteHostnameDir(legacy))
                    }
                    continue
                }
                when {
                    scopedId !in newIds -> {
                        if (legacyIds == setOf(scopedId) && !newExists) {
                            actions.add(FolderAction.RenameHostnameDir(legacy, currentHostname))
                        } else {
                            actions.add(
                                FolderAction.MoveDeviceDir(
                                    fromHostname = legacy,
                                    toHostname = currentHostname,
                                    deviceId = scopedId,
                                )
                            )
                            if (legacyIds == setOf(scopedId)) {
                                actions.add(FolderAction.DeleteHostnameDir(legacy))
                            }
                        }
                    }
                    else -> {
                        actions.add(FolderAction.DeleteStaleDeviceDir(legacy, scopedId))
                        if (legacyIds == setOf(scopedId)) {
                            actions.add(FolderAction.DeleteHostnameDir(legacy))
                        }
                    }
                }
            } else if (!newExists) {
                actions.add(FolderAction.RenameHostnameDir(legacy, currentHostname))
            } else {
                actions.add(FolderAction.DeleteHostnameDir(legacy))
            }
        }
        return actions
    }

    fun applyFolderMigration(syncDir: File, actions: List<FolderAction>): Int {
        var applied = 0
        for (action in actions) {
            val ok =
                when (action) {
                    is FolderAction.RenameHostnameDir ->
                        renameDir(File(syncDir, action.from), File(syncDir, action.to))
                    is FolderAction.MoveDeviceDir -> {
                        val destHost = File(syncDir, action.toHostname)
                        if (!destHost.exists() && !destHost.mkdirs()) {
                            warn("Could not create ${destHost.path}")
                            false
                        } else {
                            renameDir(
                                File(File(syncDir, action.fromHostname), action.deviceId),
                                File(destHost, action.deviceId),
                            )
                        }
                    }
                    is FolderAction.DeleteStaleDeviceDir ->
                        deleteRecursively(File(File(syncDir, action.hostname), action.deviceId))
                    is FolderAction.DeleteHostnameDir -> {
                        val dir = File(syncDir, action.hostname)
                        val leftover = dir.listFiles()?.isNotEmpty() == true
                        if (leftover) {
                            info("Leaving ${dir.path}; it still has other entries")
                            false
                        } else {
                            deleteRecursively(dir)
                        }
                    }
                }
            if (ok) applied++
        }
        return applied
    }

    fun migrateSyncFolders(
        syncDir: File,
        currentHostname: String,
        legacyHostnames: Collection<String>,
        localDeviceId: String?,
    ): Int {
        if (!syncDir.isDirectory) return 0
        val deviceIdsByHostname = linkedMapOf<String, Set<String>>()
        val hostnameDirs = mutableSetOf<String>()
        val children = syncDir.listFiles() ?: return 0
        for (child in children) {
            if (!child.isDirectory || !isSafeDirName(child.name)) continue
            hostnameDirs.add(child.name)
            val ids =
                child.listFiles()
                    ?.filter { it.isDirectory && isSafeDirName(it.name) }
                    ?.map { it.name }
                    ?.toSet()
                    .orEmpty()
            deviceIdsByHostname[child.name] = ids
        }
        val actions =
            planFolderMigration(
                existingHostnameDirs = hostnameDirs,
                deviceIdsByHostname = deviceIdsByHostname,
                currentHostname = currentHostname,
                legacyHostnames = legacyHostnames,
                localDeviceId = localDeviceId,
            )
        if (actions.isEmpty()) return 0
        info("Applying ${actions.size} sync-folder migration action(s) under ${syncDir.path}")
        return applyFolderMigration(syncDir, actions)
    }

    fun rewriteBucketHostnamesInDatabase(
        dbFile: File,
        currentHostname: String,
        fromHostnames: Collection<String>,
    ): Int {
        if (!dbFile.isFile || currentHostname.isEmpty()) return 0
        val from = fromHostnames.filter { it.isNotEmpty() && it != currentHostname }.distinct()
        if (from.isEmpty()) return 0
        val db =
            try {
                SQLiteDatabase.openDatabase(
                    dbFile.absolutePath,
                    null,
                    SQLiteDatabase.OPEN_READWRITE,
                )
            } catch (e: SQLiteException) {
                warn("Could not open ${dbFile.path} to rewrite hostnames: ${e.message}")
                return -1
            }
        try {
            db.beginTransaction()
            try {
                var updated = 0
                for (legacy in from) {
                    val stmt = db.compileStatement("UPDATE buckets SET hostname = ? WHERE hostname = ?")
                    try {
                        stmt.bindString(1, currentHostname)
                        stmt.bindString(2, legacy)
                        updated += stmt.executeUpdateDelete()
                    } finally {
                        stmt.close()
                    }
                }
                db.setTransactionSuccessful()
                if (updated > 0) {
                    info("Rewrote hostname on $updated bucket(s) to '$currentHostname'")
                }
                return updated
            } finally {
                db.endTransaction()
            }
        } catch (e: SQLiteException) {
            warn("Bucket hostname rewrite failed on ${dbFile.path}: ${e.message}")
            return -1
        } finally {
            db.close()
        }
    }

    private fun renameDir(from: File, to: File): Boolean {
        if (!from.exists()) return false
        if (to.exists()) {
            warn("Refusing to rename ${from.path} onto existing ${to.path}")
            return false
        }
        if (from.renameTo(to)) return true
        warn("renameTo failed for ${from.path} → ${to.path}")
        return false
    }

    private fun deleteRecursively(file: File): Boolean {
        if (!file.exists()) return false
        val deleted = file.deleteRecursively()
        if (!deleted) {
            warn("Failed to delete ${file.path}")
        }
        return deleted
    }

    // android.util.Log throws on the JVM unit-test stub.
    private fun info(message: String) {
        try {
            Log.i(TAG, message)
        } catch (_: RuntimeException) {
        }
    }

    private fun warn(message: String) {
        try {
            Log.w(TAG, message)
        } catch (_: RuntimeException) {
        }
    }
}
