package net.activitywatch.android.fragments

import android.support.v7.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView


import net.activitywatch.android.fragments.BucketListFragment.OnListFragmentInteractionListener

import kotlinx.android.synthetic.main.fragment_bucket.view.*
import net.activitywatch.android.R
import org.json.JSONObject
import org.threeten.bp.DateTimeUtils
import org.threeten.bp.Instant
import org.threeten.bp.temporal.ChronoUnit
import java.text.SimpleDateFormat

typealias Bucket = JSONObject

/**
 * [RecyclerView.Adapter] that can display a [DummyItem] and makes a call to the
 * specified [OnListFragmentInteractionListener].
 * TODO: Replace the implementation with code for your data type.
 */
class MyBucketRecyclerViewAdapter(
    private val mValues: List<Bucket>,
    private val mListener: OnListFragmentInteractionListener?
) : RecyclerView.Adapter<MyBucketRecyclerViewAdapter.ViewHolder>() {

    private val mOnClickListener: View.OnClickListener

    init {
        mOnClickListener = View.OnClickListener { v ->
            val item = v.tag as Bucket
            // Notify the active callbacks interface (the activity, if the fragment is attached to
            // one) that an item has been selected.
            mListener?.onListFragmentInteraction(item)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.fragment_bucket, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'")
        val item = mValues[position]
        holder.mIdView.text = item.getString("id")
        holder.mContentView.text = DateTimeUtils.toInstant(isoFormatter.parse(item.getString("created"))).truncatedTo(ChronoUnit.DAYS).toString().subSequence(0, 10)

        with(holder.mView) {
            tag = item
            setOnClickListener(mOnClickListener)
        }
    }

    override fun getItemCount(): Int = mValues.size

    inner class ViewHolder(val mView: View) : RecyclerView.ViewHolder(mView) {
        val mIdView: TextView = mView.item_number
        val mContentView: TextView = mView.content

        override fun toString(): String {
            return super.toString() + " '" + mContentView.text + "'"
        }
    }
}
