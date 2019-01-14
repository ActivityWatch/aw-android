package net.activitywatch.android.models

import android.util.Log
import net.activitywatch.android.RustInterface
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

    val TAG = "BucketsContent"

    private val COUNT = 25

    init {
        reload()
    }

    fun reload() {
        ITEMS.clear()
        ITEM_MAP.clear()
        val ri = RustInterface()
        val buckets = ri.getBucketsJSON()
        for(bid in buckets.keys()) {
            addItem(buckets[bid] as JSONObject)
        }
        ITEMS.sortBy { it.getString("created") }
        ITEMS.reverse()
    }

    fun addItem(item: Bucket) {
        ITEMS.add(item)
        ITEM_MAP[item.getString("id")] = item
    }

    private fun createDummyItem(position: Int): Bucket {
        return Bucket("""{"id": "test $position", "created": "2019-01-01T16:20:00.1"}""")
    }

    private fun makeDetails(position: Int): String {
        val builder = StringBuilder()
        builder.append("Details about Item: ").append(position)
        for (i in 0 until position) {
            builder.append("\nMore details information here.")
        }
        return builder.toString()
    }
}
