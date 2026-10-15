package com.polex.app

import android.content.Context
import androidx.compose.ui.graphics.Color

/**
 * The app's connection state to the PoleX backend, as judged by the
 * existing fetch path — the dashboard's `GET /api/status` cycles (the
 * [runFetchCycle] auto-retry machinery in Disconnect.kt). This is the
 * single source of truth for the dashboard header's connection indicator
 * (color + content description), the server list's gray-out, and the
 * backoff between checks while the backend is unreachable.
 *
 * The three states:
 * - [CHECKING]     — no fetch cycle has finished yet (the dashboard just
 *                    opened); the indicator is neutral and the list is not
 *                    grayed out.
 * - [CONNECTED]    — the last check reached the backend. Any HTTP code
 *                    (even a 401 or a 5xx) proves the backend is up, so
 *                    those stay CONNECTED; only a connection-level failure
 *                    ([Disconnect.isDisconnected]) means disconnected.
 * - [DISCONNECTED] — the last check failed at the connection level
 *                    (backend unreachable). The list stays visible but is
 *                    grayed out and read-only; [ConnSupervisor] keeps
 *                    re-checking with backoff, so when the backend returns
 *                    the UI recovers automatically — no user tap or app
 *                    restart.
 */
enum class ConnStatus {
    CHECKING,
    CONNECTED,
    DISCONNECTED;

    /**
     * Human-readable state text, exposed as the indicator's content
     * description (screen-reader and uiautomator text).
     */
    fun contentDescription(): String = when (this) {
        CHECKING -> "Backend connection: checking"
        CONNECTED -> "Backend connection: valid"
        DISCONNECTED -> "Backend connection: last check failed"
    }

    /** True while the server list must be rendered grayed out and read-only. */
    fun listGrayedOut(): Boolean = this == DISCONNECTED

    /** The indicator light: gray while checking, green when connected,
     *  red when the last check failed. */
    fun indicatorColor(): Color = when (this) {
        CHECKING -> CHECKING_GRAY
        CONNECTED -> OnlineGreen
        DISCONNECTED -> OfflineRed
    }

    companion object {
        /** Neutral light color while the first check is pending. */
        val CHECKING_GRAY: Color = Color(0xFF9E9E9E)
    }
}

/**
 * The connection supervisor's state machine. Fed with the final result of
 * each fetch cycle (the existing fetch path — [runFetchCycle] already does
 * the quick 3× auto-retries), it derives the [ConnStatus] the UI displays
 * and the backoff delay before the next check while disconnected. Pure JVM
 * (no Android imports), so the transition and backoff logic is unit-
 * testable; the Compose screens only observe it.
 *
 * Cadence while disconnected: every consecutive failed check doubles the
 * wait for the next check ([INITIAL_RETRY_BACKOFF_MS] → ×2 per failure,
 * capped at [MAX_RETRY_BACKOFF_MS]). The first successful check is the
 * automatic recovery: the caller re-fetches the data and un-grays the list
 * without a user tap or an app restart, and the backoff resets to the
 * normal poll interval.
 */
class ConnSupervisor {
    companion object {
        const val INITIAL_RETRY_BACKOFF_MS = 10_000L
        const val MAX_RETRY_BACKOFF_MS = 60_000L
    }

    var status: ConnStatus = ConnStatus.CHECKING
        private set

    /** Epoch millis of the most recent finished check (0 = none yet). */
    var lastCheckAt: Long = 0L
        private set

    /** Consecutive failed checks (resets on any success). */
    var consecutiveFailures: Int = 0
        private set

    /**
     * Records the outcome of one finished fetch cycle. Only a connection-
     * level failure ([Disconnect.isDisconnected], code -1) means
     * disconnected; any other code (success, 401, 5xx, …) proves the
     * backend is reachable.
     */
    fun recordOutcome(code: Int, now: Long = System.currentTimeMillis()): ConnStatus {
        lastCheckAt = now
        status = if (Disconnect.isDisconnected(code)) {
            consecutiveFailures += 1
            ConnStatus.DISCONNECTED
        } else {
            consecutiveFailures = 0
            ConnStatus.CONNECTED
        }
        return status
    }

    /**
     * Wait before the next check while [status] is [ConnStatus.DISCONNECTED]:
     * doubles per consecutive failed check, capped at
     * [MAX_RETRY_BACKOFF_MS]. 0 while CHECKING/CONNECTED — the caller keeps
     * its own (poll) interval.
     */
    fun nextCheckDelayMs(): Long {
        if (status != ConnStatus.DISCONNECTED) return 0L
        val shift = (consecutiveFailures - 1).coerceAtMost(6)
        return minOf(INITIAL_RETRY_BACKOFF_MS shl shift, MAX_RETRY_BACKOFF_MS)
    }
}

/**
 * The last successfully fetched status list from the Prefs cache (the same
 * cache the widget renders from), or an empty list when the cache is absent
 * or unparseable. Lets the dashboard show the last known server state
 * (grayed out) while the backend is unreachable, instead of an empty list.
 */
fun cachedStatusList(context: Context): List<SrvStatus> =
    Prefs.cache(context)?.let { raw ->
        runCatching { parseStatusArray(raw) }.getOrNull()
    } ?: emptyList()
