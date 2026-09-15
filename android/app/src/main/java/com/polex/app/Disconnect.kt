package com.polex.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Disconnected-from-server policy, shared by the dashboard and the server
 * detail page.
 *
 * A connection-level failure (backend unreachable; [Api.Result.code] == -1)
 * means "disconnected": the user's token and session are left exactly as
 * they are — no `Prefs.clearToken`, no routing to the login screen. Only a
 * genuine 401 from a REACHABLE backend means the session needs re-auth.
 */
object Disconnect {

    /** How many automatic retries a connection-level failure gets before the user must tap Retry. */
    const val MAX_AUTO_ATTEMPTS = 3

    /** Delay between automatic retries. */
    const val RETRY_DELAY_MS = 2_000L

    /** True only when a reachable backend rejected the credentials/session. */
    fun requiresReauth(code: Int): Boolean = code == 401

    /** True when the request never reached the backend (network-level failure). */
    fun isDisconnected(code: Int): Boolean = code == -1
}

/**
 * Bookkeeping for one auto-retry cycle. Pure JVM (no Android imports), so the
 * retry-count logic is unit-testable.
 *
 * A cycle starts with the fetch that first failed; up to
 * [Disconnect.MAX_AUTO_ATTEMPTS] further attempts ("auto-retries") follow,
 * each separated by [Disconnect.RETRY_DELAY_MS]. When [exhausted], the UI
 * offers a manual Retry button whose tap starts a fresh cycle via [reset].
 */
class RetryTracker {

    /** Auto-retries made so far in the current cycle (0 = cycle just started). */
    var attempts: Int = 0
        private set

    /** No auto-retries remain in the current cycle. */
    val exhausted: Boolean get() = attempts >= Disconnect.MAX_AUTO_ATTEMPTS

    /** Records one auto-retry attempt and returns the new count. */
    fun recordAttempt(): Int {
        attempts += 1
        return attempts
    }

    /** Starts a fresh cycle (user tapped Retry, or the connection recovered). */
    fun reset() {
        attempts = 0
    }
}

/**
 * Runs [fetch] in an auto-retry cycle for connection-level failures:
 * the first call happens immediately; while it returns a disconnected
 * result ([Disconnect.isDisconnected]) and the [tracker] still has
 * auto-retries left, waits [delayMs] and calls [fetch] again.
 *
 * Returns as soon as [fetch] produces any non-disconnected result (success,
 * 401, or a reachable-server error) or when the auto-retries are exhausted.
 * [onAttempt] fires after each failed auto-retry (with its 1-based number)
 * so the UI can show live progress.
 */
suspend fun runFetchCycle(
    tracker: RetryTracker,
    delayMs: Long = Disconnect.RETRY_DELAY_MS,
    onAttempt: (Int) -> Unit = {},
    fetch: suspend () -> Api.Result,
): Api.Result {
    tracker.reset()
    var result = fetch()
    while (Disconnect.isDisconnected(result.code) && !tracker.exhausted) {
        val n = tracker.recordAttempt()
        onAttempt(n)
        delay(delayMs)
        result = fetch()
    }
    return result
}

/**
 * Dedicated full-screen disconnected state: shown whenever the app's last
 * fetch failed at the connection level. Makes clear the backend could not
 * be reached, that the user is still signed in, how many auto-retries were
 * attempted, and offers a Retry button (a tap restarts the 3-attempt cycle).
 */
@Composable
fun DisconnectedScreen(
    attempts: Int,
    retrying: Boolean,
    onRetry: () -> Unit,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "●",
            color = OfflineRed,
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Disconnected from server",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "The PoleX backend could not be reached. You are still signed in.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = if (retrying) {
                "Auto-retry $attempts of ${Disconnect.MAX_AUTO_ATTEMPTS}…"
            } else {
                "All ${Disconnect.MAX_AUTO_ATTEMPTS} auto-retries attempted."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry, enabled = !retrying) {
            Text("Retry")
        }
        onBack?.let { back ->
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = back) { Text("Back") }
        }
    }
}
