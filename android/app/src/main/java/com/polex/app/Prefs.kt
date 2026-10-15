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
    private const val KEY_WIDGET_COLUMNS = "widgetColumns"
    private const val KEY_WIDGET_SELECTED = "widgetSelectedServers"

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

    // --- widget display settings (app-local; the widget reads them at
    //     render time, see WidgetContent.kt) ---

    /** The widget server grid's column mode: 1 or 2 (default 2). */
    fun widgetColumns(context: Context): Int =
        normalizeWidgetColumns(prefs(context).getInt(KEY_WIDGET_COLUMNS, WIDGET_COLUMNS_DEFAULT))

    fun setWidgetColumns(context: Context, columns: Int) {
        prefs(context).edit().putInt(KEY_WIDGET_COLUMNS, normalizeWidgetColumns(columns)).apply()
    }

    /**
     * Server ids the widget's grid shows; `null` (unset) = all servers
     * (the default). A stored empty set is a real "show nothing" choice,
     * distinct from the unset default. Stale ids (removed servers) are
     * dropped by the widget at render time — never persisted back.
     */
    fun widgetSelectedServerIds(context: Context): Set<Int>? {
        val raw = prefs(context).getStringSet(KEY_WIDGET_SELECTED, null) ?: return null
        return raw.mapNotNull { it.toIntOrNull() }.toSet()
    }

    fun setWidgetSelectedServerIds(context: Context, ids: Set<Int>) {
        prefs(context).edit()
            .putStringSet(KEY_WIDGET_SELECTED, ids.map { it.toString() }.toSet())
            .apply()
    }
}
