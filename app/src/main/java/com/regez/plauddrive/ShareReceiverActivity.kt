package com.regez.plauddrive

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Unsichtbare Activity: nimmt geteilte Audiodateien an, legt sie in die Outbox und startet den Upload. */
class ShareReceiverActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = extractUris(intent)
        if (uris.isEmpty()) {
            Toast.makeText(this, "Keine Datei empfangen", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val prefs = Prefs(this)
        lifecycleScope.launch {
            val names = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching { Outbox.add(applicationContext, uri) }
                        .onFailure { prefs.log("✗ Konnte geteilte Datei nicht lesen: ${it.message}") }
                        .getOrNull()
                }
            }
            names.forEach { prefs.log("→ Warteschlange: $it") }
            UploadWorker.runNow(applicationContext)
            Toast.makeText(
                this@ShareReceiverActivity,
                "${names.size} Datei(en) werden nach Google Drive hochgeladen",
                Toast.LENGTH_SHORT
            ).show()
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun extractUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        )
        Intent.ACTION_SEND_MULTIPLE ->
            (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
        else -> emptyList()
    }
}
