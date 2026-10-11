package dev.landrop.mobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.content.pm.PackageManager
import android.provider.DocumentsContract

internal fun receiveFolderProblem(context: Context, treeUri: Uri?): String? {
    if (treeUri == null) return "Choose a save folder before receiving."
    return runCatching {
        if (context.checkUriPermission(
                treeUri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return "Save folder access was lost. Choose it again."
        }
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val cursor = context.contentResolver.query(
            documentUri,
            arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS),
            null, null, null
        ) ?: return "Save folder is unavailable. Choose it again."
        cursor.use {
            if (!cursor.moveToFirst()) return "Save folder is unavailable. Choose it again."
            val isDirectory = cursor.getString(0) == DocumentsContract.Document.MIME_TYPE_DIR
            val canCreate = cursor.getInt(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0
            if (isDirectory && canCreate) null else "This folder is read-only. Choose another save folder."
        }
    }.getOrElse { "Save folder is unavailable. Choose it again." }
}
