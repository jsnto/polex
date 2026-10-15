package com.polex.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the connection supervisor (ConnStatus.kt): the state
 * machine (first-check-pending → connected/disconnected, failure→recovery
 * transitions), the backoff cadence between checks while disconnected, and
 * the indicator/gray-out state derivation. No device needed: ConnSupervisor
 * and the ConnStatus derivations are pure Kotlin.
 */
class ConnSupervisorTest {

    // ---- first-check-pending -------------------------------------------------

    @Test
    fun freshSupervisorIsChecking() {
        val s = ConnSupervisor()
        assertEquals(ConnStatus.CHECKING, s.status)
        assertEquals("Backend connection: checking", s.status.contentDescription())
        assertFalse(s.status.listGrayedOut())
        // No backoff before the first check — the caller uses its poll interval.
        assertEquals(0L, s.nextCheckDelayMs())
        assertEquals(0L, s.lastCheckAt)
        assertEquals(0, s.consecutiveFailures)
    }

    // ---- state transitions ----------------------------------------------------

    @Test
    fun successMakesConnected() {
        val s = ConnSupervisor()
        assertEquals(ConnStatus.CONNECTED, s.recordOutcome(200, now = 1_000L))
        assertEquals("Backend connection: valid", s.status.contentDescription())
        assertFalse(s.status.listGrayedOut())
        assertEquals(0L, s.nextCheckDelayMs())
        assertEquals(1_000L, s.lastCheckAt)
        assertEquals(0, s.consecutiveFailures)
    }

    @Test
    fun connectionFailureMakesDisconnected() {
        val s = ConnSupervisor()
        assertEquals(ConnStatus.DISCONNECTED, s.recordOutcome(-1, now = 1_000L))
        assertEquals("Backend connection: last check failed", s.status.contentDescription())
        assertTrue(s.status.listGrayedOut())
        assertEquals(ConnSupervisor.INITIAL_RETRY_BACKOFF_MS, s.nextCheckDelayMs())
        assertEquals(1, s.consecutiveFailures)
    }

    @Test
    fun reachableErrorCodesKeepConnected() {
        // A reachable backend that answers with an error (or rejects the
        // token) is still "connected": the light stays green.
        listOf(401, 404, 500, 503).forEach { code ->
            assertEquals(ConnStatus.CONNECTED, ConnSupervisor().recordOutcome(code))
        }
        // …including one that was disconnected before the error.
        val s = ConnSupervisor()
        s.recordOutcome(-1)
        assertEquals(ConnStatus.CONNECTED, s.recordOutcome(500))
        assertEquals(0, s.consecutiveFailures)
        assertEquals(0L, s.nextCheckDelayMs())
    }

    @Test
    fun failureToRecoverySequence() {
        val s = ConnSupervisor()
        val outcomes = listOf(-1, -1, 200, 200, -1, 200)
        val expected = listOf(
            ConnStatus.DISCONNECTED,
            ConnStatus.DISCONNECTED,
            ConnStatus.CONNECTED,
            ConnStatus.CONNECTED,
            ConnStatus.DISCONNECTED,
            ConnStatus.CONNECTED,
        )
        outcomes.forEachIndexed { i, code ->
            assertEquals(expected[i], s.recordOutcome(code, now = (i + 1) * 1_000L))
        }
        // lastCheckAt follows the most recent finished check.
        assertEquals(6_000L, s.lastCheckAt)
    }

    // ---- backoff cadence ------------------------------------------------------

    @Test
    fun backoffDoublesPerFailureAndCaps() {
        val s = ConnSupervisor()
        s.recordOutcome(-1)
        assertEquals(10_000L, s.nextCheckDelayMs())
        s.recordOutcome(-1)
        assertEquals(20_000L, s.nextCheckDelayMs())
        s.recordOutcome(-1)
        assertEquals(40_000L, s.nextCheckDelayMs())
        s.recordOutcome(-1)
        assertEquals(60_000L, s.nextCheckDelayMs()) // 80 s would exceed the cap
        s.recordOutcome(-1)
        assertEquals(60_000L, s.nextCheckDelayMs()) // stays capped
        s.recordOutcome(-1)
        assertEquals(60_000L, s.nextCheckDelayMs())
    }

    @Test
    fun recoveryResetsBackoffToInitial() {
        val s = ConnSupervisor()
        repeat(3) { s.recordOutcome(-1) }
        assertEquals(40_000L, s.nextCheckDelayMs())
        s.recordOutcome(200) // automatic recovery
        assertEquals(ConnStatus.CONNECTED, s.status)
        assertEquals(0L, s.nextCheckDelayMs()) // back to the normal poll cadence
        s.recordOutcome(-1)
        assertEquals(ConnSupervisor.INITIAL_RETRY_BACKOFF_MS, s.nextCheckDelayMs()) // fresh backoff
    }

    @Test
    fun supervisorIsFedByRealFetchCycles() = runTest {
        // The supervisor consumes the final result of each existing fetch
        // cycle (runFetchCycle) — no parallel network stack of its own.
        val s = ConnSupervisor()
        var down = true
        suspend fun cycle(): Api.Result = runFetchCycle(RetryTracker(), delayMs = 1) {
            Api.Result(if (down) -1 else 200, "[]")
        }
        s.recordOutcome(cycle().code)
        assertEquals(ConnStatus.DISCONNECTED, s.status)
        down = false
        s.recordOutcome(cycle().code)
        assertEquals(ConnStatus.CONNECTED, s.status)
        assertEquals(0L, s.nextCheckDelayMs())
    }

    // ---- indicator / gray-out derivation --------------------------------------

    @Test
    fun grayOutOnlyWhenDisconnected() {
        assertEquals(false, ConnStatus.CHECKING.listGrayedOut())
        assertEquals(false, ConnStatus.CONNECTED.listGrayedOut())
        assertEquals(true, ConnStatus.DISCONNECTED.listGrayedOut())
    }

    @Test
    fun contentDescriptionsAreDistinctPerState() {
        val descs = ConnStatus.entries.map { it.contentDescription() }
        assertEquals(3, descs.toSet().size)
        assertEquals("Backend connection: checking", ConnStatus.CHECKING.contentDescription())
        assertEquals("Backend connection: valid", ConnStatus.CONNECTED.contentDescription())
        assertEquals("Backend connection: last check failed", ConnStatus.DISCONNECTED.contentDescription())
    }

    @Test
    fun indicatorColorFollowsState() {
        assertEquals(ConnStatus.CHECKING_GRAY.value, ConnStatus.CHECKING.indicatorColor().value)
        assertEquals(OnlineGreen.value, ConnStatus.CONNECTED.indicatorColor().value)
        assertEquals(OfflineRed.value, ConnStatus.DISCONNECTED.indicatorColor().value)
        // The neutral "checking" color must be distinct from both terminal states.
        assertNotEquals(OnlineGreen.value, ConnStatus.CHECKING.indicatorColor().value)
        assertNotEquals(OfflineRed.value, ConnStatus.CHECKING.indicatorColor().value)
    }
}
