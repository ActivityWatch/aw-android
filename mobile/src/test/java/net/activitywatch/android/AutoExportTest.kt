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
    fun prune_keepIsClampedToAtLeastOne() {
        val existing = listOf("aw-export-h-2026-10-01.json", "aw-export-h-2026-10-02.json")
        assertEquals(listOf("aw-export-h-2026-10-01.json"), exportsToPrune(existing, "h", keep = 0))
    }
}
