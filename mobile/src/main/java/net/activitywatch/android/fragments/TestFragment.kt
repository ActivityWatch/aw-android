package net.activitywatch.android.fragments

import android.arch.lifecycle.ViewModelProviders
import android.os.Bundle
import android.support.design.widget.Snackbar
import android.support.v4.app.Fragment
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import net.activitywatch.android.R
import net.activitywatch.android.watcher.UsageStatsWatcher
import net.activitywatch.android.models.TestViewModel


class TestFragment : Fragment() {

    companion object {
        fun newInstance() = TestFragment()
    }

    private lateinit var viewModel: TestViewModel

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.test_fragment, container, false)

        val textView: TextView = view.findViewById(R.id.welcome_text)
        textView.isClickable = true
        textView.movementMethod = LinkMovementMethod.getInstance()

        return view
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        viewModel = ViewModelProviders.of(this).get(TestViewModel::class.java)
        // TODO: Use the ViewModel

        val button = view?.findViewById(R.id.button) as Button
        button.setOnClickListener {
            val context = activity
            if(context != null) {
                val usw = UsageStatsWatcher(context)
                usw.sendHeartbeats()
            }
        }
    }
}
