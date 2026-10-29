package com.regez.plauddrive

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = lock.withLock {
        val ctx = applicationContext
        val prefs = Prefs(ctx)

        val items = try {
            Outbox.pending(ctx) + if (prefs.watchEnabled) FolderScanner.pending(ctx, prefs) else emptyList()
        } catch (e: SecurityException) {
            prefs.log("Kein Zugriff mehr auf den überwachten Ordner – bitte neu auswählen.")
            Outbox.pending(ctx)
        }
        if (items.isEmpty()) return Result.success()

        runCatching { setForeground(foregroundInfo("Lade ${items.size} Aufnahme(n) nach Drive …")) }

        var token = DriveAuth.backgroundToken(ctx)
        if (token == null) {
            prefs.log("Nicht mit Google verbunden – App öffnen und \"Mit Google verbinden\" tippen.")
            notify(ctx, "Plaud → Drive: Anmeldung nötig", "Tippen, um Google Drive zu verbinden.")
            return Result.failure()
        }

        var failed = 0
        for (item in items) {
            var attempt = 0
            while (true) {
                try {
                    val drive = DriveClient(token!!)
                    val folderId = drive.ensureFolder(prefs.driveFolderName, prefs.driveFolderId)
                    prefs.driveFolderId = folderId
                    drive.upload(item.name, item.mime, item.size, folderId, item.open)
                    item.onDone()
                    prefs.log("✓ ${item.name} (${item.size / 1024 / 1024} MB) hochgeladen")
                    break
                } catch (e: AuthExpiredException) {
                    if (attempt++ > 0) { failed++; prefs.log("✗ ${item.name}: Auth fehlgeschlagen"); break }
                    DriveAuth.invalidate(ctx, token!!)
                    token = DriveAuth.backgroundToken(ctx) ?: return Result.failure()
                } catch (e: Exception) {
                    failed++
                    prefs.log("✗ ${item.name}: ${e.message}")
                    break
                }
            }
        }
        if (failed == 0 && items.isNotEmpty()) {
            notify(ctx, "Plaud → Drive", "${items.size} Aufnahme(n) in \"${prefs.driveFolderName}\" gesichert.")
        }
        return if (failed > 0) Result.retry() else Result.success()
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        ensureChannel(applicationContext)
        val n = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Plaud → Drive")
            .setContentText(text)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(FG_ID, n)
    }

    companion object {
        private val lock = Mutex()
        private const val CHANNEL = "uploads"
        private const val FG_ID = 1
        private const val NOTE_ID = 2
        private const val WORK_NOW = "upload-now"
        private const val WORK_PERIODIC = "watch-folder"

        private fun constraints(ctx: Context) = Constraints.Builder()
            .setRequiredNetworkType(if (Prefs(ctx).wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()

        /** Sofortiger Upload (nach Teilen oder "Jetzt synchronisieren"). */
        fun runNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints(ctx))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, req)
        }

        /** Periodischer Scan des überwachten Ordners (Android-Minimum: 15 Minuten). */
        fun schedulePeriodic(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            if (!Prefs(ctx).watchEnabled) {
                wm.cancelUniqueWork(WORK_PERIODIC)
                return
            }
            val req = PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints(ctx))
                .build()
            wm.enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        private fun ensureChannel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "Uploads", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }

        private fun notify(ctx: Context, title: String, text: String) {
            ensureChannel(ctx)
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { NotificationManagerCompat.from(ctx).notify(NOTE_ID, n) }
        }
    }
}
