package net.activitywatch.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoExportTest {
    @Test
    fun interval_defaultsToOffForUnknownOrMissing() {
        assertEquals(AutoExportInterval.OFF, AutoExportInterval.fromName(null))
        assertEquals(AutoExportInterval.OFF, AutoExportInterval.fromName("hourly"))
        assertEquals(AutoExportInterval.WEEKLY, AutoExportInterval.fromName("WEEKLY"))
        assertEquals(7L, AutoExportInterval.WEEKLY.days)
    }

    @Test
    fun filename_embedsHostAndDate() {
        assertEquals("aw-export-pixel-2026-10-02.json", autoExportFilename("pixel", "2026-10-02"))
    }

    @Test
    fun prune_keepsNewestAndNeverTouchesForeignFiles() {
        val existing = listOf(
            "aw-export-pixel-2026-09-30.json",
            "aw-export-pixel-2026-10-02.json",
            "aw-export-pixel-2026-10-01.json",
            "aw-export-other-2026-01-01.json", // another device
            "aw-export-pixel-notes.json", // not our date scheme
            "photo.jpg",
        )
        assertEquals(
            listOf("aw-export-pixel-2026-09-30.json"),
            exportsToPrune(existing, "pixel", keep = 2),
        )
        assertTrue(exportsToPrune(existing, "pixel", keep = 3).isEmpty())
    }

    @Test
    fun tempExportsToClear_matchesAnyDateAndBothSuffixes() {
        val existing = listOf(
            "aw-export-pixel-2026-10-01.json.tmp",       // yesterday's orphaned download
            "aw-export-pixel-2026-10-01.json.tmp.json",  // same, provider appended extension
            "aw-export-pixel-2026-10-02.json.tmp",       // today's (also cleared pre-create)
            "aw-export-pixel-2026-10-02.json",            // completed — must not be touched
            "aw-export-other-2026-10-01.json.tmp",        // different device — must not be touched
            "photo.jpg",
        )
        assertEquals(
            listOf(
                "aw-export-pixel-2026-10-01.json.tmp",
                "aw-export-pixel-2026-10-01.json.tmp.json",
                "aw-export-pixel-2026-10-02.json.tmp",
            ).sorted(),
            tempExportsToClear(existing, "pixel").sorted(),
        )
    }

    @Test
    fun tempExportsToClear_doesNotMatchPrefixShareDevice() {
        // "pixel-abc" is a prefix of "pixel-abc123"; cleanup must not cross device namespaces.
        val existing = listOf(
            "aw-export-pixel-abc-2026-10-01.json.tmp",    // this device — must be cleared
            "aw-export-pixel-abc123-2026-10-01.json.tmp", // different device — must not be touched
        )
        assertEquals(
            listOf("aw-export-pixel-abc-2026-10-01.json.tmp"),
            tempExportsToClear(existing, "pixel-abc"),
        )
    }

    @Test
    fun cleanupIgnoresInvalidCalendarDates() {
        val invalid = listOf("2026-99-99", "0000-00-00", "2026-02-29", "2026-04-31")
        val names = invalid.map { "aw-export-h-$it.json" }
        assertTrue(exportsToPrune(names, "h", keep = 1).isEmpty())
        assertTrue(tempExportsToClear(names.flatMap { listOf("$it.tmp", "$it.tmp.json") }, "h").isEmpty())
        val restored = mutableListOf<String>()
        recoverInterruptedExports(names.flatMap { listOf("$it.bak", "$it.bak.json") }, "h") { backup, _ ->
            restored.add(backup)
            true
        }
        assertTrue(restored.isEmpty())
        assertEquals(listOf("aw-export-h-2024-02-29.json.tmp"),
            tempExportsToClear(listOf("aw-export-h-2024-02-29.json.tmp"), "h"))
    }

    @Test
    fun prune_keepIsClampedToAtLeastOne() {
        val existing = listOf("aw-export-h-2026-10-01.json", "aw-export-h-2026-10-02.json")
        assertEquals(listOf("aw-export-h-2026-10-01.json"), exportsToPrune(existing, "h", keep = 0))
    }
}
