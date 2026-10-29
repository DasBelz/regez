package com.regez.plauddrive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class AuthExpiredException : IOException("401 Unauthorized")

/** Minimaler Google-Drive-v3-REST-Client (ohne Google-API-Bibliothek). */
class DriveClient(private val token: String) {

    private fun connect(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $token")
        }

    private fun HttpURLConnection.check(vararg ok: Int): HttpURLConnection {
        val code = responseCode
        if (code == 401) throw AuthExpiredException()
        if (code !in ok) {
            val err = errorStream?.bufferedReader()?.readText().orEmpty()
            throw IOException("HTTP $code: ${err.take(300)}")
        }
        return this
    }

    private fun HttpURLConnection.json(): JSONObject =
        JSONObject(inputStream.bufferedReader().use { it.readText() })

    private fun HttpURLConnection.sendJson(body: JSONObject) {
        doOutput = true
        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        outputStream.use { it.write(body.toString().toByteArray()) }
    }

    /** Liefert die ID des Zielordners; legt ihn an, falls nötig. */
    suspend fun ensureFolder(name: String, cachedId: String?): String = withContext(Dispatchers.IO) {
        if (cachedId != null) {
            val c = connect("$API/files/$cachedId?fields=id,trashed", "GET")
            if (c.responseCode == 200 && !c.json().optBoolean("trashed")) return@withContext cachedId
            if (c.responseCode == 401) throw AuthExpiredException()
        }
        val escaped = name.replace("\\", "\\\\").replace("'", "\\'")
        val q = "mimeType='$FOLDER_MIME' and name='$escaped' and trashed=false"
        val search = connect("$API/files?fields=files(id)&q=${URLEncoder.encode(q, "UTF-8")}", "GET").check(200)
        val files = search.json().optJSONArray("files")
        if (files != null && files.length() > 0) return@withContext files.getJSONObject(0).getString("id")

        val create = connect("$API/files?fields=id", "POST")
        create.sendJson(JSONObject().put("name", name).put("mimeType", FOLDER_MIME))
        create.check(200).json().getString("id")
    }

    /** Resumable Upload: streamt die Datei, ohne sie komplett in den RAM zu laden. */
    suspend fun upload(
        name: String,
        mime: String,
        size: Long,
        folderId: String,
        open: () -> InputStream,
    ): String = withContext(Dispatchers.IO) {
        val init = connect("$UPLOAD/files?uploadType=resumable&fields=id", "POST")
        init.setRequestProperty("X-Upload-Content-Type", mime)
        init.setRequestProperty("X-Upload-Content-Length", size.toString())
        init.sendJson(
            JSONObject()
                .put("name", name)
                .put("parents", JSONArray().put(folderId))
                .put("description", "Automatisch hochgeladen von Plaud → Drive")
        )
        init.check(200)
        val session = init.getHeaderField("Location") ?: throw IOException("Keine Upload-Session erhalten")

        val put = (URL(session).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 30_000
            readTimeout = 300_000
            setFixedLengthStreamingMode(size)
            setRequestProperty("Content-Type", mime)
        }
        open().use { input -> put.outputStream.use { input.copyTo(it, 256 * 1024) } }
        put.check(200, 201).json().getString("id")
    }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
    }
}
