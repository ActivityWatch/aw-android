package net.activitywatch.android

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SanitizedHostnameMigrationTest {
    @Test
    fun legacyHostnames_includesUnsanitizedDeviceNameAndModel() {
        assertEquals(
            listOf("POCO F8 Ultra", "Poco F8 Ultra", "Unknown", "unknown"),
            SanitizedHostnameMigration.legacyHostnames(
                current = "poco_f8_ultra",
                deviceName = "POCO F8 Ultra",
                model = "Poco F8 Ultra",
            ),
        )
    }

    @Test
    fun legacyHostnames_dropsNamesThatAlreadyMatch() {
        assertEquals(
            listOf("Unknown", "unknown"),
            SanitizedHostnameMigration.legacyHostnames(
                current = "poco_f8_ultra",
                deviceName = "poco_f8_ultra",
                model = "poco_f8_ultra",
            ),
        )
    }

    @Test
    fun isSafeDirName_rejectsPathElements() {
        assertFalse(SanitizedHostnameMigration.isSafeDirName(""))
        assertFalse(SanitizedHostnameMigration.isSafeDirName(".."))
        assertFalse(SanitizedHostnameMigration.isSafeDirName("a/b"))
        assertFalse(SanitizedHostnameMigration.isSafeDirName("a\\b"))
        assertTrue(SanitizedHostnameMigration.isSafeDirName("POCO F8 Ultra"))
        assertTrue(SanitizedHostnameMigration.isSafeDirName("poco_f8_ultra"))
    }

    @Test
    fun hostnamesToRewrite_onlyTouchesKnownLegacyValues() {
        assertEquals(
            listOf("POCO F8 Ultra"),
            SanitizedHostnameMigration.hostnamesToRewrite(
                existingHostnames = listOf("POCO F8 Ultra", "erik-mac", "poco_f8_ultra"),
                current = "poco_f8_ultra",
                legacy = listOf("POCO F8 Ultra", "Unknown"),
            ),
        )
    }

    @Test
    fun plan_renamesLegacyDirWhenSanitizedNameIsAbsent() {
        val actions =
            SanitizedHostnameMigration.planFolderMigration(
                existingHostnameDirs = setOf("POCO F8 Ultra"),
                deviceIdsByHostname = mapOf("POCO F8 Ultra" to setOf("dev-1")),
                currentHostname = "poco_f8_ultra",
                legacyHostnames = listOf("POCO F8 Ultra"),
                localDeviceId = "dev-1",
            )
        assertEquals(
            listOf(
                SanitizedHostnameMigration.FolderAction.RenameHostnameDir(
                    "POCO F8 Ultra",
                    "poco_f8_ultra",
                )
            ),
            actions,
        )
    }

    @Test
    fun plan_deletesStaleForkWhenBothDirsExistForSameDevice() {
        val actions =
            SanitizedHostnameMigration.planFolderMigration(
                existingHostnameDirs = setOf("POCO F8 Ultra", "poco_f8_ultra"),
                deviceIdsByHostname =
                    mapOf(
                        "POCO F8 Ultra" to setOf("dev-1"),
                        "poco_f8_ultra" to setOf("dev-1"),
                    ),
                currentHostname = "poco_f8_ultra",
                legacyHostnames = listOf("POCO F8 Ultra"),
                localDeviceId = "dev-1",
            )
        assertEquals(
            listOf(
                SanitizedHostnameMigration.FolderAction.DeleteStaleDeviceDir("POCO F8 Ultra", "dev-1"),
                SanitizedHostnameMigration.FolderAction.DeleteHostnameDir("POCO F8 Ultra"),
            ),
            actions,
        )
    }

    @Test
    fun plan_movesOnlyThisDeviceWhenLegacyDirHasOtherIds() {
        val actions =
            SanitizedHostnameMigration.planFolderMigration(
                existingHostnameDirs = setOf("POCO F8 Ultra"),
                deviceIdsByHostname = mapOf("POCO F8 Ultra" to setOf("dev-1", "other")),
                currentHostname = "poco_f8_ultra",
                legacyHostnames = listOf("POCO F8 Ultra"),
                localDeviceId = "dev-1",
            )
        assertEquals(
            listOf(
                SanitizedHostnameMigration.FolderAction.MoveDeviceDir(
                    "POCO F8 Ultra",
                    "poco_f8_ultra",
                    "dev-1",
                )
            ),
            actions,
        )
    }

    @Test
    fun apply_renamesLegacyFolderOnDisk() {
        val root = File.createTempFile("aw-sync-mig", null)
        assertTrue(root.delete())
        assertTrue(root.mkdirs())
        try {
            val deviceId = "41662faa-7dc4-4e50-970b-f986d59a1819"
            val oldDb = File(root, "POCO F8 Ultra/$deviceId/test.db")
            assertTrue(oldDb.parentFile?.mkdirs() == true)
            oldDb.writeText("legacy")

            val applied =
                SanitizedHostnameMigration.migrateSyncFolders(
                    syncDir = root,
                    currentHostname = "poco_f8_ultra",
                    legacyHostnames = listOf("POCO F8 Ultra"),
                    localDeviceId = deviceId,
                )
            assertEquals(1, applied.moved)
            assertFalse(File(root, "POCO F8 Ultra").exists())
            assertEquals("legacy", File(root, "poco_f8_ultra/$deviceId/test.db").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun apply_removesStaleForkWhenSanitizedFolderAlreadyExists() {
        val root = File.createTempFile("aw-sync-fork", null)
        assertTrue(root.delete())
        assertTrue(root.mkdirs())
        try {
            val deviceId = "41662faa-7dc4-4e50-970b-f986d59a1819"
            val oldDb = File(root, "POCO F8 Ultra/$deviceId/test.db")
            val newDb = File(root, "poco_f8_ultra/$deviceId/test.db")
            assertTrue(oldDb.parentFile?.mkdirs() == true)
            assertTrue(newDb.parentFile?.mkdirs() == true)
            oldDb.writeText("old")
            newDb.writeText("new")

            val applied =
                SanitizedHostnameMigration.migrateSyncFolders(
                    syncDir = root,
                    currentHostname = "poco_f8_ultra",
                    legacyHostnames = listOf("POCO F8 Ultra"),
                    localDeviceId = deviceId,
                )
            assertEquals(2, applied.moved)
            assertFalse(File(root, "POCO F8 Ultra").exists())
            assertEquals("new", newDb.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun plan_withoutDeviceId_renamesOnlySingleUnambiguousCandidate() {
        val actions =
            SanitizedHostnameMigration.planFolderMigration(
                existingHostnameDirs = setOf("POCO F8 Ultra"),
                deviceIdsByHostname = mapOf("POCO F8 Ultra" to setOf("dev-1")),
                currentHostname = "poco_f8_ultra",
                legacyHostnames = listOf("POCO F8 Ultra"),
                localDeviceId = null,
            )
        assertEquals(
            listOf(
                SanitizedHostnameMigration.FolderAction.RenameHostnameDir(
                    "POCO F8 Ultra",
                    "poco_f8_ultra",
                )
            ),
            actions,
        )
    }

    @Test
    fun plan_withoutDeviceId_leavesAmbiguousCandidatesAlone() {
        val actions =
            SanitizedHostnameMigration.planFolderMigration(
                existingHostnameDirs = setOf("POCO F8 Ultra", "Poco F8 Ultra"),
                deviceIdsByHostname =
                    mapOf(
                        "POCO F8 Ultra" to setOf("dev-1"),
                        "Poco F8 Ultra" to setOf("other"),
                    ),
                currentHostname = "poco_f8_ultra",
                legacyHostnames = listOf("POCO F8 Ultra", "Poco F8 Ultra"),
                localDeviceId = null,
            )
        assertTrue(actions.isEmpty())
    }

    @Test
    fun plan_withoutDeviceId_leavesMultiDeviceLegacyDirAlone() {
        // A legacy dir holding several device ids cannot be attributed to this
        // device; renaming it wholesale would fold all of them under the current
        // hostname.
        val actions =
            SanitizedHostnameMigration.planFolderMigration(
                existingHostnameDirs = setOf("POCO F8 Ultra"),
                deviceIdsByHostname = mapOf("POCO F8 Ultra" to setOf("dev-1", "dev-2")),
                currentHostname = "poco_f8_ultra",
                legacyHostnames = listOf("POCO F8 Ultra"),
                localDeviceId = null,
            )
        assertEquals(emptyList<SanitizedHostnameMigration.FolderAction>(), actions)
    }

    @Test
    fun plan_withoutDeviceId_andSanitizedDirExists_leavesLegacyDirWithDeviceEntries() {
        // Legacy dir has device-id subdirs that can't be attributed without the local
        // device id. Previously the planner added DeleteHostnameDir here, which
        // applyFolderMigration always rejected (dir not empty), making MigrationResult
        // report failed=1 and causing ensureLegacyFoldersMigrated to retry every sync.
        val actions =
            SanitizedHostnameMigration.planFolderMigration(
                existingHostnameDirs = setOf("POCO F8 Ultra", "poco_f8_ultra"),
                deviceIdsByHostname =
                    mapOf(
                        "POCO F8 Ultra" to setOf("other"),
                        "poco_f8_ultra" to setOf("dev-1"),
                    ),
                currentHostname = "poco_f8_ultra",
                legacyHostnames = listOf("POCO F8 Ultra"),
                localDeviceId = null,
            )
        assertEquals(emptyList<SanitizedHostnameMigration.FolderAction>(), actions)
    }

    @Test
    fun plan_withoutDeviceId_andSanitizedDirExists_deletesEmptyLegacyDir() {
        // Legacy dir has no device-id subdirs (already cleaned up); safe to delete.
        val actions =
            SanitizedHostnameMigration.planFolderMigration(
                existingHostnameDirs = setOf("POCO F8 Ultra", "poco_f8_ultra"),
                deviceIdsByHostname =
                    mapOf(
                        "POCO F8 Ultra" to emptySet(),
                        "poco_f8_ultra" to setOf("dev-1"),
                    ),
                currentHostname = "poco_f8_ultra",
                legacyHostnames = listOf("POCO F8 Ultra"),
                localDeviceId = null,
            )
        assertEquals(
            listOf(SanitizedHostnameMigration.FolderAction.DeleteHostnameDir("POCO F8 Ultra")),
            actions,
        )
    }

    @Test
    fun plan_withoutDeviceId_andSanitizedDirExists_leavesLegacyDirHoldingOnlyFilesAlone() {
        // A legacy dir whose only entries are regular files looks empty to the
        // device-id map, but applyFolderMigration refuses to delete a dir with any
        // entries. Planning that delete would make the pass report failed=1 and
        // retry on every sync forever.
        val actions =
            SanitizedHostnameMigration.planFolderMigration(
                existingHostnameDirs = setOf("POCO F8 Ultra", "poco_f8_ultra"),
                deviceIdsByHostname =
                    mapOf(
                        "POCO F8 Ultra" to emptySet(),
                        "poco_f8_ultra" to setOf("dev-1"),
                    ),
                currentHostname = "poco_f8_ultra",
                legacyHostnames = listOf("POCO F8 Ultra"),
                localDeviceId = null,
                hostnameDirsWithUnmanagedEntries = setOf("POCO F8 Ultra"),
            )
        assertEquals(emptyList<SanitizedHostnameMigration.FolderAction>(), actions)
    }

    @Test
    fun apply_doesNotFailOnLegacyDirWithStrayFileAlongsideStaleDeviceDir() {
        val root = File.createTempFile("aw-sync-stray", null)
        assertTrue(root.delete())
        assertTrue(root.mkdirs())
        try {
            val deviceId = "41662faa-7dc4-4e50-970b-f986d59a1819"
            val legacyDeviceDir = File(root, "POCO F8 Ultra/$deviceId")
            val newDeviceDir = File(root, "poco_f8_ultra/$deviceId")
            assertTrue(legacyDeviceDir.mkdirs())
            assertTrue(newDeviceDir.mkdirs())
            File(root, "POCO F8 Ultra/.nomedia").writeText("")
            File(legacyDeviceDir, "test.db").writeText("old")

            val applied =
                SanitizedHostnameMigration.migrateSyncFolders(
                    syncDir = root,
                    currentHostname = "poco_f8_ultra",
                    legacyHostnames = listOf("POCO F8 Ultra"),
                    localDeviceId = deviceId,
                )
            // The stale device dir goes; the hostname dir cannot be deleted while
            // the stray file is in it, and that must not count as a failure —
            // otherwise the migration never marks itself done and retries forever.
            assertEquals(1, applied.moved)
            assertEquals(0, applied.failed)
            assertFalse(legacyDeviceDir.exists())
            assertTrue(File(root, "POCO F8 Ultra/.nomedia").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun migrate_doesNotInterpretLegacyNamesAsPendingRenames() {
        val sync = kotlin.io.path.createTempDirectory("aw-sync-legacy-dot").toFile()
        try {
            val legacy = File(sync, ".pixel8.aw-rename/dev-1/sync.db")
            assertTrue(legacy.parentFile!!.mkdirs())
            legacy.writeText("legacy")
            assertTrue(File(sync, "pixel8/peer").mkdirs())
            File(sync, ".notes.aw-rename").writeText("unrelated file")
            val result = SanitizedHostnameMigration.migrateSyncFolders(
                sync, "pixel8_aw-rename", listOf(".pixel8.aw-rename"), "dev-1",
            )
            assertEquals(0, result.failed)
            assertEquals("legacy", File(sync, "pixel8_aw-rename/dev-1/sync.db").readText())
            assertEquals("unrelated file", File(sync, ".notes.aw-rename").readText())
            assertTrue(File(sync, "pixel8/peer").isDirectory)
        } finally {
            sync.deleteRecursively()
        }
    }

    @Test
    fun migrate_recoversInterruptedCaseRenameBeforePlanning() {
        val sync = kotlin.io.path.createTempDirectory("aw-sync-recover").toFile()
        try {
            recordPendingRename(sync)
            val db = File(sync, ".aw-rename-test/dev-1/sync.db")
            assertTrue(db.parentFile!!.mkdirs())
            db.writeText("interrupted")
            SanitizedHostnameMigration.migrateSyncFolders(sync, "pixel8", listOf("PIXEL8"), "dev-1")
            assertEquals("interrupted", File(sync, "pixel8/dev-1/sync.db").readText())
            assertFalse(File(sync, ".aw-rename-test").exists())
            assertFalse(SanitizedHostnameMigration.caseRenameJournal(sync).exists())
        } finally {
            sync.deleteRecursively()
        }
    }

    @Test
    fun migrate_doesNotOverwriteTargetWhenRecoveringInterruptedRename() {
        val root = kotlin.io.path.createTempDirectory("aw-sync-conflict").toFile()
        val sync = File(root, "sync").apply { mkdir() }
        try {
            recordPendingRename(sync)
            val pending = File(sync, ".aw-rename-test/dev-1/sync.db")
            assertTrue(pending.parentFile!!.mkdirs())
            pending.writeText("pending")
            val target = File(sync, "pixel8/dev-1/sync.db")
            assertTrue(target.parentFile!!.mkdirs())
            target.writeText("target")
            val result = SanitizedHostnameMigration.migrateSyncFolders(
                sync, "pixel8", listOf("PIXEL8"), "dev-1",
            )
            assertEquals(1, result.failed)
            assertTrue(result.recoveryPending)
            assertEquals("pending", pending.readText())
            assertEquals("target", target.readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun migrate_recoversPendingRenameEvenWhenCurrentHostnameChanged() {
        val sync = kotlin.io.path.createTempDirectory("aw-sync-changed").toFile()
        try {
            recordPendingRename(sync)
            val pending = File(sync, ".aw-rename-test/dev-1/sync.db")
            assertTrue(pending.parentFile!!.mkdirs())
            pending.writeText("pending")
            val result = SanitizedHostnameMigration.migrateSyncFolders(
                sync, "new_phone", emptyList(), "dev-1",
            )
            assertEquals(0, result.failed)
            assertEquals("pending", File(sync, "pixel8/dev-1/sync.db").readText())
            assertFalse(File(sync, ".aw-rename-test").exists())
        } finally {
            sync.deleteRecursively()
        }
    }

    private fun recordPendingRename(sync: File) {
        java.io.DataOutputStream(
            java.io.FileOutputStream(SanitizedHostnameMigration.caseRenameJournal(sync))
        ).use {
            it.writeUTF("PIXEL8")
            it.writeUTF(".aw-rename-test")
            it.writeUTF("pixel8")
        }
    }

    @Test
    fun migrate_clearsJournalBeforeFirstOrAfterSecondRename() {
        for (name in listOf("PIXEL8", "pixel8")) {
            val sync = kotlin.io.path.createTempDirectory("aw-sync-journal").toFile()
            try {
                recordPendingRename(sync)
                assertTrue(File(sync, "$name/dev-1").mkdirs())
                val result = SanitizedHostnameMigration.migrateSyncFolders(
                    sync, "pixel8", listOf("PIXEL8"), "dev-1",
                )
                assertEquals(0, result.failed)
                assertTrue(File(sync, "pixel8/dev-1").isDirectory)
                assertFalse(SanitizedHostnameMigration.caseRenameJournal(sync).exists())
            } finally {
                sync.deleteRecursively()
            }
        }
    }

    @Test
    fun migrate_rejectsInvalidJournalWithoutTouchingData() {
        val sync = kotlin.io.path.createTempDirectory("aw-sync-invalid-journal").toFile()
        try {
            File(sync, "PIXEL8/dev-1").mkdirs()
            SanitizedHostnameMigration.caseRenameJournal(sync).writeText("incomplete")
            val result = SanitizedHostnameMigration.migrateSyncFolders(sync, "pixel8", listOf("PIXEL8"), "dev-1")
            assertEquals(1, result.failed)
            assertTrue(result.recoveryPending)
            assertTrue(File(sync, "PIXEL8/dev-1").isDirectory)
        } finally {
            SanitizedHostnameMigration.caseRenameJournal(sync).delete()
            sync.deleteRecursively()
        }
    }

    @Test
    fun renameCaseOnly_refusesSeparateExistingTarget() {
        val sync = kotlin.io.path.createTempDirectory("aw-sync-existing-target").toFile()
        try {
            val from = File(sync, "PIXEL8").apply { mkdir() }
            val to = File(sync, "pixel8")
            org.junit.Assume.assumeTrue(to.mkdir())
            File(from, "source.db").writeText("source")
            File(to, "target.db").writeText("target")
            assertFalse(SanitizedHostnameMigration.renameCaseOnly(from, to))
            assertEquals("source", File(from, "source.db").readText())
            assertEquals("target", File(to, "target.db").readText())
        } finally {
            sync.deleteRecursively()
        }
    }

    @Test
    fun renameCaseOnly_movesContentsViaTemporaryName() {
        val sync = kotlin.io.path.createTempDirectory("aw-sync-case").toFile()
        try {
            val from = File(sync, "PIXEL8")
            File(from, "dev-1").mkdirs()
            File(File(from, "dev-1"), "sync.db").writeText("data")

            assertTrue(SanitizedHostnameMigration.renameCaseOnly(from, File(sync, "pixel8")))

            assertEquals(listOf("pixel8"), sync.list()!!.toList())
            assertEquals("data", File(sync, "pixel8/dev-1/sync.db").readText())
        } finally {
            sync.deleteRecursively()
        }
    }

    @Test
    fun isCaseFoldedSameDir_falseWhenBothCaseVariantsReallyExist() {
        val sync = kotlin.io.path.createTempDirectory("aw-sync-case").toFile()
        try {
            assertTrue(File(sync, "PIXEL8").mkdirs())
            org.junit.Assume.assumeTrue(File(sync, "pixel8").mkdirs())
            // Case-sensitive storage with two real dirs: not the same dir.
            assertFalse(SanitizedHostnameMigration.isCaseFoldedSameDir(sync, "PIXEL8", "pixel8"))
            assertFalse(SanitizedHostnameMigration.isCaseFoldedSameDir(sync, "PIXEL8", "PIXEL8"))
            assertFalse(SanitizedHostnameMigration.isCaseFoldedSameDir(sync, "PIXEL8", "other"))
        } finally {
            sync.deleteRecursively()
        }
    }
}

