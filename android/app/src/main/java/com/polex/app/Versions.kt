package com.polex.app

/**
 * Numeric comparison of simple dotted version strings ("1.0", "1.10", "2.0.1").
 * Segments compare as integers (so "1.10" > "1.9"); the shorter string is
 * zero-padded, and non-numeric segments count as 0. Returns negative/zero/
 * positive like [compareBy].
 */
fun compareVersions(a: String, b: String): Int {
    val pa = a.trim().split('.')
    val pb = b.trim().split('.')
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
        val y = pb.getOrNull(i)?.trim()?.toIntOrNull() ?: 0
        if (x != y) return x - y
    }
    return 0
}

/**
 * Which side is out of date when the versions disagree.
 *
 * Returns `"app"` when the app version is LOWER than the backend version
 * (update the app), `"backend"` when the backend is lower (update the
 * backend), and `null` when they are equal OR the backend version is
 * unknown/unreachable — an unreachable backend is a connection failure, not
 * a version mismatch.
 */
fun outdatedSide(appVersion: String, backendVersion: String?): String? {
    if (backendVersion == null) return null
    return when (val c = compareVersions(appVersion, backendVersion)) {
        0 -> null
        else -> if (c < 0) "app" else "backend"
    }
}

/** One-line settings-screen explanation for [outdatedSide] (null = none). */
fun mismatchExplanation(side: String?): String? = when (side) {
    "app" -> "App is older than the backend — update the app."
    "backend" -> "Backend is older than the app — update the backend."
    else -> null
}
