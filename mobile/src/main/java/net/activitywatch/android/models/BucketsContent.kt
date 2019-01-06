package net.activitywatch.android.models

import org.json.JSONObject
import java.util.ArrayList
import java.util.HashMap

typealias Bucket = JSONObject

/**
 * Helper class for providing sample content for user interfaces created by
 * Android template wizards.
 *
 * TODO: Replace all uses of this class before publishing your app.
 */
object BucketsContent {

    /**
     * An array of sample (dummy) items.
     */
    val ITEMS: MutableList<Bucket> = ArrayList()

    /**
     * A map of sample (dummy) items, by ID.
     */
    val ITEM_MAP: MutableMap<String, Bucket> = HashMap()

    private val COUNT = 25

    init {
        // Add some sample items.
        for (i in 1..COUNT) {
            addItem(
                createDummyItem(
                    i
                )
            )
        }
    }

    private fun addItem(item: Bucket) {
        ITEMS.add(item)
        ITEM_MAP.put(item.getString("id"), item)
    }

    private fun createDummyItem(position: Int): Bucket {
        return Bucket("""{"id": "test $position", "created": "2019-01-01T16:20:00.1"}""")
    }

    private fun makeDetails(position: Int): String {
        val builder = StringBuilder()
        builder.append("Details about Item: ").append(position)
        for (i in 0..position - 1) {
            builder.append("\nMore details information here.")
        }
        return builder.toString()
    }
}
