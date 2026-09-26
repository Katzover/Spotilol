package com.project.lol.offline

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.project.lol.util.Logger
import java.io.File

/**
 * Storage Access Framework access to the folder the user picked for downloads. The tree URI is
 * granted at pick time and persisted, so any writable folder on any volume works without a
 * broad storage permission.
 */
object DownloadFolder {
    private const val TAG = "Spl-DL"

    data class Entry(val uri: Uri, val name: String)

    fun persist(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        true
    }.getOrElse {
        Logger.w(TAG, "downloadFolder: persist failed for $treeUri: ${it.message}")
        false
    }

    fun hasAccess(context: Context, treeUri: Uri): Boolean = runCatching {
        context.contentResolver.persistedUriPermissions
            .any { it.uri == treeUri && it.isWritePermission }
    }.getOrDefault(false)

    /** The document URI of the tree itself, which is what the system picker needs to open at it. */
    fun documentUri(treeUri: Uri): Uri =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )

    /** Full path for display, e.g. /storage/emulated/0/Music/Spotilol. */
    fun displayPath(treeUri: Uri): String {
        val id = treeDocumentId(treeUri)
        val volume = id.substringBefore(':')
        val relative = id.substringAfter(':', "")
        val root = if (volume == "primary") {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "External storage ($volume)"
        }
        return if (relative.isBlank()) root else "$root/$relative"
    }

    /** Short label for status messages, e.g. Music/Spotilol. */
    fun label(treeUri: Uri): String {
        val id = treeDocumentId(treeUri)
        val relative = id.substringAfter(':', "")
        return relative.ifBlank { "Internal storage" }
    }

    fun create(
        context: Context,
        treeUri: Uri,
        displayName: String,
        mime: String,
        source: File,
    ): Uri? {
        val resolver = context.contentResolver
        val target = runCatching {
            DocumentsContract.createDocument(resolver, documentUri(treeUri), mime, displayName)
        }.getOrElse {
            Logger.w(TAG, "downloadFolder: create failed for $displayName: ${it.message}")
            return null
        } ?: return null

        return runCatching {
            resolver.openOutputStream(target, "w")?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("openOutputStream returned null")
            target
        }.getOrElse {
            Logger.w(TAG, "downloadFolder: write failed for $displayName: ${it.message}")
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            null
        }
    }

    /** Files directly inside the tree, subfolders are skipped. */
    fun listChildren(context: Context, treeUri: Uri): List<Entry> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        val entries = mutableListOf<Entry>()
        runCatching {
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val mime = cursor.getString(2) ?: continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    val name = cursor.getString(1) ?: continue
                    val documentId = cursor.getString(0) ?: continue
                    entries.add(
                        Entry(
                            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
                            name,
                        )
                    )
                }
            }
        }.onFailure {
            Logger.w(TAG, "downloadFolder: listing failed for $treeUri: ${it.message}")
        }
        return entries
    }

    private fun treeDocumentId(treeUri: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrDefault("")
}
