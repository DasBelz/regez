package com.regez.plauddrive

import android.app.Activity
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Google-Autorisierung über die Identity-API (Play Services).
 * Scope drive.file: Die App sieht in Drive nur Dateien/Ordner, die sie selbst angelegt hat.
 */
object DriveAuth {
    private const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

    private fun request() = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DRIVE_FILE)))
        .build()

    /** Für die UI: liefert ein Ergebnis, das ggf. eine Zustimmungs-Resolution enthält. */
    suspend fun authorizeInteractive(activity: Activity): AuthorizationResult =
        Identity.getAuthorizationClient(activity).authorize(request()).await()

    fun resultFromIntent(activity: Activity, data: Intent?): AuthorizationResult =
        Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(data)

    /**
     * Für den Hintergrund: liefert ein Access-Token, wenn der Nutzer bereits zugestimmt hat,
     * sonst null (dann muss die App einmal geöffnet und "Mit Google verbinden" getippt werden).
     */
    suspend fun backgroundToken(context: Context): String? {
        val result = Identity.getAuthorizationClient(context).authorize(request()).await()
        return if (result.hasResolution()) null else result.accessToken
    }

    /** Verwirft ein abgelaufenes Token, damit beim nächsten authorize() ein frisches kommt. */
    suspend fun invalidate(context: Context, token: String) = withContext(Dispatchers.IO) {
        runCatching { GoogleAuthUtil.clearToken(context, token) }
    }
}
