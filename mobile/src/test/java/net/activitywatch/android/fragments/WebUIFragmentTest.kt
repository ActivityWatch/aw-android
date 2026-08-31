package net.activitywatch.android.fragments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class WebUIFragmentTest {
    @Test
    fun `untyped file input uses a generic mime type`() {
        assertEquals(listOf("*/*"), resolvedFileChooserMimeTypes(null))
        assertEquals(listOf("*/*"), resolvedFileChooserMimeTypes(arrayOf()))
        assertEquals(listOf("*/*"), resolvedFileChooserMimeTypes(arrayOf("", "*/*")))
    }

    @Test
    fun `explicit file input accept types are preserved`() {
        assertEquals(
            listOf("application/json"),
            resolvedFileChooserMimeTypes(arrayOf("application/json")),
        )
        assertEquals(
            listOf("text/csv", "application/json"),
            resolvedFileChooserMimeTypes(arrayOf("text/csv", "application/json")),
        )
    }

    @Test
    fun `treats local embedded server hosts as internal`() {
        // http variants
        assertTrue(isEmbeddedActivityWatchUrl("http://127.0.0.1:5600/#/settings/"))
        assertTrue(isEmbeddedActivityWatchUrl("http://localhost:5600/#/settings/"))
        assertTrue(isEmbeddedActivityWatchUrl("http://[::1]:5600/#/settings/"))
        // https variants (function explicitly allows both schemes)
        assertTrue(isEmbeddedActivityWatchUrl("https://127.0.0.1:5600/"))
        assertTrue(isEmbeddedActivityWatchUrl("https://localhost:5600/"))
        assertTrue(isEmbeddedActivityWatchUrl("https://[::1]:5600/"))
        // no-port case
        assertTrue(isEmbeddedActivityWatchUrl("http://localhost/"))
    }

    @Test
    fun `treats non-loopback hosts as external`() {
        assertFalse(isEmbeddedActivityWatchUrl("https://activitywatch.net"))
        assertFalse(isEmbeddedActivityWatchUrl("http://192.168.1.10:5600"))
        assertFalse(isEmbeddedActivityWatchUrl("not a url"))
    }

    @Test
    fun `sanitizeExportFilename strips path components`() {
        assertEquals("aw-bucket-export.json", sanitizeExportFilename("../../aw-bucket-export.json"))
        assertEquals("events.csv", sanitizeExportFilename("C:\\temp\\events.csv"))
        assertEquals("export", sanitizeExportFilename("   "))
        assertEquals("export", sanitizeExportFilename(""))
        assertEquals("aw-bucket-export-foo.json", sanitizeExportFilename("aw-bucket-export-foo.json"))
    }

    @Test
    fun `inferExportMimeType prefers explicit type then filename`() {
        assertEquals("application/json", inferExportMimeType("export.bin", "application/json"))
        assertEquals("text/csv", inferExportMimeType("events.CSV", "application/octet-stream"))
        assertEquals("application/json", inferExportMimeType("bucket.json", null))
        assertEquals("application/octet-stream", inferExportMimeType("export", null))
    }

    @Test
    fun `export hook js intercepts blob downloads and chunks through the bridge`() {
        assertTrue(ANDROID_EXPORT_HOOK_JS.contains("URL.createObjectURL"))
        assertTrue(ANDROID_EXPORT_HOOK_JS.contains("URL.revokeObjectURL"))
        assertTrue(ANDROID_EXPORT_HOOK_JS.contains("delete blobs[url]"))
        assertTrue(ANDROID_EXPORT_HOOK_JS.contains("Android.beginExport"))
        assertTrue(ANDROID_EXPORT_HOOK_JS.contains("Android.appendExport"))
        assertTrue(ANDROID_EXPORT_HOOK_JS.contains("Android.finishExport"))
        assertTrue(ANDROID_EXPORT_HOOK_JS.contains("var CHUNK = $EXPORT_BRIDGE_CHUNK_SIZE;"))
        assertTrue(EXPORT_BRIDGE_CHUNK_SIZE < 1024 * 1024)
        assertTrue(ANDROID_EXPORT_HOOK_JS.contains("/\\.csv$/i"))
    }

    @Test
    fun `blob recovery js quotes hostile filenames`() {
        val js = blobExportRecoveryJs("blob:http://127.0.0.1/abc", "aw-export\".js")
        assertTrue(js.startsWith("window.__awAndroidSendBlob && window.__awAndroidSendBlob("))
        assertTrue(js.contains("blob:http://127.0.0.1/abc"))
        assertTrue(js.contains("\\u0022") || js.contains("\\\""))
        assertFalse(js.contains("aw-export\".js"))
    }

    @Test
    fun `WebAppInterface reassembles chunked exports`() {
        var received: Triple<String, String, String>? = null
        val bridge = WebAppInterface { content, filename, mimeType ->
            received = Triple(content, filename, mimeType)
        }

        bridge.beginExport("../aw-bucket-export.json", "application/json")
        bridge.appendExport("{\"buckets\":")
        bridge.appendExport("[1,2,3]}")
        bridge.finishExport()

        assertEquals(Triple("{\"buckets\":[1,2,3]}", "aw-bucket-export.json", "application/json"), received)
    }

    @Test
    fun `WebAppInterface download helpers keep explicit mime types`() {
        val received = mutableListOf<Triple<String, String, String>>()
        val bridge = WebAppInterface { content, filename, mimeType ->
            received.add(Triple(content, filename, mimeType))
        }

        bridge.downloadJSON("{}", "data.json")
        bridge.downloadCSV("a,b", "data.csv")

        assertEquals(
            listOf(
                Triple("{}", "data.json", "application/json"),
                Triple("a,b", "data.csv", "text/csv"),
            ),
            received,
        )
    }

    @Test
    fun `export queue keeps the first picker payload when a second export arrives`() {
        val dir = createTempDir()
        val first = cachedExport(dir, "first.json", "one")
        val second = cachedExport(dir, "second.json", "two")
        val queue = ExportSaveQueue()

        queue.enqueue(first)
        assertEquals(first, queue.beginNext())
        queue.enqueue(second)
        assertEquals(null, queue.beginNext())
        assertEquals(first, queue.inFlight)

        assertEquals(first, queue.completeInFlight())
        assertEquals(second, queue.beginNext())
        assertEquals(second, queue.completeInFlight())
        assertEquals(null, queue.beginNext())
        assertTrue(queue.isEmpty)
    }

    @Test
    fun `cancelling the first picker still offers the next queued export`() {
        val dir = createTempDir()
        val first = cachedExport(dir, "first.json", "one")
        val second = cachedExport(dir, "second.json", "two")
        val queue = ExportSaveQueue()

        queue.enqueue(first)
        queue.beginNext()
        queue.enqueue(second)
        queue.completeInFlight()

        assertEquals(second, queue.beginNext())
    }

    @Test
    fun `queue snapshot restore keeps the in-flight export first`() {
        val dir = createTempDir()
        val first = cachedExport(dir, "first.json", "one")
        val second = cachedExport(dir, "second.json", "two")
        val original = ExportSaveQueue()
        original.enqueue(first)
        original.beginNext()
        original.enqueue(second)

        val restored = ExportSaveQueue()
        restored.restore(original.snapshot())

        assertEquals(first, restored.inFlight)
        assertEquals(first, restored.completeInFlight())
        assertEquals(second, restored.beginNext())
        assertEquals("one", first.readContent())
        assertEquals("two", second.readContent())
    }

    @Test
    fun `restoring after the in-flight write started can begin the next waiting export`() {
        val dir = createTempDir()
        val first = cachedExport(dir, "first.json", "one")
        val second = cachedExport(dir, "second.json", "two")
        val original = ExportSaveQueue()
        original.enqueue(first)
        original.beginNext()
        original.enqueue(second)
        original.completeInFlight()

        val restored = ExportSaveQueue()
        restored.restore(original.snapshot())

        assertNull(restored.inFlight)
        assertEquals(second, restored.beginNext())
    }

    @Test
    fun `restore drops a missing in-flight cache instead of delivering the next export`() {
        val dir = createTempDir()
        val first = cachedExport(dir, "first.json", "one")
        val second = cachedExport(dir, "second.json", "two")
        val original = ExportSaveQueue()
        original.enqueue(first)
        original.beginNext()
        original.enqueue(second)
        first.cacheFile.delete()

        val restored = ExportSaveQueue()
        restored.restore(original.snapshot())

        assertNull(restored.inFlight)
        assertEquals(second, restored.beginNext())
    }

    @Test
    fun `persistExportPayload writes content that can be read back`() {
        val file = persistExportPayload(createTempDir(), "{\"ok\":true}")
        assertTrue(file.isFile)
        assertEquals("{\"ok\":true}", file.readText())
    }

    private fun cachedExport(dir: File, name: String, content: String): PendingExport {
        return PendingExport(name, "application/json", File(dir, name).also { it.writeText(content) })
    }
}
