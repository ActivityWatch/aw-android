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
            assertEquals(1, applied)
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
            assertEquals(2, applied)
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
    fun plan_withoutDeviceId_andSanitizedDirExistsOnlyPlansEmptyDirDeletes() {
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
        assertEquals(
            listOf(SanitizedHostnameMigration.FolderAction.DeleteHostnameDir("POCO F8 Ultra")),
            actions,
        )
    }
}
