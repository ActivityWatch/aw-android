package net.activitywatch.android.fragments

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import net.activitywatch.android.ANDROID_THEME_HOOK_JS
import net.activitywatch.android.R
import net.activitywatch.android.baseURL
import net.activitywatch.android.ensureDashboardApiKey
import net.activitywatch.android.initialWebViewBackgroundRes
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

private const val TAG = "WebUI"

private const val ARG_URL = "url"

// Reload backoff while the local server is still starting (aw-android#261).
internal const val INITIAL_RELOAD_DELAY_MS = 250L
internal const val MAX_RELOAD_DELAY_MS = 5_000L

// Bound on waiting for the page to report its theme stylesheet has settled
// (see ANDROID_THEME_HOOK_JS). The dashboard reveals as soon as it reports; this
// bound only reveals a document that can never report (no JS bridge).
internal const val THEME_SETTLE_FAILSAFE_MS = 3_000L

// A document that can still report its theme is waited for, not revealed: doing
// so would paint its light base styles before dark.css applies (aw-android#300).
// Each extension adds one more THEME_SETTLE_FAILSAFE_MS. After the polling cap,
// keep a pending dashboard hidden until its own settle report arrives.
internal const val THEME_SETTLE_MAX_EXTENSIONS = 4

@VisibleForTesting
internal var themeSettleFailsafeMs = THEME_SETTLE_FAILSAFE_MS

internal enum class ThemeFailsafeAction { WAIT, AWAIT_REPORT, REVEAL }

/**
 * Decides what the theme-settle failsafe does when one bound elapses.
 *
 * Only a document that can never report (`no-bridge`, `not-dashboard`, or an
 * unreadable hook state) is revealed. A `pending` dashboard is still applying
 * its theme, so revealing it would reintroduce the white frame this failsafe
 * exists to avoid. Exhausting [extensionsLeft] stops polling, not waiting for
 * the page's own settle report.
 */
internal fun themeFailsafeAction(state: String?, extensionsLeft: Int): ThemeFailsafeAction = when {
    state != "pending" -> ThemeFailsafeAction.REVEAL
    extensionsLeft > 0 -> ThemeFailsafeAction.WAIT
    else -> ThemeFailsafeAction.AWAIT_REPORT
}

private const val THEME_STATE_JS =
    "(window.__awAndroidThemeState && window.__awAndroidThemeState()) || 'unknown'"

internal fun nextReloadDelayMs(current: Long): Long = (current * 2).coerceAtMost(MAX_RELOAD_DELAY_MS)

/**
 * Decides when a dashboard WebView error should queue a full-page retry.
 *
 * WebView calls [android.webkit.WebViewClient.onPageFinished] after a failed
 * main-frame load as well as a successful one. Cancelling the pending retry
 * on every finish would drop the startup backoff (aw-android#261). Only a
 * successful finish cancels; subresource errors never schedule a reload.
 */
internal class DashboardReloadPolicy {
    var delayMs: Long = INITIAL_RELOAD_DELAY_MS
        private set
    var currentLoadFailed: Boolean = false
        private set

    fun onPageStarted() {
        currentLoadFailed = false
    }

    /** Delay to schedule, or null if this error must not reload the page. */
    fun onReceivedError(isForMainFrame: Boolean): Long? {
        if (!isForMainFrame) {
            return null
        }
        currentLoadFailed = true
        val delay = delayMs
        delayMs = nextReloadDelayMs(delayMs)
        return delay
    }

    /** True when a pending retry should be dropped because the page loaded. */
    fun onPageFinished(): Boolean {
        if (currentLoadFailed) {
            return false
        }
        delayMs = INITIAL_RELOAD_DELAY_MS
        return true
    }
}

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

  // JSON bucket exports go through axios today. Abort that XHR and stream
  // natively so a 30s timeout / JSON.parse of 500k events cannot kill the save.
  var xhr = XMLHttpRequest.prototype;
  var origOpen = xhr.open;
  var origSend = xhr.send;
  xhr.open = function (method, url) {
    this.__awExportUrl = typeof url === 'string' ? url : '';
    return origOpen.apply(this, arguments);
  };
  xhr.send = function () {
    var url = this.__awExportUrl || '';
    var match = url.match(/\/0\/(?:buckets\/([^/?#]+)\/)?export(?:[?#]|$)/);
    if (match && typeof Android !== 'undefined' && Android.exportFromUrl) {
      var filename = match[1]
        ? ('aw-bucket-export-' + decodeURIComponent(match[1]) + '.json')
        : 'aw-bucket-export.json';
      Android.exportFromUrl(url, filename);
      this.abort();
      return;
    }
    return origSend.apply(this, arguments);
  };
})();
""".trimIndent()

internal data class PendingExport(
    val filename: String,
    val mimeType: String,
    val cacheFile: File,
) {
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
    return persistExportStream(cacheDir, content.byteInputStream(StandardCharsets.UTF_8))
}

internal const val SHARED_EXPORTS_DIR = "shared-exports"

// Shared copies are only needed until the receiving app has read them.
internal const val SHARED_EXPORT_MAX_AGE_MS = 24 * 60 * 60 * 1000L

/**
 * Moves [pending]'s cache file to `<sharedRoot>/<unique>/<filename>` so it can be shared
 * under its real name, and returns it (null if the move failed). Each share gets its own
 * directory so exports with the same filename never overwrite each other. Directories
 * older than [SHARED_EXPORT_MAX_AGE_MS] are removed first.
 */
internal fun moveExportForSharing(
    sharedRoot: File,
    pending: PendingExport,
    now: Long = System.currentTimeMillis(),
): File? {
    sharedRoot.listFiles()?.forEach { dir ->
        if (now - dir.lastModified() > SHARED_EXPORT_MAX_AGE_MS) dir.deleteRecursively()
    }
    val dir = File(sharedRoot, java.util.UUID.randomUUID().toString())
    if (!dir.mkdirs()) return null
    val target = File(dir, pending.filename)
    return target.takeIf { pending.cacheFile.renameTo(it) }
}

internal fun persistExportStream(cacheDir: File, input: java.io.InputStream): File {
    val dir = File(cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, "${java.util.UUID.randomUUID()}.export")
    try {
        file.outputStream().use { output -> input.copyTo(output) }
    } catch (e: Exception) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Failed to delete incomplete export cache ${file.name}")
        }
        throw e
    }
    return file
}

internal fun resolveEmbeddedExportUrl(url: String, baseUrl: String = "http://127.0.0.1:5600/"): String? {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) {
        return null
    }
    return try {
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            URI(baseUrl).resolve(trimmed).toString()
        }
    } catch (_: Exception) {
        null
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
    private val reloadHandler = Handler(Looper.getMainLooper())
    private val reloadPolicy = DashboardReloadPolicy()
    private val reloadRunnable = Runnable {
        val target = webView ?: return@Runnable
        arguments?.getString(ARG_URL)?.let { target.loadUrl(it) }
    }
    private val exportQueue = ExportSaveQueue()
    // Each main-frame document starts hidden and is revealed only once it has
    // both finished loading and applied its theme (aw-android#300).
    private var navigationId = 0L
    private var finishedNavigation = -1L
    private var themeSettledNavigation = -1L
    private var themeFailsafeNavigation = -1L
    private var themeFailsafeExtensionsLeft = 0
    private val themeSettleFailsafe = Runnable { runThemeSettleFailsafe() }
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val webMenuState = WebMenuState()
    private var nativeBridgeReply: JavaScriptReplyProxy? = null

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
        // Prevent white flash: set the background before the first frame loads.
        // The JS theme hook corrects it after onPageFinished reads localStorage.theme.
        myWebView.setBackgroundColor(
            ContextCompat.getColor(view.context, initialWebViewBackgroundRes(resources.configuration)),
        )
        // The dashboard paints its light base styles before it has loaded settings
        // and applied dark.css. Keep it hidden until revealWhenReady().
        myWebView.visibility = View.INVISIBLE

        class MyWebViewClient : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                navigationId++
                view?.visibility = View.INVISIBLE
                reloadHandler.removeCallbacks(themeSettleFailsafe)
                reloadPolicy.onPageStarted()
                webMenuState.onPageStarted()
                nativeBridgeReply = null
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                val delay = reloadPolicy.onReceivedError(request.isForMainFrame) ?: return
                // The local server may still be starting; retry with backoff.
                // This used to Thread.sleep() on the main thread and reload
                // immediately, which turned a slow server start into a tight
                // reload loop on the UI thread (aw-android#261).
                // Chromium paints its own (potentially white) error document even
                // when the WebView background is dark. Keep it out of the view
                // hierarchy's drawing until a successful retry replaces it.
                view.visibility = View.INVISIBLE
                Log.e(TAG, "WebView received error: ${error.description}")
                scheduleReload(delay)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // Error-page finishes still call onPageFinished. Only a
                // successful load should drop the pending startup retry.
                if (!reloadPolicy.onPageFinished()) return
                reloadHandler.removeCallbacks(reloadRunnable)
                finishedNavigation = navigationId
                view?.evaluateJavascript(ANDROID_EXPORT_HOOK_JS, null)
                // Tag the document so a settle report from an older one is ignored.
                view?.evaluateJavascript("window.__awAndroidNavigation = '$navigationId';", null)
                view?.evaluateJavascript(ANDROID_THEME_HOOK_JS, null)
                scheduleThemeSettleFailsafe(navigationId, THEME_SETTLE_MAX_EXTENSIONS)
                revealWhenReady()
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
        myWebView.addJavascriptInterface(
            WebAppInterface(::queueExport, ::onColorSchemeReported, ::onExportFromUrl) { navigation ->
                navigation.toLongOrNull()?.let { postToUi { onThemeSettled(it) } }
            },
            "Android",
        )
        installNativeBridge(myWebView)
        arguments?.let {
            it.getString(ARG_URL)?.let { it1 -> myWebView.loadUrl(it1) }
        }

        return view
    }

    private fun onThemeSettled(navigation: Long) {
        if (navigation != navigationId) return
        themeSettledNavigation = navigation
        revealWhenReady()
    }

    /**
     * Bounded fallback for documents that can never report their theme.
     *
     * The page's own report always wins; this only reveals when the hook says
     * the document has no bridge. A dashboard that is still settling is waited
     * for rather than revealed, because at that point its body is still painted
     * with the light base styles (aw-android#300).
     */
    private fun scheduleThemeSettleFailsafe(navigation: Long, extensionsLeft: Int) {
        themeFailsafeNavigation = navigation
        themeFailsafeExtensionsLeft = extensionsLeft
        reloadHandler.removeCallbacks(themeSettleFailsafe)
        reloadHandler.postDelayed(themeSettleFailsafe, themeSettleFailsafeMs)
    }

    private fun runThemeSettleFailsafe() {
        val navigation = themeFailsafeNavigation
        val view = webView ?: return
        if (navigation != navigationId || finishedNavigation != navigation ||
            themeSettledNavigation == navigation || reloadPolicy.currentLoadFailed
        ) {
            return
        }
        view.evaluateJavascript(THEME_STATE_JS) { raw ->
            postToUi {
                if (navigation != navigationId || themeSettledNavigation == navigation) return@postToUi
                val state = raw?.trim()?.trim('"')
                when (themeFailsafeAction(state, themeFailsafeExtensionsLeft)) {
                    ThemeFailsafeAction.WAIT ->
                        scheduleThemeSettleFailsafe(navigation, themeFailsafeExtensionsLeft - 1)
                    // The hook's observer/load/error callbacks still report
                    // settlement after polling stops. Keep the WebView hidden.
                    ThemeFailsafeAction.AWAIT_REPORT -> Unit
                    ThemeFailsafeAction.REVEAL -> onThemeSettled(navigation)
                }
            }
        }
    }

    private fun revealWhenReady() {
        val view = webView ?: return
        val navigation = navigationId
        if (finishedNavigation != navigation || themeSettledNavigation != navigation ||
            reloadPolicy.currentLoadFailed
        ) {
            return
        }
        reloadHandler.removeCallbacks(themeSettleFailsafe)
        // A finished navigation can still have the old error frame in the
        // compositor. Reveal only after the replacement is drawable.
        view.postVisualStateCallback(navigation, object : WebView.VisualStateCallback() {
            override fun onComplete(requestId: Long) {
                if (view === webView && navigationId == requestId && !reloadPolicy.currentLoadFailed) {
                    view.visibility = View.VISIBLE
                }
            }
        })
    }

    private fun scheduleReload(delay: Long) {
        reloadHandler.removeCallbacks(reloadRunnable)
        reloadHandler.postDelayed(reloadRunnable, delay)
    }

    /**
     * Always run [action] on the main looper.
     *
     * JS-bridge callbacks and background fetch completions must not use
     * `view?.post`: a detached view drops the runnable, so a finished export
     * is never queued and the cache file leaks. The fragment Handler still
     * fires after the view is gone; callers then enqueue or delete.
     */
    private fun postToUi(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            reloadHandler.post(action)
        }
    }

    /**
     * Expose native-only menu actions to the embedded web UI (see NativeBridge.kt).
     * Without WebMessageListener support the page simply never sees the bridge,
     * and the native drawer remains the way to reach these actions.
     */
    private fun installNativeBridge(target: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            Log.i(TAG, "WebMessageListener unsupported; native menu bridge disabled")
            return
        }
        val originRule = nativeBridgeOriginRule(baseURL) ?: return
        WebViewCompat.addWebMessageListener(
            target,
            NATIVE_BRIDGE_JS_OBJECT,
            setOf(originRule),
        ) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            onNativeBridgeMessage(message, sourceOrigin.toString(), isMainFrame, replyProxy)
        }
    }

    // WebMessageListener callbacks run on the UI thread.
    private fun onNativeBridgeMessage(
        message: WebMessageCompat,
        sourceOrigin: String,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        if (!isTrustedNativeBridgeMessage(sourceOrigin, isMainFrame, baseURL)) {
            Log.w(TAG, "Ignoring native bridge message from untrusted frame: $sourceOrigin main=$isMainFrame")
            return
        }
        nativeBridgeReply = replyProxy
        when (val parsed = parseNativeBridgeMessage(message.data)) {
            NativeBridgeMessage.Hello -> replyProxy.postMessage(nativeCapabilitiesMessage())
            is NativeBridgeMessage.Action -> if (isAdded) listener?.onNativeAction(parsed.action)
            is NativeBridgeMessage.Menu -> webMenuState.onMenuMessage(parsed.open)
            null -> Log.w(TAG, "Ignoring malformed native bridge message")
        }
    }

    fun isWebMenuOpen(): Boolean = webMenuState.open

    fun closeWebMenu() {
        if (webMenuState.requestClose()) {
            nativeBridgeReply?.postMessage(nativeCloseMenuMessage())
        }
    }

    fun canGoBack(): Boolean = webView?.canGoBack() == true

    fun goBack() {
        webView?.goBack()
    }

    override fun onDestroyView() {
        reloadHandler.removeCallbacks(reloadRunnable)
        reloadHandler.removeCallbacks(themeSettleFailsafe)
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
        webView = null
        nativeBridgeReply = null
        webMenuState.onPageStarted()
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

    private fun onExportFromUrl(url: String, filename: String) {
        // @JavascriptInterface runs on the WebView bridge thread. webView.url
        // (and Toast) are UI-thread only.
        postToUi {
            if (!isAdded) return@postToUi
            val base = webView?.url ?: "http://127.0.0.1:5600/"
            val resolved = resolveEmbeddedExportUrl(url, base)
            if (resolved == null || !isEmbeddedActivityWatchUrl(resolved)) {
                Log.w(TAG, "Rejected export URL: $url")
                showExportToast(getString(R.string.export_save_failed), long = true)
                return@postToUi
            }
            downloadEmbeddedExport(resolved, filename, inferExportMimeType(filename, null))
        }
    }

    private fun downloadEmbeddedExport(url: String, filename: String, mimeType: String?) {
        val token = context?.let { ensureDashboardApiKey(it) }.orEmpty()
        val cacheDir = context?.applicationContext?.cacheDir ?: return
        val safeName = sanitizeExportFilename(filename)
        val resolvedMime = inferExportMimeType(safeName, mimeType)
        thread(name = "aw-export-fetch") {
            val result = runCatching {
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    // Streamed exports send headers immediately, then trickle
                    // events. Allow long gaps without holding the JSON in RAM.
                    readTimeout = 120_000
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
                    persistExportStream(cacheDir, connection.inputStream)
                } finally {
                    connection.disconnect()
                }
            }
            result.fold(
                onSuccess = { file ->
                    queueExportFile(file, safeName, resolvedMime)
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to fetch export from $url", error)
                    notifyExportFailed()
                },
            )
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
            notifyExportFailed()
            return
        }
        enqueuePending(pending)
    }

    private fun queueExportFile(file: File, filename: String, mimeType: String) {
        Log.i(TAG, "Export save requested: $filename ($mimeType, ${file.length()} bytes)")
        enqueuePending(PendingExport(filename, mimeType, file))
    }

    private fun notifyExportFailed() {
        postToUi {
            if (isAdded) {
                showExportToast(getString(R.string.export_save_failed), long = true)
            }
        }
    }

    private fun enqueuePending(pending: PendingExport) {
        postToUi {
            if (isAdded) {
                exportQueue.enqueue(pending)
                launchNextExportPicker()
            } else {
                pending.deleteCache()
            }
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

    // Opens the share sheet for an export when the system file picker is unavailable.
    // The cached export is moved, not copied, into a shareable location: a rename within
    // cacheDir is instant, so this stays synchronous on the UI thread. That avoids reading
    // large exports into memory (#304) and leaves no background work to race a second
    // export or be lost if the activity is recreated.
    private fun shareExport(pending: PendingExport) {
        val ctx = context ?: run {
            pending.deleteCache()
            return
        }
        val file = moveExportForSharing(File(ctx.cacheDir, SHARED_EXPORTS_DIR), pending)
        if (file == null) {
            Log.e(TAG, "Failed to prepare ${pending.filename} for sharing")
            pending.deleteCache()
            showExportToast(getString(R.string.export_save_failed), long = true)
            return
        }
        startShareChooser(ctx, file, pending)
    }

    private fun startShareChooser(ctx: Context, file: File, pending: PendingExport) {
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

    private fun onColorSchemeReported(scheme: String) {
        val dark = scheme.equals("dark", ignoreCase = true)
        val apply = {
            if (isAdded) {
                listener?.onWebUiColorSchemeChanged(dark)
                val ctx = context
                if (ctx != null) {
                    webView?.setBackgroundColor(
                        ContextCompat.getColor(
                            ctx,
                            if (dark) R.color.chrome_dark else R.color.chrome_light,
                        ),
                    )
                }
            }
        }
        view?.post(apply) ?: if (isAdded) requireActivity().runOnUiThread(apply) else Unit
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
        fun onWebUiColorSchemeChanged(dark: Boolean) {}
        fun onNativeAction(action: NativeAction) {}
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
    private val onColorScheme: (String) -> Unit = {},
    private val onExportUrl: (url: String, filename: String) -> Unit = { _, _ -> },
    private val onThemeSettled: (navigation: String) -> Unit = {},
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

    @JavascriptInterface
    fun reportColorScheme(scheme: String) {
        onColorScheme(scheme)
    }

    @JavascriptInterface
    fun reportThemeSettled(navigation: String) {
        onThemeSettled(navigation)
    }

    @JavascriptInterface
    fun exportFromUrl(url: String, filename: String) {
        onExportUrl(url, filename)
    }
}
