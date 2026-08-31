package net.activitywatch.android.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import net.activitywatch.android.R
import net.activitywatch.android.ensureDashboardApiKey
import org.json.JSONObject
import java.io.File
import java.lang.Thread.sleep
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

private const val TAG = "WebUI"

private const val ARG_URL = "url"

// Stay under Binder's ~1 MiB transaction limit when shuttling export bodies from JS.
internal const val EXPORT_BRIDGE_CHUNK_SIZE = 256 * 1024

// The bundled web UI saves files with <a download> + blob: URLs. Android WebView
// does not persist those, and DownloadListener often never fires for them.
internal val ANDROID_EXPORT_HOOK_JS = """
(function () {
  if (window.__awAndroidExportHook) return;
  window.__awAndroidExportHook = true;

  var blobs = Object.create(null);
  window.__awAndroidBlobs = blobs;

  var createObjectURL = URL.createObjectURL.bind(URL);
  var revokeObjectURL = URL.revokeObjectURL.bind(URL);
  URL.createObjectURL = function (obj) {
    var url = createObjectURL(obj);
    if (typeof Blob !== 'undefined' && obj instanceof Blob) {
      blobs[url] = obj;
    }
    return url;
  };
  URL.revokeObjectURL = function (url) {
    delete blobs[url];
    revokeObjectURL(url);
  };

  var CHUNK = $EXPORT_BRIDGE_CHUNK_SIZE;

  function sendText(text, filename, mimeType) {
    if (typeof Android === 'undefined') return;
    Android.beginExport(filename, mimeType || '');
    text = String(text || '');
    for (var i = 0; i < text.length; i += CHUNK) {
      Android.appendExport(text.substring(i, i + CHUNK));
    }
    Android.finishExport();
  }

  window.__awAndroidSendBlob = function (url, filename) {
    var blob = blobs[url];
    if (!blob) return false;
    delete blobs[url];
    filename = filename || 'export';
    var reader = new FileReader();
    reader.onloadend = function () {
      var mime = blob.type || (/\.csv${'$'}/i.test(filename) ? 'text/csv' : 'application/json');
      sendText(reader.result, filename, mime);
    };
    reader.readAsText(blob);
    return true;
  };

  document.addEventListener('click', function (event) {
    var el = event.target;
    while (el && el.tagName !== 'A') el = el.parentElement;
    if (!el || !el.hasAttribute('download')) return;
    var href = el.href;
    if (!href || !blobs[href]) return;
    event.preventDefault();
    event.stopPropagation();
    window.__awAndroidSendBlob(href, el.getAttribute('download') || 'export');
  }, true);
})();
""".trimIndent()

internal data class PendingExport(
    val filename: String,
    val mimeType: String,
    val cacheFile: File,
) {
    fun readContent(): String = cacheFile.readText(StandardCharsets.UTF_8)

    fun deleteCache() {
        if (cacheFile.exists() && !cacheFile.delete()) {
            Log.w(TAG, "Failed to delete export cache ${cacheFile.name}")
        }
    }
}

internal data class ExportQueueSnapshot(
    val items: List<PendingExport>,
    val hasInFlight: Boolean,
)

internal const val STATE_EXPORT_PATHS = "aw_export_paths"
internal const val STATE_EXPORT_NAMES = "aw_export_names"
internal const val STATE_EXPORT_MIMES = "aw_export_mimes"
internal const val STATE_EXPORT_IN_FLIGHT = "aw_export_in_flight"

/** Serializes Save-to pickers so a later export cannot overwrite an earlier one. */
internal class ExportSaveQueue {
    private val queue = ArrayDeque<PendingExport>()
    var inFlight: PendingExport? = null
        private set

    fun enqueue(export: PendingExport) {
        queue.add(export)
    }

    fun beginNext(): PendingExport? {
        if (inFlight != null) return null
        val next = queue.firstOrNull() ?: return null
        inFlight = next
        return next
    }

    fun completeInFlight(): PendingExport? {
        val current = inFlight ?: return null
        inFlight = null
        if (queue.isNotEmpty() && queue.first() == current) {
            queue.removeFirst()
        }
        return current
    }

    fun snapshot(): ExportQueueSnapshot = ExportQueueSnapshot(queue.toList(), inFlight != null)

    fun restore(snapshot: ExportQueueSnapshot) {
        queue.clear()
        inFlight = null
        val originalFirst = snapshot.items.firstOrNull()
        for (item in snapshot.items) {
            if (item.cacheFile.isFile) {
                queue.add(item)
            }
        }
        if (snapshot.hasInFlight && originalFirst != null && originalFirst.cacheFile.isFile) {
            inFlight = queue.firstOrNull()
        }
    }

    val isEmpty: Boolean get() = queue.isEmpty()
}

internal fun writeExportSnapshot(outState: Bundle, snapshot: ExportQueueSnapshot) {
    outState.putStringArrayList(
        STATE_EXPORT_PATHS,
        ArrayList(snapshot.items.map { it.cacheFile.absolutePath }),
    )
    outState.putStringArrayList(
        STATE_EXPORT_NAMES,
        ArrayList(snapshot.items.map { it.filename }),
    )
    outState.putStringArrayList(
        STATE_EXPORT_MIMES,
        ArrayList(snapshot.items.map { it.mimeType }),
    )
    outState.putBoolean(STATE_EXPORT_IN_FLIGHT, snapshot.hasInFlight)
}

internal fun readExportSnapshot(state: Bundle): ExportQueueSnapshot? {
    val paths = state.getStringArrayList(STATE_EXPORT_PATHS) ?: return null
    val names = state.getStringArrayList(STATE_EXPORT_NAMES) ?: return null
    val mimes = state.getStringArrayList(STATE_EXPORT_MIMES) ?: return null
    if (paths.size != names.size || paths.size != mimes.size) {
        return null
    }
    val items = paths.indices.map { index ->
        PendingExport(names[index], mimes[index], File(paths[index]))
    }
    return ExportQueueSnapshot(items, state.getBoolean(STATE_EXPORT_IN_FLIGHT))
}

internal fun persistExportPayload(cacheDir: File, content: String): File {
    val dir = File(cacheDir, "exports").apply { mkdirs() }
    return File(dir, "${java.util.UUID.randomUUID()}.export").apply {
        writeText(content, StandardCharsets.UTF_8)
    }
}

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

internal fun sanitizeExportFilename(filename: String): String {
    val name = filename.substringAfterLast('/').substringAfterLast('\\').trim()
    return name.takeIf { it.isNotEmpty() } ?: "export"
}

/**
 * WebView's default (no WebChromeClient) file picker is camera/gallery.
 * Category import uses `<input type="file">` with no `accept`, so an untyped
 * input must open a generic document picker (aw-android#247).
 */
internal fun resolvedFileChooserMimeTypes(acceptTypes: Array<String>?): List<String> {
    val types = acceptTypes.orEmpty().filter { it.isNotBlank() && it != "*/*" }
    return types.ifEmpty { listOf("*/*") }
}

internal fun fileChooserIntentForAcceptTypes(acceptTypes: Array<String>?): Intent {
    val types = resolvedFileChooserMimeTypes(acceptTypes)
    return Intent(Intent.ACTION_GET_CONTENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        type = types.first()
        if (types.size > 1) {
            putExtra(Intent.EXTRA_MIME_TYPES, types.toTypedArray())
        }
    }
}

internal fun parseFileChooserResult(resultCode: Int, data: Intent?): Array<Uri>? {
    if (resultCode != Activity.RESULT_OK || data == null) {
        return null
    }
    val clip = data.clipData
    if (clip != null && clip.itemCount > 0) {
        return Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
    }
    return data.data?.let { arrayOf(it) }
}

internal fun inferExportMimeType(filename: String, explicit: String? = null): String {
    val given = explicit?.trim().orEmpty()
    if (given.isNotEmpty() && given != "application/octet-stream") {
        return given
    }
    return when {
        filename.endsWith(".csv", ignoreCase = true) -> "text/csv"
        filename.endsWith(".json", ignoreCase = true) -> "application/json"
        else -> given.ifEmpty { "application/octet-stream" }
    }
}

internal fun blobExportRecoveryJs(blobUrl: String, filename: String): String {
    return "window.__awAndroidSendBlob && window.__awAndroidSendBlob(" +
        "${JSONObject.quote(blobUrl)}, ${JSONObject.quote(filename)});"
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
    private var webView: WebView? = null
    private val exportQueue = ExportSaveQueue()
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        onExportDocumentCreated(uri)
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = filePathCallback
        filePathCallback = null
        callback?.onReceiveValue(parseFileChooserResult(result.resultCode, result.data))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { state ->
            readExportSnapshot(state)?.let { exportQueue.restore(it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        writeExportSnapshot(outState, exportQueue.snapshot())
    }

    override fun onStart() {
        super.onStart()
        // Recreating during an async write restores waiting items with no in-flight
        // picker. Resume them here; if a picker is still open, inFlight is set.
        if (exportQueue.inFlight == null) {
            launchNextExportPicker()
        }
    }

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
        webView = myWebView

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

            override fun onPageFinished(view: WebView?, url: String?) {
                view?.evaluateJavascript(ANDROID_EXPORT_HOOK_JS, null)
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
        myWebView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                this@WebUIFragment.filePathCallback?.onReceiveValue(null)
                this@WebUIFragment.filePathCallback = filePathCallback
                val intent = fileChooserIntentForAcceptTypes(fileChooserParams?.acceptTypes)
                return try {
                    fileChooserLauncher.launch(intent)
                    true
                } catch (e: ActivityNotFoundException) {
                    Log.e(TAG, "No activity to handle file chooser", e)
                    this@WebUIFragment.filePathCallback?.onReceiveValue(null)
                    this@WebUIFragment.filePathCallback = null
                    false
                }
            }
        }

        myWebView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            handleWebViewDownload(url, contentDisposition, mimeType)
        }

        myWebView.settings.javaScriptEnabled = true
        myWebView.settings.domStorageEnabled = true
        myWebView.addJavascriptInterface(WebAppInterface(::queueExport), "Android")
        arguments?.let {
            it.getString(ARG_URL)?.let { it1 -> myWebView.loadUrl(it1) }
        }

        return view
    }

    override fun onDestroyView() {
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView = null
        super.onDestroyView()
    }

    private fun handleWebViewDownload(url: String?, contentDisposition: String?, mimeType: String?) {
        if (url.isNullOrBlank()) {
            return
        }
        Log.i(TAG, "DownloadListener: $url")
        val suggestedName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        when {
            url.startsWith("blob:") -> {
                webView?.evaluateJavascript(blobExportRecoveryJs(url, suggestedName), null)
            }
            isEmbeddedActivityWatchUrl(url) -> {
                downloadEmbeddedExport(url, suggestedName, mimeType)
            }
            else -> {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                } catch (e: ActivityNotFoundException) {
                    Log.e(TAG, "No app to open $url", e)
                    showExportToast(getString(R.string.export_save_failed), long = true)
                }
            }
        }
    }

    private fun downloadEmbeddedExport(url: String, filename: String, mimeType: String?) {
        val token = context?.let { ensureDashboardApiKey(it) }.orEmpty()
        thread(name = "aw-export-fetch") {
            val result = runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                    if (token.isNotEmpty()) {
                        setRequestProperty("Authorization", "Bearer $token")
                    }
                }
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        error("export HTTP $code")
                    }
                    connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }
            view?.post {
                result.fold(
                    onSuccess = { body ->
                        queueExport(body, filename, inferExportMimeType(filename, mimeType))
                    },
                    onFailure = { error ->
                        Log.e(TAG, "Failed to fetch export from $url", error)
                        showExportToast(getString(R.string.export_save_failed), long = true)
                    },
                )
            }
        }
    }

    private fun queueExport(content: String, filename: String, mimeType: String) {
        val safeName = sanitizeExportFilename(filename)
        val resolvedMime = inferExportMimeType(safeName, mimeType)
        Log.i(TAG, "Export save requested: $safeName ($resolvedMime, ${content.length} chars)")
        val cacheDir = context?.applicationContext?.cacheDir ?: return
        val pending = try {
            PendingExport(safeName, resolvedMime, persistExportPayload(cacheDir, content))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist export payload", e)
            val notify = {
                showExportToast(getString(R.string.export_save_failed), long = true)
            }
            view?.post(notify) ?: if (isAdded) requireActivity().runOnUiThread(notify) else Unit
            return
        }
        val enqueue = {
            if (isAdded) {
                exportQueue.enqueue(pending)
                launchNextExportPicker()
            } else {
                pending.deleteCache()
            }
        }
        val view = view
        if (view != null) {
            view.post(enqueue)
        } else if (isAdded) {
            requireActivity().runOnUiThread(enqueue)
        } else {
            pending.deleteCache()
        }
    }

    private fun launchNextExportPicker() {
        if (!isAdded) return
        val next = exportQueue.beginNext() ?: return
        try {
            createDocumentLauncher.launch(next.filename)
        } catch (e: Exception) {
            Log.e(TAG, "CreateDocument failed, falling back to share sheet", e)
            exportQueue.completeInFlight()
            shareExport(next)
            next.deleteCache()
            launchNextExportPicker()
        }
    }

    private fun onExportDocumentCreated(uri: Uri?) {
        val pending = exportQueue.completeInFlight()
        if (uri != null && pending != null) {
            val appContext = context?.applicationContext
            if (appContext == null) {
                pending.deleteCache()
                launchNextExportPicker()
                return
            }
            thread(name = "aw-export-write") {
                val ok = writeExport(appContext, uri, pending.cacheFile)
                pending.deleteCache()
                val activity = activity ?: return@thread
                activity.runOnUiThread {
                    if (ok) {
                        showExportToast(getString(R.string.export_saved, pending.filename))
                    } else {
                        showExportToast(getString(R.string.export_save_failed), long = true)
                    }
                    launchNextExportPicker()
                }
            }
            return
        }
        pending?.deleteCache()
        launchNextExportPicker()
    }

    private fun shareExport(pending: PendingExport) {
        val ctx = context ?: return
        val externalDir = ctx.getExternalFilesDir(null) ?: run {
            Log.e(TAG, "External files directory unavailable")
            showExportToast(getString(R.string.export_save_failed), long = true)
            return
        }
        val file = File(externalDir, pending.filename)
        try {
            file.writeText(pending.readContent())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write export file: ${e.message}")
            showExportToast(getString(R.string.export_save_failed), long = true)
            return
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = pending.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, pending.filename)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, pending.filename))
        } catch (e: ActivityNotFoundException) {
            Log.e(TAG, "No app to share ${pending.mimeType}", e)
            showExportToast(getString(R.string.export_save_failed), long = true)
        }
    }

    private fun showExportToast(message: String, long: Boolean = false) {
        val ctx = context ?: return
        Toast.makeText(ctx, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
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

internal fun writeExport(context: Context, uri: Uri, source: File): Boolean {
    return try {
        context.contentResolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { input -> input.copyTo(out) }
            out.flush()
        } != null
    } catch (e: Exception) {
        Log.e(TAG, "Failed to write export", e)
        false
    }
}

class WebAppInterface(
    private val onExport: (content: String, filename: String, mimeType: String) -> Unit,
) {
    private val lock = Any()
    private val buffer = StringBuilder()
    private var filename: String = "export"
    private var mimeType: String = "application/json"

    @JavascriptInterface
    fun downloadCSV(csv: String, filename: String) {
        onExport(csv, filename, "text/csv")
    }

    @JavascriptInterface
    fun downloadJSON(json: String, filename: String) {
        onExport(json, filename, "application/json")
    }

    @JavascriptInterface
    fun beginExport(filename: String, mimeType: String) {
        synchronized(lock) {
            buffer.setLength(0)
            this.filename = sanitizeExportFilename(filename)
            this.mimeType = inferExportMimeType(this.filename, mimeType)
        }
    }

    @JavascriptInterface
    fun appendExport(chunk: String) {
        synchronized(lock) {
            buffer.append(chunk)
        }
    }

    @JavascriptInterface
    fun finishExport() {
        val content: String
        val name: String
        val mime: String
        synchronized(lock) {
            content = buffer.toString()
            name = filename
            mime = mimeType
            buffer.setLength(0)
        }
        onExport(content, name, mime)
    }
}
