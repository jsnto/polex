package com.polex.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the disconnected-from-server retry logic (Disconnect.kt).
 * No device/emulator needed: RetryTracker and runFetchCycle are pure Kotlin.
 */
class DisconnectTest {

    // ---- RetryTracker -------------------------------------------------------

    @Test
    fun trackerCountsAttemptsUpToMax() {
        val t = RetryTracker()
        assertFalse(t.exhausted)
        repeat(Disconnect.MAX_AUTO_ATTEMPTS - 1) {
            t.recordAttempt()
            assertFalse(t.exhausted)
        }
        t.recordAttempt()
        assertEquals(Disconnect.MAX_AUTO_ATTEMPTS, t.attempts)
        assertTrue(t.exhausted)
    }

    @Test
    fun trackerResetStartsFreshCycle() {
        val t = RetryTracker()
        repeat(3) { t.recordAttempt() }
        assertTrue(t.exhausted)
        t.reset()
        assertEquals(0, t.attempts)
        assertFalse(t.exhausted)
    }

    // ---- runFetchCycle: retry-count logic ----------------------------------

    @Test
    fun exhaustedAfterThreeAutoRetries() = runTest {
        var calls = 0
        val tracker = RetryTracker()
        val result = runFetchCycle(tracker, delayMs = 200) {
            calls++
            Api.Result(-1, "no route to host")
        }
        assertEquals(-1, result.code)
        // initial failed fetch + 3 auto-retries
        assertEquals(4, calls)
        assertEquals(3, tracker.attempts)
        assertTrue(tracker.exhausted)
    }

    @Test
    fun stopsRetryingWhenBackendRecovers() = runTest {
        var calls = 0
        var reported = 0
        val tracker = RetryTracker()
        val result = runFetchCycle(tracker, delayMs = 200, onAttempt = { reported = it }) {
            calls++
            if (calls <= 2) Api.Result(-1, "connection refused") else Api.Result(200, "[]")
        }
        assertEquals(200, result.code)
        assertEquals(3, calls) // initial + 2 auto-retries, third succeeds
        assertEquals(2, tracker.attempts)
        assertFalse(tracker.exhausted)
        assertEquals(2, reported)
    }

    @Test
    fun stopsRetryingOnReal401() = runTest {
        var calls = 0
        val tracker = RetryTracker()
        val result = runFetchCycle(tracker, delayMs = 200) {
            calls++
            if (calls == 1) Api.Result(-1, "unreachable") else Api.Result(401, """{"ok":false}""")
        }
        // A 401 from a reachable backend is re-auth, not disconnection:
        // the cycle must stop instead of burning auto-retries.
        assertEquals(401, result.code)
        assertEquals(2, calls)
        assertEquals(1, tracker.attempts)
        assertFalse(tracker.exhausted)
    }

    @Test
    fun userRetryStartsFreshCycle() = runTest {
        val tracker = RetryTracker()
        var calls = 0
        val fetch: suspend () -> Api.Result = {
            calls++
            Api.Result(-1, "down")
        }
        assertEquals(-1, runFetchCycle(tracker, delayMs = 200, fetch = fetch).code)
        assertEquals(4, calls) // initial + 3 auto-retries
        assertTrue(tracker.exhausted)

        // User taps Retry: a fresh cycle with its own 3 auto-retries.
        calls = 0
        assertEquals(-1, runFetchCycle(tracker, delayMs = 200, fetch = fetch).code)
        assertEquals(4, calls)
        assertEquals(3, tracker.attempts)
        assertTrue(tracker.exhausted)
    }

    // ---- screen show/hide policy (when the disconnected screen appears) ---

    @Test
    fun screenNeverAppearsWhenAQuietRetryRecovers() = runTest {
        val tracker = RetryTracker()
        var visible = false
        var reported = 0
        var calls = 0
        // First fetch fails at the connection level; a silent auto-retry
        // recovers before the cycle exhausts.
        val result = runFetchCycle(tracker, delayMs = 200, onAttempt = { n ->
            // While the app is connected (screen not shown), mid-cycle
            // attempts must stay completely silent — no progress, no flip.
            if (visible) reported = n
        }) {
            calls++
            if (calls == 1) Api.Result(-1, "brief blip") else Api.Result(200, "[]")
        }
        assertEquals(200, result.code)
        assertEquals(2, calls) // initial + 1 auto-retry
        assertEquals(0, reported)
        visible = Disconnect.screenVisibleAfterCycle(result.code)
        assertFalse(visible)
    }

    @Test
    fun screenAppearsOnlyAfterAllSilentRetriesFail() = runTest {
        val tracker = RetryTracker()
        var visible = false
        var reported = 0
        var calls = 0
        // Every fetch in the cycle fails at the connection level.
        val result = runFetchCycle(tracker, delayMs = 200, onAttempt = { n ->
            if (visible) reported = n
        }) {
            calls++
            Api.Result(-1, "still down")
        }
        assertEquals(4, calls) // initial + 3 silent auto-retries
        assertEquals(0, reported) // nothing shown while the tests ran
        visible = Disconnect.screenVisibleAfterCycle(result.code)
        assertTrue(visible) // only after the cycle is exhausted
        assertEquals(Disconnect.MAX_AUTO_ATTEMPTS, tracker.attempts)
    }

    @Test
    fun screenClearsWhenManualRetryCycleRecovers() = runTest {
        val tracker = RetryTracker()
        // Cycle 1 (app connected): everything fails -> screen appears.
        var visible = runFetchCycle(tracker, delayMs = 200) {
            Api.Result(-1, "down")
        }.let { Disconnect.screenVisibleAfterCycle(it.code) }
        assertTrue(visible)

        // User taps Retry from the screen: a fresh cycle whose live progress
        // the screen DOES update (it is already visible)…
        var progress = 0
        runFetchCycle(tracker, delayMs = 200, onAttempt = { n ->
            if (visible) progress = n
        }) {
            when {
                progress == 0 -> Api.Result(-1, "still down")
                else -> Api.Result(200, "[]")
            }
        }
        assertTrue(progress > 0) // live "Auto-retry X of 3…" while on the screen

        // …and recovers: the screen clears.
        visible = runFetchCycle(tracker, delayMs = 200) {
            Api.Result(200, "[]")
        }.let { Disconnect.screenVisibleAfterCycle(it.code) }
        assertFalse(visible)
    }

    // ---- session handling: disconnection must not log the user out ---------

    @Test
    fun connectionFailureNeverTriggersReauth() {
        var token: String? = "secret-token"
        // Every connection-level failure in the retry cycle must leave the
        // stored token exactly as it was (no clearToken, no login redirect).
        repeat(Disconnect.MAX_AUTO_ATTEMPTS) {
            if (Disconnect.requiresReauth(-1)) token = null
        }
        assertEquals("secret-token", token)
        assertFalse(Disconnect.isDisconnected(401))
    }

    @Test
    fun real401TriggersReauthOnly() {
        assertTrue(Disconnect.requiresReauth(401))
        assertFalse(Disconnect.requiresReauth(200))
        assertFalse(Disconnect.requiresReauth(-1))
        assertFalse(Disconnect.requiresReauth(500))
    }
}
