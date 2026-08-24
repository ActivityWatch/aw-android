package net.activitywatch.android.widget

import org.junit.Assert.assertEquals
import org.junit.Test
import org.threeten.bp.LocalTime

class CategoryTimeWidgetUpdaterTest {

    @Test
    fun parseStartOfDay_readsMinutesFromNativeSetting() {
        assertEquals(
            LocalTime.of(4, 30),
            CategoryTimeWidgetUpdater.parseStartOfDay("\"04:30\"")
        )
    }

    @Test
    fun parseStartOfDay_fallsBackForMissingOrInvalidSetting() {
        assertEquals(LocalTime.of(4, 0), CategoryTimeWidgetUpdater.parseStartOfDay("null"))
        assertEquals(LocalTime.of(4, 0), CategoryTimeWidgetUpdater.parseStartOfDay("\"99:00\""))
    }

    private fun catEvent(duration: Double, vararg category: String): String {
        val cats = category.joinToString(",") { "\"$it\"" }
        return """{"duration":$duration,"data":{"${'$'}category":[$cats]}}"""
    }

    private fun response(vararg events: String) =
        """[{"cat_events":[${events.joinToString(",")}]}]"""

    /**
     * The widget groups by top-level category ($category[0]), so subcategories collapse
     * into a single row. This is the widget's compact display; see
     * ActivityWatch/aw-android#142 for the discussion.
     */
    @Test
    fun parseCategories_collapsesSubcategoriesIntoTopLevel() {
        val result = CategoryTimeWidgetUpdater.parseCategories(
            response(
                catEvent(60.0, "Work", "Programming"),
                catEvent(30.0, "Work", "Planning")
            )
        )

        assertEquals(listOf("Work" to 90_000L), result)
    }

    @Test
    fun parseCategories_totalIsUnchangedBySubcategorySplit() {
        val result = CategoryTimeWidgetUpdater.parseCategories(
            response(
                catEvent(60.0, "Work", "Programming"),
                catEvent(30.0, "Work", "Planning"),
                catEvent(10.0, "Media")
            )
        )

        assertEquals(100_000L, result.sumOf { it.second })
    }

    @Test
    fun parseCategories_mergesRepeatsOfTheSameTopLevel() {
        val result = CategoryTimeWidgetUpdater.parseCategories(
            response(
                catEvent(60.0, "Work", "Programming"),
                catEvent(15.0, "Work", "Planning")
            )
        )

        assertEquals(listOf("Work" to 75_000L), result)
    }

    @Test
    fun parseCategories_sortsByDurationDescending() {
        val result = CategoryTimeWidgetUpdater.parseCategories(
            response(
                catEvent(10.0, "Media", "Video"),
                catEvent(90.0, "Work", "Programming"),
                catEvent(50.0, "Comms")
            )
        )

        assertEquals(
            listOf("Work", "Comms", "Media"),
            result.map { it.first }
        )
    }

    /**
     * Regression for ActivityWatch/aw-android#142: with full-path grouping,
     * ["Uncategorized", "Browser"] and ["Uncategorized", "Games"] became separate rows.
     * Top-level grouping collapses all "Uncategorized > *" subcategories back into a
     * single "Uncategorized" row.
     */
    @Test
    fun parseCategories_collapsesUncategorizedSubcategories() {
        val result = CategoryTimeWidgetUpdater.parseCategories(
            response(
                catEvent(20.0, "Uncategorized", "Browser"),
                catEvent(10.0, "Uncategorized", "Games")
            )
        )

        assertEquals(listOf("Uncategorized" to 30_000L), result)
    }

    @Test
    fun parseCategories_keepsSingleLevelCategoriesUnprefixed() {
        val result = CategoryTimeWidgetUpdater.parseCategories(
            response(catEvent(20.0, "Uncategorized"))
        )

        assertEquals(listOf("Uncategorized" to 20_000L), result)
    }

    @Test
    fun parseCategories_returnsEmptyForEmptyResult() {
        assertEquals(emptyList<Pair<String, Long>>(), CategoryTimeWidgetUpdater.parseCategories("[]"))
    }
}
