package com.shiturmedia.compressor

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.ShareCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream

/**
 * Saves a video [File] from internal/cache storage into the public device gallery (Movies/ShitUrMedia).
 * Returns the MediaStore content [Uri] if successful, or null on error.
 */
fun saveVideoToGallery(context: Context, videoFile: File): Uri? {
    return try {
        val filename = "SUMC_${System.currentTimeMillis()}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, filename)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ShitUrMedia")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val itemUri = resolver.insert(collection, contentValues) ?: return null

        resolver.openOutputStream(itemUri)?.use { out ->
            FileInputStream(videoFile).use { input ->
                input.copyTo(out)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(itemUri, contentValues, null, null)
        }

        itemUri
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

/**
 * Shares a video [File] via Android's ACTION_SEND intent using FileProvider.
 */
fun shareVideo(context: Context, videoFile: File) {
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            videoFile
        )
        val intent = ShareCompat.IntentBuilder(context)
            .setType("video/mp4")
            .setStream(uri)
            .setChooserTitle("Videoyu Paylaş")
            .createChooserIntent()
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
