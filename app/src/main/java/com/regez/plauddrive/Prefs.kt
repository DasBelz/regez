package com.regez.plauddrive

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("plauddrive", Context.MODE_PRIVATE)

    var driveFolderName: String
        get() = sp.getString("folder_name", null)?.takeIf { it.isNotBlank() } ?: "Plaud Recordings"
        set(v) {
            sp.edit().putString("folder_name", v).remove("folder_id").apply()
        }

    var driveFolderId: String?
        get() = sp.getString("folder_id", null)
        set(v) = sp.edit().putString("folder_id", v).apply()

    var watchTreeUri: String?
        get() = sp.getString("watch_uri", null)
        set(v) = sp.edit().putString("watch_uri", v).apply()

    var watchEnabled: Boolean
        get() = sp.getBoolean("watch_enabled", false)
        set(v) = sp.edit().putBoolean("watch_enabled", v).apply()

    var wavOnly: Boolean
        get() = sp.getBoolean("wav_only", true)
        set(v) = sp.edit().putBoolean("wav_only", v).apply()

    var wifiOnly: Boolean
        get() = sp.getBoolean("wifi_only", false)
        set(v) = sp.edit().putBoolean("wifi_only", v).apply()

    var accountHint: String?
        get() = sp.getString("account", null)
        set(v) = sp.edit().putString("account", v).apply()

    fun isUploaded(key: String) = sp.getStringSet("uploaded", emptySet())!!.contains(key)

    fun markUploaded(key: String) {
        val set = HashSet(sp.getStringSet("uploaded", emptySet())!!)
        set.add(key)
        sp.edit().putStringSet("uploaded", set).apply()
    }

    /** Markiert alle aktuell vorhandenen Dateien als erledigt, damit nur neue hochgeladen werden. */
    fun markAllUploaded(keys: Collection<String>) {
        val set = HashSet(sp.getStringSet("uploaded", emptySet())!!)
        set.addAll(keys)
        sp.edit().putStringSet("uploaded", set).apply()
    }

    @Synchronized
    fun log(msg: String) {
        val ts = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY).format(Date())
        val lines = (listOf("$ts  $msg") + logText.lines()).filter { it.isNotBlank() }.take(80)
        sp.edit().putString("log", lines.joinToString("\n")).apply()
    }

    val logText: String get() = sp.getString("log", "") ?: ""
}
