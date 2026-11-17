package net.activitywatch.android.fragments

import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import androidx.fragment.app.Fragment
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import net.activitywatch.android.R
import net.activitywatch.android.watcher.UsageStatsWatcher
import java.lang.Exception

private const val TAG = "TestFragment"

class TestFragment : Fragment() {

    companion object {
        fun newInstance() = TestFragment()
    }

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

        // The only interactive control is the hidden debug button below; the
        // screen keeps no fragment-owned state, so it deliberately has no
        // ViewModel (a placeholder one used to be created and never read).
        val usw = UsageStatsWatcher(requireContext())

        val button = view?.findViewById(R.id.button) as Button
        button.setOnClickListener {
            Log.w(TAG, "log data button clicked")
            usw.sendHeartbeats()
        }
    }
}
