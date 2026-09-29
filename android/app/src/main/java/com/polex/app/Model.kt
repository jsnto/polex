package com.polex.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** One server status row as returned by GET /api/status. */
data class SrvStatus(
    val id: Int,
    val name: String,
    val ip: String,
    val mac: String,
    val port: Int,
    val check: String,   // "tcp" (default) or "ping"
    val sshUser: String,   // SSH power-off (with sshKeyPath and/or sshPassword); user alone = off
    val sshKeyPath: String, // path to the private key on the backend host
    val sshPassword: String, // password auth; fallback after a rejected key (direct when no key)
    val online: Boolean,
    val latencyMs: Long,
    val lastChecked: String,
    val lastWolAt: String,
    val lastShutdownAt: String,
)

fun parseStatusArray(body: String): List<SrvStatus> {
    val arr = JSONArray(body)
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        SrvStatus(
            id = o.optInt("id"),
            name = o.optString("name"),
            ip = o.optString("ip"),
            mac = o.optString("mac"),
            port = o.optInt("port"),
            check = o.optString("check", "tcp"),
            sshUser = o.optString("sshUser"),
            sshKeyPath = o.optString("sshKeyPath"),
            sshPassword = o.optString("sshPassword"),
            online = o.optBoolean("online"),
            latencyMs = o.optLong("latencyMs"),
            lastChecked = o.optString("lastChecked"),
            lastWolAt = o.optString("lastWolAt"),
            lastShutdownAt = o.optString("lastShutdownAt"),
        )
    }
}

/** Parsed server entry as returned by GET /api/servers. */
data class SrvEntry(
    val id: Int,
    val name: String,
    val ip: String,
    val mac: String,
    val port: Int,
    val check: String,   // "tcp" (default) or "ping"
    val sshUser: String = "",
    val sshKeyPath: String = "",
    val sshPassword: String = "",
)

fun parseServerArray(body: String): List<SrvEntry> {
    val arr = JSONArray(body)
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        SrvEntry(
            id = o.optInt("id"),
            name = o.optString("name"),
            ip = o.optString("ip"),
            mac = o.optString("mac"),
            port = o.optInt("port"),
            check = o.optString("check", "tcp"),
            sshUser = o.optString("sshUser"),
            sshKeyPath = o.optString("sshKeyPath"),
            sshPassword = o.optString("sshPassword"),
        )
    }
}

/** True when SSH power-off is usable for this server (mirrors the backend's
 *  `sshConfigured`: the user plus at least one credential — key path and/or
 *  password — like an empty MAC disables WOL). */
fun SrvStatus.sshConfigured(): Boolean = sshUser.isNotEmpty() && (sshKeyPath.isNotEmpty() || sshPassword.isNotEmpty())

fun serverToJson(
    name: String,
    ip: String,
    mac: String,
    port: Int,
    check: String,
    sshUser: String = "",
    sshKeyPath: String = "",
    sshPassword: String = "",
): String {
    val o = JSONObject()
    o.put("name", name)
    o.put("ip", ip)
    o.put("mac", mac)
    o.put("port", port)
    o.put("check", check)
    o.put("sshUser", sshUser)
    o.put("sshKeyPath", sshKeyPath)
    o.put("sshPassword", sshPassword)
    return o.toString()
}

/** Body for POST /api/servers/reorder: {"order": [id, id, ...]}. */
fun reorderJson(ids: List<Int>): String {
    val o = JSONObject()
    val arr = JSONArray()
    ids.forEach { arr.put(it) }
    o.put("order", arr)
    return o.toString()
}

/** `true` if a WOL response body looks like `{"ok": true}`. */
fun parseOk(body: String): Boolean =
    try {
        JSONObject(body).optBoolean("ok", false)
    } catch (_: Exception) {
        false
    }

/** Parses an RFC3339 UTC timestamp to epoch millis (null if unparseable, e.g. "never"). */
fun parseEpoch(rfc3339: String): Long? =
    try {
        Instant.parse(rfc3339).toEpochMilli()
    } catch (_: Exception) {
        null
    }

/** Human-friendly age ("42s ago", "5m ago") of a timestamp, or the raw string if unparseable. */
fun relativeTime(rfc3339: String): String {
    val ms = parseEpoch(rfc3339) ?: return if (rfc3339.isEmpty()) "never" else rfc3339
    return relativeTime(ms)
}

/** Human-friendly age ("42s ago", "5m ago") of an epoch-millis timestamp. */
fun relativeTime(epochMillis: Long): String {
    val secs = ((System.currentTimeMillis() - epochMillis) / 1000).coerceAtLeast(0)
    return when {
        secs < 5 -> "just now"
        secs < 60 -> "${secs}s ago"
        secs < 3600 -> "${secs / 60}m ago"
        secs < 86400 -> "${secs / 3600}h ago"
        else -> "${secs / 86400}d ago"
    }
}
