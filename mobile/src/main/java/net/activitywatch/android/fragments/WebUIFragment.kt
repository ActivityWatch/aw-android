package net.activitywatch.android.fragments

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import androidx.core.content.FileProvider
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
import java.io.File
import java.lang.Thread.sleep
import java.net.URI

private const val TAG = "WebUI"

private const val ARG_URL = "url"

// The embedded server lives on loopback, so keep those navigations inside the app WebView.
internal fun isEmbeddedActivityWatchUrl(url: String): Boolean {
    val uri = try {
        URI(url)
    } catch (_: Exception) {
        return false
    }

    if (uri.scheme != "http" && uri.scheme != "https") {
        return false
    }

    // java.net.URI.getHost() returns IPv6 addresses with brackets, e.g. "[::1]"
    return when (uri.host?.lowercase()) {
        "localhost", "127.0.0.1", "[::1]" -> true
        else -> false
    }
}

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
                        if (!isEmbeddedActivityWatchUrl(url)) {
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
        myWebView.addJavascriptInterface(WebAppInterface(requireContext()), "Android")
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
    fun downloadJSON(json: String, filename: String) {
        downloadFile(json, filename, "application/json")
    }

    private fun downloadFile(content: String, filename: String, mimetype: String) {
        // Strip path components from the JS-supplied name to prevent export-root escape
        val safeName = File(filename).name.takeIf { it.isNotEmpty() } ?: "export"
        val externalDir = mContext.getExternalFilesDir(null) ?: run {
            Log.e(TAG, "External files directory unavailable")
            return
        }
        val file = File(externalDir, safeName)
        try {
            file.writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write export file: ${e.message}")
            return
        }
        // FileProvider required on API 24+: Uri.fromFile() throws FileUriExposedException
        val uri = FileProvider.getUriForFile(
            mContext,
            "${mContext.packageName}.provider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW)
        intent.setDataAndType(uri, mimetype)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NO_HISTORY)
        try {
            mContext.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No viewer app found for $mimetype", e)
        }
    }
}
