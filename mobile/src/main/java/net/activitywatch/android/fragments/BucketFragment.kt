package net.activitywatch.android.fragments

import android.arch.lifecycle.ViewModelProviders
import android.os.Bundle
import android.support.v4.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

import net.activitywatch.android.R
import net.activitywatch.android.models.BucketViewModel

class BucketFragment : Fragment() {

    companion object {
        fun newInstance() = BucketFragment()
    }

    private lateinit var viewModel: BucketViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.bucket_fragment, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        viewModel = ViewModelProviders.of(this).get(BucketViewModel::class.java)
        // TODO: Use the ViewModel
    }

}
