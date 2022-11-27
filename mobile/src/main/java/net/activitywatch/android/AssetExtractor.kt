package net.activitywatch.android

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream

object AssetExtractor {
    private const val TAG = "AssetExtractor"

    // Returns the extracted versionCode, or 0 if it doesn't exist,
    // used to check if we need to extract the assets again.
    private fun getExtractedVersion(context: Context): Int {
        val file = File(context.cacheDir, "last_extracted_version")
        if(file.exists()) {
            return file.readText().toInt()
        }
        return 0
    }

    // Sets extracted version to the current versionCode
    private fun setExtractedVersion(context: Context) {
        val version = BuildConfig.VERSION_CODE
        val file = File(context.cacheDir, "last_extracted_version")
        file.writeText(version.toString())
    }

    // Extracts all assets to the cacheDir
    // Tries to detect updated using the versionCode,
    // but will always extract if the file doesn't exist or if in debug mode.
    // From: https://stackoverflow.com/a/8475135/965332
    fun extractAssets(path: String, context: Context, force_overwrite: Boolean = false) {
        val extractedOlder = getExtractedVersion(context) != BuildConfig.VERSION_CODE
        val overwrite = force_overwrite || extractedOlder || BuildConfig.DEBUG

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

        if(extractedOlder) {
            setExtractedVersion(context)
        }
    }
}
