package com.regez.plauddrive

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.regez.plauddrive.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs

    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        runCatching { DriveAuth.resultFromIntent(this, res.data) }
            .onSuccess { if (it.accessToken != null) onConnected() else prefs.log("Google-Zustimmung abgebrochen") }
            .onFailure { prefs.log("Google-Anmeldung fehlgeschlagen: ${it.message}") }
        refresh()
    }

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onFolderPicked(uri)
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)

        b.folderName.setText(prefs.driveFolderName)
        b.wavOnly.isChecked = prefs.wavOnly
        b.wifiOnly.isChecked = prefs.wifiOnly
        b.watchEnabled.isChecked = prefs.watchEnabled

        b.connect.setOnClickListener { connect() }
        b.pickFolder.setOnClickListener { pickFolder.launch(prefs.watchTreeUri?.let(Uri::parse)) }
        b.saveFolderName.setOnClickListener {
            prefs.driveFolderName = b.folderName.text.toString().trim()
            prefs.log("Drive-Zielordner: ${prefs.driveFolderName}")
            refresh()
        }
        b.wavOnly.setOnCheckedChangeListener { _, v -> prefs.wavOnly = v }
        b.wifiOnly.setOnCheckedChangeListener { _, v -> prefs.wifiOnly = v; UploadWorker.schedulePeriodic(this) }
        b.watchEnabled.setOnCheckedChangeListener { _, v ->
            prefs.watchEnabled = v
            UploadWorker.schedulePeriodic(this)
            refresh()
        }
        b.syncNow.setOnClickListener {
            prefs.log("Synchronisierung angestoßen")
            UploadWorker.runNow(this)
            refresh()
        }
        b.refreshLog.setOnClickListener { refresh() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun connect() {
        lifecycleScope.launch {
            try {
                val result = DriveAuth.authorizeInteractive(this@MainActivity)
                val pending = result.pendingIntent
                if (result.hasResolution() && pending != null) {
                    consent.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                } else {
                    onConnected()
                }
            } catch (e: Exception) {
                prefs.log("Google-Anmeldung fehlgeschlagen: ${e.message} (OAuth-Client/SHA-1 in der Cloud Console prüfen)")
                refresh()
            }
        }
    }

    private fun onConnected() {
        prefs.accountHint = "verbunden"
        prefs.log("Mit Google Drive verbunden")
        UploadWorker.runNow(this)
        refresh()
    }

    private fun onFolderPicked(uri: Uri) {
        contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.watchTreeUri = uri.toString()
        prefs.log("Überwachter Ordner: ${describe(uri)}")
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                runCatching { FolderScanner.list(this@MainActivity, uri, prefs.wavOnly) }.getOrDefault(emptyList())
            }.filterNot { prefs.isUploaded(it.key) }
            if (existing.isEmpty()) {
                enableWatch()
                return@launch
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("${existing.size} vorhandene Aufnahme(n) gefunden")
                .setMessage("Sollen die bereits vorhandenen Dateien auch hochgeladen werden – oder nur neue?")
                .setPositiveButton("Alle hochladen") { _, _ -> enableWatch(); UploadWorker.runNow(this@MainActivity) }
                .setNegativeButton("Nur neue") { _, _ ->
                    prefs.markAllUploaded(existing.map { it.key })
                    enableWatch()
                }
                .show()
        }
    }

    private fun enableWatch() {
        prefs.watchEnabled = true
        b.watchEnabled.isChecked = true
        UploadWorker.schedulePeriodic(this)
        refresh()
    }

    private fun describe(uri: Uri): String =
        runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrDefault(uri.toString())

    private fun refresh() {
        b.status.text = buildString {
            append("Google Drive: ").append(if (prefs.accountHint != null) "verbunden" else "nicht verbunden").append('\n')
            append("Drive-Ordner: ").append(prefs.driveFolderName).append('\n')
            append("Überwachter Ordner: ")
                .append(prefs.watchTreeUri?.let { describe(Uri.parse(it)) } ?: "keiner")
                .append(if (prefs.watchEnabled) " (aktiv, alle 15 min)" else " (aus)")
        }
        b.log.text = prefs.logText.ifBlank { "Noch keine Aktivität." }
    }
}
