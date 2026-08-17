package net.activitywatch.android.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoryTimeWidgetUpdaterTest {

    private fun catEvent(duration: Double, vararg category: String): String {
        val cats = category.joinToString(",") { "\"$it\"" }
        return """{"duration":$duration,"data":{"${'$'}category":[$cats]}}"""
    }

    private fun response(vararg events: String) =
        """[{"cat_events":[${events.joinToString(",")}]}]"""

    /**
     * Regression for ActivityWatch/aw-android#142: the widget rolled every event up to its
     * top-level category, so subcategories collapsed together and per-category times
     * disagreed with the Activity view (which groups by the full `$category` path) even
     * though the totals matched.
     */
    @Test
    fun parseCategories_keepsSubcategoriesSeparate() {
        val result = CategoryTimeWidgetUpdater.parseCategories(
            response(
                catEvent(60.0, "Work", "Programming"),
                catEvent(30.0, "Work", "Planning")
            )
        )

        assertEquals(
            listOf("Work > Programming" to 60_000L, "Work > Planning" to 30_000L),
            result
        )
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
    fun parseCategories_mergesRepeatsOfTheSamePath() {
        val result = CategoryTimeWidgetUpdater.parseCategories(
            response(
                catEvent(60.0, "Work", "Programming"),
                catEvent(15.0, "Work", "Programming")
            )
        )

        assertEquals(listOf("Work > Programming" to 75_000L), result)
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
            listOf("Work > Programming", "Comms", "Media > Video"),
            result.map { it.first }
        )
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
