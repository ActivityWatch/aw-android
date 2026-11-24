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
            // A peer can populate the retained directory without a later cleanup
            // deleting it. The rejected mapping is still reported as a failure.
            File(rejected, "peer.db").writeText("peer data")
            assertEquals("peer data", File(rejected, "peer.db").readText())
        } finally {
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
