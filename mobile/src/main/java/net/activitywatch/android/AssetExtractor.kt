package net.activitywatch.android

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files.exists

object AssetExtractor {
    private const val TAG = "AssetExtractor"

    // TODO: Extract assets only if updated instead of using overwrite = true
    // From: https://stackoverflow.com/a/8475135/965332
    fun extractAssets(path: String, context: Context, overwrite: Boolean = true) {
        val filenames = context.assets.list(path)
        //Log.w(TAG, filenames.joinToString(", "))
        for(fn in filenames) {
            val destPath = context.cacheDir.path + File.separator + path + File.separator + fn
            val sourcePath = path + File.separator + fn

            val f = File(destPath)
            val dir = f.parentFile
            if(!dir.exists()) {
                dir.mkdirs()
            }
            // Check that it hasn't already been extracted, and that it's not a folder (ugly hack)
            if ((!f.exists() or overwrite) && f.path.substringAfterLast("/").contains(".")) {
                Log.w(TAG, "$sourcePath -> $destPath")
                try {
                    val asset = context.assets.open(sourcePath)
                    val size = asset.available()
                    val buffer = ByteArray(size)
                    asset.read(buffer)
                    asset.close()

                    val fos = FileOutputStream(f)
                    fos.write(buffer)
                    fos.close()
                } catch (e: Exception) {
                    throw RuntimeException(e)
                }
            }
        }
    }
}