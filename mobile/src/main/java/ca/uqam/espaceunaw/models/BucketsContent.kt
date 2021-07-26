package ca.uqam.espaceunaw.models

import android.util.Log
import ca.uqam.espaceunaw.RustInterface
import org.json.JSONObject
import java.util.ArrayList
import java.util.HashMap

typealias BucketObj = JSONObject

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
    val ITEMS: MutableList<BucketObj> = ArrayList()

    /**
     * A map of sample (dummy) items, by ID.
     */
    val ITEM_MAP: MutableMap<String, BucketObj> = HashMap()

    val TAG = "BucketsContent"

    private val COUNT = 25

    init {
        reload()
    }

    fun reload() {
        Log.i(TAG, "Reloading buckets")
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

    fun addItem(item: BucketObj) {
        ITEMS.add(item)
        ITEM_MAP[item.getString("id")] = item
    }

    private fun createDummyItem(position: Int): BucketObj {
        return BucketObj("""{"id": "test $position", "created": "2019-01-01T16:20:00.1"}""")
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
