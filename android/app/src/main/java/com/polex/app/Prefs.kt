package com.polex.app

import android.content.Context
import android.content.SharedPreferences

/** Shared prefs: backend URL + cached status JSON (also read by the widget). */
object Prefs {

    private const val FILE = "polex"
    private const val KEY_BASE_URL = "baseUrl"
    private const val KEY_STATUS_CACHE = "statusCache"
    private const val KEY_STATUS_CACHE_TIME = "statusCacheTime"
    private const val KEY_TOKEN = "authToken"

    // Emulator loopback to the host machine by default.
    // On a physical device change this to your PC's LAN IP, e.g. http://192.168.1.5:8080
    private const val DEFAULT_BASE_URL = "http://10.0.2.2:8080"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun base(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL

    fun setBase(context: Context, url: String) {
        prefs(context).edit().putString(KEY_BASE_URL, url.trim().trimEnd('/')).apply()
    }

    fun cache(context: Context): String? =
        prefs(context).getString(KEY_STATUS_CACHE, null)

    /** Epoch millis of the last successful status cache write (0 = never). */
    fun cacheTime(context: Context): Long =
        prefs(context).getLong(KEY_STATUS_CACHE_TIME, 0L)

    fun setCache(context: Context, json: String) {
        prefs(context).edit()
            .putString(KEY_STATUS_CACHE, json)
            .putLong(KEY_STATUS_CACHE_TIME, System.currentTimeMillis())
            .apply()
    }

    // --- auth token (issued by POST /api/login) ---

    fun token(context: Context): String? =
        prefs(context).getString(KEY_TOKEN, null)

    fun hasToken(context: Context): Boolean =
        !prefs(context).getString(KEY_TOKEN, null).isNullOrEmpty()

    fun setToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
    }

    fun clearToken(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).apply()
    }
}
