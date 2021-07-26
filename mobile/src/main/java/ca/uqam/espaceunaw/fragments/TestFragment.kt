package ca.uqam.espaceunaw.fragments

import androidx.lifecycle.ViewModelProviders
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import ca.uqam.espaceunaw.R
import ca.uqam.espaceunaw.watcher.UsageStatsWatcher
import ca.uqam.espaceunaw.models.TestViewModel

private const val TAG = "TestFragment"

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

        val usw = UsageStatsWatcher(context!!)

        val button = view?.findViewById(R.id.button) as Button
        button.setOnClickListener {
            Log.w(TAG, "log data button clicked")
            usw.sendHeartbeats()
        }
    }
}
