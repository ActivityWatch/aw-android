package net.activitywatch.android

import androidx.documentfile.provider.DocumentFile
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun rejectedFreshEmptyDirectoryIsRemovedOnEveryRetry() {
        val root = kotlin.io.path.createTempDirectory("aw-saf-retry").toFile()
        try {
            repeat(3) {
                val directory = File(root, "pixel8 ($it)")
                assertTrue(directory.mkdir())
                discardRejectedSafDirectory(
                    DocumentFile.fromFile(directory), created = true, knownUris = emptySet(),
                )
                assertFalse(directory.exists())
            }
            assertTrue(root.listFiles()!!.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectedAliasExistingOrPopulatedDirectoryIsNeverRemoved() {
        val root = kotlin.io.path.createTempDirectory("aw-saf-alias").toFile()
        try {
            val alias = DocumentFile.fromFile(File(root, "PIXEL8").apply { mkdir() })
            discardRejectedSafDirectory(alias, created = true, knownUris = setOf(alias.uri))
            assertTrue(alias.exists())

            val existing = DocumentFile.fromFile(File(root, "existing").apply { mkdir() })
            discardRejectedSafDirectory(existing, created = false, knownUris = emptySet())
            assertTrue(existing.exists())

            val populatedFile = File(root, "new").apply { mkdir() }
            File(populatedFile, "peer.db").writeText("peer")
            val populated = DocumentFile.fromFile(populatedFile)
            discardRejectedSafDirectory(populated, created = true, knownUris = emptySet())
            assertTrue(populated.exists())
            assertEquals("peer", File(populatedFile, "peer.db").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
