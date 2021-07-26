package ca.uqam.espaceunaw

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object AssetExtractor {
    private const val TAG = "AssetExtractor"

    // TODO: Extract assets only if updated instead of using overwrite = true
    // From: https://stackoverflow.com/a/8475135/965332
    fun extractAssets(path: String, context: Context, overwrite: Boolean = true) {
        val filenames = context.assets.list(path)
        //Log.w(TAG, "path: $path")
        //Log.w(TAG, "files: ${filenames!!.joinToString(", ")}")
        if (filenames != null) {
            for(fn in filenames) {
                val sourcePath = path + File.separator + fn
                val destPath = context.cacheDir.path + File.separator + path + File.separator + fn
                val f = File(destPath)

                // If directory, recurse
                // TODO: Fix this ugly folder-guess hack
                if(!f.path.substringAfterLast("/").contains(".")) {
                    //Log.w(TAG, "WAS DIR")
                    extractAssets(sourcePath, context, overwrite)
                    continue
                }


                // Create parent directory if missing
                val dir = f.parentFile
                if(!dir.exists()) {
                    dir.mkdirs()
                }

                // Write file if it doesn't exist, or if overwrite is true
                // TODO: Fix the ugly folder-detection hack
                if ((!f.exists() || overwrite) && f.path.substringAfterLast("/").contains(".")) {
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
                } else {
                    Log.w(TAG, "Skipped: $sourcePath -> $destPath")
                }
            }
        }
    }
}