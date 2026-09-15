package com.polex.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM unit tests for the app/backend version comparison helpers (Versions.kt).
 * Covers: equal, app-lower, backend-lower, multi-part versions, and the
 * unreachable-backend case (null → no mismatch).
 */
class VersionsTest {

    // ---- compareVersions ----------------------------------------------------

    @Test
    fun equalVersionsCompareZero() {
        assertEquals(0, compareVersions("1.0", "1.0"))
        assertEquals(0, compareVersions("1.2.3", "1.2.3"))
    }

    @Test
    fun differentVersionsCompareByValue() {
        assertEquals(-1, compareVersions("1.0", "1.1"))
        assertEquals(1, compareVersions("2.0", "1.9"))
        assertEquals(-1, compareVersions("1.9", "2.0"))
    }

    @Test
    fun numericNotLexicographic() {
        // "1.10" > "1.9" numerically even though "1.10" < "1.9" as strings.
        assertEquals(1, compareVersions("1.10", "1.9"))
        assertEquals(-1, compareVersions("1.9", "1.10"))
    }

    @Test
    fun multiPartVersions() {
        assertEquals(1, compareVersions("2.0.1", "1.9.9"))
        assertEquals(-1, compareVersions("1.0.1", "1.0.2"))
        assertEquals(0, compareVersions("1.0", "1.0.0"))   // trailing zeros
        assertEquals(0, compareVersions("1", "1.0"))
        assertEquals(1, compareVersions("1.0.1", "1.0"))
    }

    // ---- outdatedSide ---------------------------------------------------------

    @Test
    fun equalVersionsNoMismatch() {
        assertNull(outdatedSide("1.0", "1.0"))
    }

    @Test
    fun appLowerHighlightsApp() {
        assertEquals("app", outdatedSide("1.0", "1.1"))
        assertEquals("app", outdatedSide("1.9", "2.0"))
    }

    @Test
    fun backendLowerHighlightsBackend() {
        assertEquals("backend", outdatedSide("1.1", "1.0"))
        assertEquals("backend", outdatedSide("2.0", "1.9.9"))
    }

    @Test
    fun unreachableBackendIsNotAMismatch() {
        assertNull(outdatedSide("1.0", null))
        assertNull(outdatedSide("9.9", null))
    }

    // ---- mismatchExplanation ----------------------------------------------------

    @Test
    fun explanationsMatchSides() {
        assertEquals("App is older than the backend — update the app.", mismatchExplanation("app"))
        assertEquals("Backend is older than the app — update the backend.", mismatchExplanation("backend"))
        assertNull(mismatchExplanation(null))
    }
}
