// DocumentFile's constructor is package-private; the test provider lives here.
package androidx.documentfile.provider

import android.net.Uri
import java.io.File
import net.activitywatch.android.SafMirrorStamp
import net.activitywatch.android.SyncInterface
import net.activitywatch.android.mirroredHostnameName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SafDirectoryIdentityTest {
    @Test
    fun cleanupUsesListedNameOnlyWhenItIsTheSameLocalDirectory() {
        assertEquals("PIXEL8", mirroredHostnameName(setOf("PIXEL8"), "pixel8") { it == "PIXEL8" })
        assertEquals(null, mirroredHostnameName(setOf("PIXEL8"), "pixel8") { false })
        assertEquals("pixel8", mirroredHostnameName(setOf("PIXEL8", "pixel8"), "pixel8") { true })
        assertEquals(null, mirroredHostnameName(setOf("PIXEL8", "Pixel8"), "pixel8") { true })
    }

    @Test
    fun rejectedDirectoryCannotDeleteFilesAddedByAnotherSyncTool() {
        val root = kotlin.io.path.createTempDirectory("aw-saf-peer-write").toFile()
        try {
            val source = File(root, "source").apply { mkdir() }
            assertTrue(File(source, "pixel8").mkdir())
            val destination = File(root, "destination").apply { mkdir() }
            val rejected = File(destination, "pixel8 (1)").apply { mkdir() }
            // Simulate a provider whose delete recursively removes content, including
            // a peer file arriving after an empty listing but before deletion.
            val racedDirectory = object : LocalDocument(rejected) {
                override fun delete(): Boolean {
                    File(rejected, "peer.db").writeText("peer data")
                    return rejected.deleteRecursively()
                }
            }
            val provider = object : LocalDocument(destination) {
                override fun listFiles(): Array<DocumentFile> = emptyArray()
                override fun createDirectory(name: String): DocumentFile = racedDirectory
            }
            // Bypass JNI initialization: this exercises only the Kotlin mirror loop.
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val sync = unsafeClass.getMethod("allocateInstance", Class::class.java)
                .invoke(unsafeField.get(null), SyncInterface::class.java) as SyncInterface
            val mirror = SyncInterface::class.java.getDeclaredMethod(
                "mirrorDirectory", File::class.java, DocumentFile::class.java,
                String::class.java, Map::class.java, Map::class.java, IntArray::class.java,
            ).apply { isAccessible = true }
            val counts = intArrayOf(0, 0)
            val result = mirror.invoke(
                sync, source, provider, "", emptyMap<String, SafMirrorStamp>(),
                mutableMapOf<String, SafMirrorStamp>(), counts,
            ) as Map<*, *>
            assertTrue(result.isEmpty())
            assertEquals(1, counts[1])
            assertTrue("Rejected SAF directories must not be deleted", rejected.isDirectory)
            // Exercise another mirror attempt after a peer populates the rejected
            // directory. Rejection must still leave the peer's content untouched.
            File(rejected, "peer.db").writeText("peer data")
            val retry = mirror.invoke(
                sync, source, provider, "", emptyMap<String, SafMirrorStamp>(),
                mutableMapOf<String, SafMirrorStamp>(), counts,
            ) as Map<*, *>
            assertTrue(retry.isEmpty())
            assertEquals(2, counts[1])
            assertEquals("peer data", File(rejected, "peer.db").readText())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cleanupUsesTheMirroredDeviceDirectoryAndPreservesPeers() {
        val root = kotlin.io.path.createTempDirectory("aw-saf-device-case").toFile()
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val deviceIdFile = File(context.filesDir, "device_id")
        try {
            android.provider.Settings.Global.putString(context.contentResolver, "device_name", "pixel8")
            deviceIdFile.writeText("dev-1")
            val source = File(root, "source").apply { mkdir() }
            val localDevice = File(source, "pixel8/dev-1").apply { mkdirs() }
            val current = File(localDevice, "sync.db").apply { writeText("current database") }
            val destination = File(root, "destination").apply { mkdir() }
            val safHostname = File(destination, "pixel8").apply { mkdir() }
            val safDevice = File(safHostname, "DEV-1").apply { mkdir() }
            val mirrored = File(safDevice, "sync.db").apply { writeText(current.readText()) }
            File(safDevice, "test.db").writeText("obsolete database")
            File(safDevice, "test.db-wal").writeText("obsolete WAL")
            File(safDevice, "notes.txt").writeText("keep notes")
            val peer = File(safHostname, "peer-device").apply { mkdir() }
            File(peer, "test.db").writeText("peer database")
            // A trusted manifest skips byte copying, exercising the actual directory
            // matching and cleanup without needing a ContentProvider for file I/O.
            val stamp = SafMirrorStamp(current.length(), current.lastModified(), mirrored.length(), mirrored.lastModified())
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val sync = unsafeClass.getMethod("allocateInstance", Class::class.java)
                .invoke(unsafeField.get(null), SyncInterface::class.java) as SyncInterface
            for ((name, value) in mapOf("appContext" to context, "syncDir" to source.absolutePath)) {
                SyncInterface::class.java.getDeclaredField(name).apply { isAccessible = true }.set(sync, value)
            }
            val mirror = SyncInterface::class.java.getDeclaredMethod(
                "mirrorDirectory", File::class.java, DocumentFile::class.java,
                String::class.java, Map::class.java, Map::class.java, IntArray::class.java,
            ).apply { isAccessible = true }
            val counts = intArrayOf(0, 0, 0)
            val result = mirror.invoke(
                sync, source, LocalDocument(destination), "", mapOf("pixel8/dev-1/sync.db" to stamp),
                mutableMapOf<String, SafMirrorStamp>(), counts,
            ) as Map<*, *>
            assertEquals(0, counts[1])
            assertEquals(1, counts[2])
            SyncInterface::class.java.getDeclaredMethod("deleteStaleSafDatabaseFiles", Map::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(sync, result, "pixel8")
            assertTrue("Obsolete database must be removed from the actual mirrored device", !File(safDevice, "test.db").exists())
            assertTrue(!File(safDevice, "test.db-wal").exists())
            assertEquals("current database", mirrored.readText())
            assertEquals("keep notes", File(safDevice, "notes.txt").readText())
            assertEquals("peer database", File(peer, "test.db").readText())
        } finally {
            deviceIdFile.delete()
            android.provider.Settings.Global.putString(context.contentResolver, "device_name", null)
            root.deleteRecursively()
        }
    }

    private open class LocalDocument(private val file: File) : DocumentFile(null) {
        override fun getUri(): Uri = Uri.fromFile(file)
        override fun getName(): String = file.name
        override fun getType(): String? = null
        override fun isDirectory(): Boolean = file.isDirectory
        override fun isFile(): Boolean = file.isFile
        override fun isVirtual(): Boolean = false
        override fun lastModified(): Long = file.lastModified()
        override fun length(): Long = file.length()
        override fun canRead(): Boolean = true
        override fun canWrite(): Boolean = true
        override fun exists(): Boolean = file.exists()
        override fun delete(): Boolean = file.deleteRecursively()
        override fun listFiles(): Array<DocumentFile> =
            file.listFiles()!!.map { LocalDocument(it) }.toTypedArray()
        override fun createDirectory(name: String): DocumentFile? = null
        override fun createFile(mimeType: String, displayName: String): DocumentFile? = null
        override fun renameTo(displayName: String): Boolean = false
    }
}
