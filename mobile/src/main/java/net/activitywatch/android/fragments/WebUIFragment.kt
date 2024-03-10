package net.activitywatch.android.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView

import android.content.Intent.ACTION_VIEW
import android.util.Log
import android.webkit.URLUtil
import android.webkit.WebResourceRequest
import android.webkit.WebViewClient
import net.activitywatch.android.R
import java.lang.Thread.sleep

private const val TAG = "WebUI"

private const val ARG_URL = "url"

/**
 * A simple [Fragment] subclass.
 * Activities that contain this fragment must implement the
 * [WebUIFragment.OnFragmentInteractionListener] interface
 * to handle interaction events.
 * Use the [WebUIFragment.newInstance] factory method to
 * create an instance of this fragment.
 *
 */
class WebUIFragment : Fragment() {
    // TODO: Rename and change types of parameters
    private var listener: OnFragmentInteractionListener? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Inflate the layout for this fragment
        val view = inflater.inflate(R.layout.fragment_web_ui, container, false)

        // Enables WebView debugging, in testing builds
        // https://developers.google.com/web/tools/chrome-devtools/remote-debugging/webviews
        if (0 != view.context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        val myWebView: WebView = view.findViewById(R.id.webview) as WebView

        class MyWebViewClient : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String,
                failingUrl: String
            ) {
                // Retry
                // TODO: Find way to not show the blinking Android error page
                Log.e(TAG, "WebView received error: $description")
                sleep(100);
                arguments?.let {
                    it.getString(ARG_URL)?.let { it1 -> myWebView.loadUrl(it1) }
                }
            }

            // Open external links in external browser
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url.toString()
                if (URLUtil.isNetworkUrl(url)) {
                    if (url.startsWith("http://") || url.startsWith("https://")) {
                        if (!url.contains("//localhost:")) {
                            // Open the URL in an external browser
                            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            startActivity(i)
                            return true
                        }
                    }
                    // For all other URLs, load them inside the WebView
                    return false
                }
                return true
            }
        }
        myWebView.webViewClient = MyWebViewClient()

        myWebView.setDownloadListener { url, _, _, _, _ ->
            val i = Intent(ACTION_VIEW)
            i.data = Uri.parse(url)
            startActivity(i)
        }

        myWebView.settings.javaScriptEnabled = true
        myWebView.settings.domStorageEnabled = true
        myWebView.addJavascriptInterface(WebAppInterface(context), "Android")
        arguments?.let {
            it.getString(ARG_URL)?.let { it1 -> myWebView.loadUrl(it1) }
        }

        return view
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnFragmentInteractionListener) {
            listener = context
        } else {
            throw RuntimeException(context.toString() + " must implement OnFragmentInteractionListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     *
     *
     * See the Android Training lesson [Communicating with Other Fragments]
     * (http://developer.android.com/training/basics/fragments/communicating.html)
     * for more information.
     */
    interface OnFragmentInteractionListener {
        // TODO: Update argument type and name
        fun onFragmentInteraction(uri: Uri)
    }

    companion object {
        // TODO: Rename and change types and number of parameters
        @JvmStatic
        fun newInstance(url: String) =
            WebUIFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_URL, url)
                }
            }
    }
}

class WebAppInterface(private val mContext: Context) {
    @JavascriptInterface
    fun downloadCSV(csv: String, filename: String) {
        downloadFile(csv, filename, "text/csv")
    }

    @JavascriptInterface
    fun downloadJSON(csv: String, filename: String) {
        downloadFile(csv, filename, "application/json")
    }

    fun downloadFile(csv: String, filename: String, mimetype: String) {
        val file = File(mContext.getExternalFilesDir(null), filename)
        file.writeText(csv)
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(Uri.fromFile(file), mimetype)
        intent.flags = Intent.FLAG_ACTIVITY_NO_HISTORY
        mContext.startActivity(intent)
    }
}
