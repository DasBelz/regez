package com.regez.plauddrive

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.InputStream

/** Eine Datei, die nach Drive soll – egal ob geteilt oder im überwachten Ordner gefunden. */
class Pending(
    val name: String,
    val size: Long,
    val mime: String,
    /** Eindeutiger Schlüssel gegen Doppel-Uploads (null = Outbox-Datei, wird nach Upload gelöscht). */
    val key: String?,
    val open: () -> InputStream,
    val onDone: () -> Unit,
)

fun mimeFor(name: String, fallback: String? = null): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext == "wav") return "audio/wav"
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        ?: fallback?.takeIf { it != "application/octet-stream" }
        ?: "application/octet-stream"
}

private val AUDIO_EXT = setOf("wav", "mp3", "m4a", "aac", "ogg", "opus", "flac", "amr")

fun isWanted(name: String, wavOnly: Boolean): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return if (wavOnly) ext == "wav" else ext in AUDIO_EXT
}

/**
 * Outbox: Dateien, die per Teilen-Menü reinkommen, werden sofort in den App-Speicher kopiert.
 * Grund: Die Leseberechtigung für geteilte URIs ist nur temporär – der Upload läuft aber später
 * im Hintergrund (WorkManager), evtl. erst wenn WLAN da ist.
 */
object Outbox {
    private fun dir(ctx: Context) = File(ctx.filesDir, "outbox").apply { mkdirs() }

    fun add(ctx: Context, uri: Uri): String {
        val cr = ctx.contentResolver
        var name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "plaud_${System.currentTimeMillis()}"
        if (!name.contains('.')) {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(cr.getType(uri))
            if (ext != null) name += ".$ext"
        }
        val target = File(dir(ctx), "${System.currentTimeMillis()}__$name")
        cr.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        return name
    }

    fun pending(ctx: Context): List<Pending> =
        dir(ctx).listFiles().orEmpty().sortedBy { it.name }.map { f ->
            val name = f.name.substringAfter("__")
            Pending(name, f.length(), mimeFor(name), null, { f.inputStream() }, { f.delete() })
        }
}

/** Durchsucht einen per Storage Access Framework freigegebenen Ordner (rekursiv) nach neuen Aufnahmen. */
object FolderScanner {
    /** Dateien, die jünger als das sind, werden evtl. noch geschrieben → nächster Lauf. */
    private const val SETTLE_MS = 60_000L

    data class Entry(val uri: Uri, val name: String, val size: Long, val mime: String?, val key: String)

    fun list(ctx: Context, tree: Uri, wavOnly: Boolean): List<Entry> {
        val out = mutableListOf<Entry>()
        walk(ctx, tree, DocumentsContract.getTreeDocumentId(tree), wavOnly, 0, out)
        return out
    }

    private fun walk(ctx: Context, tree: Uri, docId: String, wavOnly: Boolean, depth: Int, out: MutableList<Entry>) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val now = System.currentTimeMillis()
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(3)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (depth < 4) walk(ctx, tree, id, wavOnly, depth + 1, out)
                    continue
                }
                if (!isWanted(name, wavOnly)) continue
                val size = c.getLong(2)
                val modified = c.getLong(4)
                if (size <= 0 || now - modified < SETTLE_MS) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                out += Entry(uri, name, size, mime, "$id|$size|$modified")
            }
        }
    }

    fun pending(ctx: Context, prefs: Prefs): List<Pending> {
        val tree = prefs.watchTreeUri?.let(Uri::parse) ?: return emptyList()
        return list(ctx, tree, prefs.wavOnly)
            .filterNot { prefs.isUploaded(it.key) }
            .map { e ->
                Pending(e.name, e.size, mimeFor(e.name, e.mime), e.key,
                    { ctx.contentResolver.openInputStream(e.uri)!! },
                    { prefs.markUploaded(e.key) })
            }
    }
}
