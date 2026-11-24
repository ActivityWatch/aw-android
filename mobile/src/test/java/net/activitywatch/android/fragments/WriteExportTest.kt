package net.activitywatch.android.fragments

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Some providers (MediaProvider/FUSE-backed Downloads) do not truncate on "w",
 * so a shorter re-export over an existing file kept the old file's tail.
 * The export must ask for "wt", as the SAF mirror already does.
 */
@RunWith(RobolectricTestRunner::class)
class WriteExportTest {
    class RecordingProvider : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            modes += mode
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
        }
        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ) = 0

        companion object {
            val modes = mutableListOf<String>()
            lateinit var target: File
        }
    }

    @Test
    fun `export opens the destination for truncating write`() {
        val context = RuntimeEnvironment.getApplication()
        Robolectric.setupContentProvider(RecordingProvider::class.java, AUTHORITY)
        RecordingProvider.modes.clear()
        RecordingProvider.target = File(context.cacheDir, "dest.json").apply {
            writeText("[1,2,3,4,5,6,7,8,9]")
        }
        val source = File(context.cacheDir, "src.json").apply { writeText("[1]") }

        assertTrue(writeExport(context, Uri.parse("content://$AUTHORITY/export.json"), source))

        assertEquals(listOf("wt"), RecordingProvider.modes)
        assertEquals("[1]", RecordingProvider.target.readText())
    }

    private companion object {
        const val AUTHORITY = "net.activitywatch.android.test.export"
    }
}
