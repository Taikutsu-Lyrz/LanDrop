package dev.landrop.mobile

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.ContentResolver
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.annotation.RequiresApi

internal const val DOWNLOADS_DESTINATION = "landrop://downloads"

internal fun documentByteSize(resolver: ContentResolver, uri: Uri): Long {
    if (uri.authority == MediaStore.AUTHORITY) {
        val mediaSize = runCatching {
            resolver.openFileDescriptor(uri, "r")?.use { pfd -> pfd.statSize }
        }.getOrNull()
        if (mediaSize != null && mediaSize >= 0L) return mediaSize
    }
    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
        if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
            val queried = cursor.getLong(sizeIndex)
            if (queried > 0L) return queried
        }
    }
    return runCatching {
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            pfd.statSize.takeIf { it >= 0L }
        }
    }.getOrNull() ?: 0L
}

internal fun receivePathParts(path: String, category: String, rootName: String): List<String> {
    val parts = path.split('/').filter { it.isNotBlank() }
    require(parts.isNotEmpty() && parts.none { it == "." || it == ".." }) { "Invalid receive path" }
    val prefix = if (rootName.equals("LanDrop", ignoreCase = true)) emptyList() else listOf("LanDrop")
    return prefix + category + parts.map { it.replace('\\', '_') }
}

@RequiresApi(Build.VERSION_CODES.Q)
internal class DownloadsDestination(private val context: Context) {
    private val resolver get() = context.contentResolver
    private val collection get() = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun directory(path: String, category: String): String =
        "Download/" + receivePathParts(path, category, "Download").dropLast(1).joinToString("/") + "/"

    fun findPartial(path: String, category: String): Uri? {
        val name = receivePathParts(path, category, "Download").last() + ".landrop.part"
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=1"
        val args = arrayOf(directory(path, category), name)
        @Suppress("DEPRECATION")
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resolver.query(collection, projection, Bundle().apply {
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            }, null)
        } else {
            resolver.query(MediaStore.setIncludePending(collection), projection, selection, args, null)
        }
        matches?.use { cursor ->
            if (cursor.moveToFirst()) {
                return android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
            }
        }
        return null
    }

    fun createPartial(path: String, category: String): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, receivePathParts(path, category, "Download").last() + ".landrop.part")
            put(MediaStore.MediaColumns.RELATIVE_PATH, directory(path, category))
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection, values) ?: error("Could not create download")
    }

    fun finish(uri: Uri, path: String, category: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, receivePathParts(path, category, "Download").last())
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        check(resolver.update(uri, values, null, null) == 1) { "Could not finalize download" }
    }
}
