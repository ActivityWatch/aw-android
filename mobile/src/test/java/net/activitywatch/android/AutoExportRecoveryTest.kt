package net.activitywatch.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoExportRecoveryTest {
    @Test
    fun restoresInterruptedPromotionsAcrossDatesAndMimeSuffixes() {
        val old = "aw-export-pixel-abc123-2026-10-01.json"
        val today = "aw-export-pixel-abc123-2026-10-02.json"
        val calls = mutableListOf<Pair<String, String>>()
        recoverInterruptedExports(
            listOf("$old.bak", "$today.bak.json", old, "photo.bak",
                "aw-export-other-2026-10-01.json.bak",
                "aw-export-pixel-abc123-notes.json.bak"),
            "pixel-abc123",
        ) { backup, final -> calls.add(backup to final); true }
        assertEquals(listOf("$old.bak" to old, "$today.bak.json" to today), calls)
    }

    @Test
    fun recoveryKeepsNewestContentWhenABackupAlsoExists() {
        // Promotion is rename-only, so a `.bak` beside a final name means the export
        // completed and only the stale backup survived (or its delete failed): the visible
        // final is kept and the backup dropped. The backup is restored only when no final
        // exists (an interrupted rename). The callback below mirrors that production policy;
        // recoverInterruptedExports itself only selects candidates and invokes it.
        val final = "aw-export-h-2026-10-01.json"
        listOf(null, "partial", "new complete").forEach { replacement ->
            val files = mutableMapOf("$final.bak" to "old complete")
            if (replacement != null) files[final] = replacement
            recoverInterruptedExports(files.keys.toList(), "h") { backup, name ->
                if (files.containsKey(name)) {
                    files.remove(backup)
                } else {
                    files[name] = files.remove(backup)!!
                }
                true
            }
            assertEquals(mapOf(final to (replacement ?: "old complete")), files)
        }
    }

    @Test
    fun thrownRestoreStopsBeforeAnotherBackupCanBeTouched() {
        val names = listOf("aw-export-h-2026-10-01.json.bak", "aw-export-h-2026-10-02.json.bak")
        val calls = mutableListOf<String>()
        try {
            recoverInterruptedExports(names, "h") { backup, _ ->
                calls.add(backup)
                throw SecurityException("grant revoked")
            }
            throw AssertionError("failed recovery must stop the export")
        } catch (e: SecurityException) {
            assertEquals("grant revoked", e.message)
        }
        assertEquals(listOf(names.first()), calls)
    }

    @Test
    fun failedRestoreStopsBeforeAnotherBackupCanBeTouched() {
        val names = listOf("aw-export-h-2026-10-01.json.bak", "aw-export-h-2026-10-02.json.bak")
        val calls = mutableListOf<String>()
        try {
            recoverInterruptedExports(names, "h") { backup, _ -> calls.add(backup); false }
            throw AssertionError("failed recovery must stop the export")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains(names.first()))
        }
        assertEquals(listOf(names.first()), calls)
    }
}
