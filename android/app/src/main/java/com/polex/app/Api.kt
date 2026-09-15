package com.polex.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Tiny OkHttp-based REST client. All calls are blocking; run on Dispatchers.IO. */
object Api {

    data class Result(val code: Int, val body: String)

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json".toMediaType()

    /** Bearer token set by the login flow; sent on every request when non-null. */
    @Volatile
    var authToken: String? = null

    fun request(method: String, url: String, json: String? = null): Result {
        val body = json?.toRequestBody(JSON)
        val builder = Request.Builder()
            .url(url)
            .method(method, body)
            .header("Content-Type", "application/json")
        authToken?.let { builder.header("Authorization", "Bearer $it") }
        val req = builder.build()
        client.newCall(req).execute().use { resp ->
            return Result(resp.code, resp.body?.string() ?: "")
        }
    }

    fun get(url: String): Result = request("GET", url)
    fun post(url: String, json: String = "{}"): Result = request("POST", url, json)
    fun put(url: String, json: String): Result = request("PUT", url, json)
    fun delete(url: String): Result = request("DELETE", url)

    /**
     * Runs a blocking call on the IO dispatcher; connection-level failures
     * become Result(-1, message) instead of escaping as exceptions, so
     * callers can branch on [Result.code] alone.
     */
    suspend fun safe(block: () -> Result): Result =
        withContext(Dispatchers.IO) {
            try {
                block()
            } catch (e: Exception) {
                Result(-1, e.message ?: "connection failed")
            }
        }

    /**
     * GET /api/version → the backend's version string, or null when the
     * backend is unreachable or the response is unusable. Null means
     * "unknown", NOT a version mismatch (a connection failure is not a
     * mismatch — see `outdatedSide` in Versions.kt).
     */
    suspend fun getBackendVersion(context: Context): String? {
        val r = safe { request("GET", "${base(context)}/api/version") }
        if (r.code != 200) return null
        return runCatching { JSONObject(r.body).optString("version") }
            .getOrNull()
            .takeIf { it.isNotBlank() }
    }

    /** POSTs a WOL magic packet for a server; callers check [Result.code] + [parseOk]. */
    suspend fun sendWol(context: Context, serverId: Int): Result =
        safe { post("${base(context)}/api/servers/$serverId/wol") }

    fun base(context: Context): String = Prefs.base(context).trim().trimEnd('/')
}
