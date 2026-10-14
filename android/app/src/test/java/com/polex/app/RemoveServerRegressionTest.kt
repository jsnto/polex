package com.polex.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regressions for the two real bugs fixed during on-device verification of
 * the moved remove-server action:
 *
 * [EditServerFreezeTest] — bug 1: the edit form silently morphed into a
 * new-server form when the server vanished (deleted elsewhere) while the
 * form was open — Save would then POST a duplicate and Remove no-opped.
 * The form must freeze its server entry at edit start: stay an edit form
 * (Danger zone still offered, Save still PUTs the id, Remove still DELETEs
 * the id) even after the backend no longer lists the server.
 *
 * [SettingsRemoveReauthTest] — the settings caller must route a genuine 401
 * mid-remove to re-auth (onAuthError = onLogout), exactly like the
 * detail-page caller, instead of swallowing the failure.
 *
 * Like EditServerRemoveTest, the screens under test are the production
 * code, driven through the Compose UI test API (real composition, real
 * clicks, real OkHttp calls) against a tiny local HTTP stand-in backend.
 */
@Config(sdk = [34], application = TestApplication::class)
@RunWith(RobolectricTestRunner::class)
abstract class RemoveServerStandInBase {

    @get:Rule
    val compose = createAndroidComposeRule<TestDetailActivity>()

    protected lateinit var socket: ServerSocket
    protected var base = ""
    /** One entry per request: "METHOD PATH". */
    protected val requests = CopyOnWriteArrayList<String>()
    /** Epoch millis of the most recent /api/status poll the stand-in served. */
    @Volatile protected var lastStatusPollAt = 0L
    /** When true, /api/status reports no servers (deleted elsewhere). */
    @Volatile protected var serverVanished = false
    /** HTTP status the stand-in gives for DELETE /api/servers/1. */
    @Volatile protected var deleteStatus = 404

    protected val statusJson = """
        [{"id":1,"name":"test-srv","ip":"192.168.1.50","mac":"AA:BB:CC:DD:EE:FF","port":22,
          "check":"tcp","sshUser":"","sshKeyPath":"","sshPassword":"","poweroffCommand":"",
          "online":true,"latencyMs":3,"lastChecked":"2025-01-01T00:00:00Z",
          "lastWolAt":"","lastShutdownAt":""}]
    """.trimIndent()

    @Before
    fun setUp() {
        serverVanished = false
        deleteStatus = 404
        requests.clear()
        socket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        base = "http://127.0.0.1:${socket.localPort}"
        Thread {
            while (!socket.isClosed) {
                val conn = try { socket.accept() } catch (e: Exception) { break }
                Thread { serve(conn) }.also { it.isDaemon = true }.start()
            }
        }.also { it.isDaemon = true; it.start() }

        val prefs = compose.activity.getSharedPreferences("polex", Context.MODE_PRIVATE)
        prefs.edit().putString("baseUrl", base).putString("authToken", "tok123").commit()
        Api.authToken = "tok123"
    }

    @After
    fun tearDown() {
        socket.close()
        Api.authToken = null
    }

    private fun serve(conn: Socket) {
        conn.use { s ->
            val in_ = s.getInputStream().bufferedReader()
            val line = in_.readLine() ?: return
            if (line.isBlank()) return
            val parts = line.split(" ")
            if (parts.size < 2) return
            val (method, path) = parts[0] to parts[1]
            while (true) {
                val h = in_.readLine() ?: break
                if (h.isEmpty()) break
            }
            requests.add("$method $path")
            val (code, body) = when {
                method == "GET" && path == "/api/status" -> {
                    lastStatusPollAt = System.currentTimeMillis()
                    200 to (if (serverVanished) "[]" else statusJson)
                }
                // The settings server list reads the configuration (same
                // fields; the extra status fields are ignored by
                // parseServerArray).
                method == "GET" && path == "/api/servers" ->
                    200 to (if (serverVanished) "[]" else statusJson)
                method == "DELETE" && path == "/api/servers/1" ->
                    if (deleteStatus == 200) 200 to """{"ok":true}"""
                    else deleteStatus to """{"error":"server not found","ok":false}"""
                method == "PUT" && path == "/api/servers/1" ->
                    404 to """{"error":"no such server: 1","ok":false}"""
                else -> 404 to """{"error":"unexpected: $method $path","ok":false}"""
            }
            val b = body.toByteArray()
            val out = s.getOutputStream()
            out.write(
                (
                    "HTTP/1.1 $code X\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${b.size}\r\nConnection: close\r\n\r\n"
                ).toByteArray()
            )
            out.write(b)
            out.flush()
        }
    }

    protected fun hasNode(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    protected fun hasNodeSubstring(sub: String): Boolean =
        compose.onAllNodesWithText(sub, substring = true).fetchSemanticsNodes().isNotEmpty()

    protected fun waitFor(what: String, deadlineMs: Long = 30_000, block: () -> Unit) {
        val end = System.currentTimeMillis() + deadlineMs
        var last: Throwable? = null
        while (true) {
            try {
                block()
                return
            } catch (e: Throwable) {
                last = e
            }
            if (System.currentTimeMillis() >= end) {
                throw AssertionError("timed out waiting for: $what", last)
            }
            Thread.sleep(100)
        }
    }

    /** Clicks [label] (retrying while the condition is unmet; the page's
     *  15 s auto-refresh recomposes the screen and can drop a click). */
    protected fun clickUntil(label: String, what: String, condition: () -> Boolean) {
        var lastClickAt = 0L
        waitFor(what) {
            if (!condition() && System.currentTimeMillis() - lastClickAt >= 700L) {
                compose.onNodeWithText(label).performClick()
                lastClickAt = System.currentTimeMillis()
            }
            assertTrue("timed out: $what", condition())
        }
    }
}

/**
 * Bug 1: the form freezes its server entry when the detail page opens the
 * edit form — even if the server vanishes from the backend while the form is
 * open (deleted from another client), the form must stay an EDIT form.
 */
class EditServerFreezeTest : RemoveServerStandInBase() {

    @Test
    fun formStaysAnEditFormWhenServerVanishesWhileOpen() {
        var back = 0
        compose.setContent {
            ServerDetailScreen(serverId = 1, onBack = { back++ }, onAuthError = {})
        }
        compose.onNodeWithText("Settings").assertExists()
        compose.onNodeWithText("Settings").performScrollTo()
        compose.onNodeWithText("Edit settings").performScrollTo()

        // Open the form (entry frozen at this moment).
        clickUntil(
            "Edit settings", "edit form opens (Danger zone reachable)",
            condition = { hasNode("Danger zone") }
        )
        // TopAppBar title is pinned (not inside the scrollable content), so
        // no scroll; the form body fields below are scroll-safe.
        compose.onNodeWithText("Edit server").assertIsDisplayed()
        compose.onNodeWithText("test-srv").assertExists() // pre-filled name
        compose.onNodeWithText("Save changes").performScrollTo()
        compose.onNodeWithText("Save changes").assertIsDisplayed() // edit, not "Add server"

        // The server is deleted elsewhere while the form is open.
        val vanishedAt = System.currentTimeMillis()
        serverVanished = true

        // Wait until the page's 15 s auto-refresh has re-polled with the
        // server gone (a status poll strictly after the flip). The compose
        // test rule drives the UI on a virtual main clock, so the 15 s
        // coroutine delay only comes due when the test advances it.
        var advancedMs = 0L
        waitFor("auto-refresh re-polls after the server vanished", deadlineMs = 60_000) {
            if (advancedMs < 20_000L) {
                compose.mainClock.advanceTimeBy(4_000)
                advancedMs += 4_000
            }
            Thread.sleep(150) // let the OkHttp round-trip + continuation land
            assertTrue(
                "no /api/status poll yet after the flip (lastStatusPollAt=$lastStatusPollAt " +
                    "vanishedAt=$vanishedAt requests=${requests.toList()})",
                lastStatusPollAt > vanishedAt
            )
        }

        // The form must still be the edit form: frozen entry, Danger zone
        // still offered, save still an edit-save.
        compose.onNodeWithText("Edit server").assertIsDisplayed()
        compose.onNodeWithText("Danger zone").performScrollTo()
        compose.onNodeWithText("Danger zone").assertIsDisplayed()
        compose.onNodeWithText("Remove server").performScrollTo()
        compose.onNodeWithText("Remove server").assertIsDisplayed()
        compose.onNodeWithText("test-srv").assertExists()
        compose.onNodeWithText("Save changes").performScrollTo()
        compose.onNodeWithText("Save changes").assertIsDisplayed()

        // Save must PUT the frozen id (and fail against the vanished server),
        // not POST a brand-new duplicate.
        clickUntil(
            "Save changes", "Save PUTs the frozen id",
            condition = { requests.contains("PUT /api/servers/1") }
        )
        waitFor("save failure surfaces inline") {
            assertTrue("no inline save error", hasNodeSubstring("Save failed:"))
        }
        assertEquals(
            "Save must not create a duplicate (no POST /api/servers)",
            false,
            requests.contains("POST /api/servers")
        )

        // And Remove must DELETE the frozen id (and surface the failure as a
        // dialog with the backend's error field as plain text).
        clickUntil(
            "Remove server", "Delete server? dialog appears",
            condition = { hasNode("Delete server?") }
        )
        clickUntil(
            "Delete", "remove-failed dialog appears",
            condition = { hasNodeSubstring("The server could not be removed: server not found") }
        )
        assertEquals(
            "a failed remove must stay on the form (no navigation to the dashboard)",
            0, back
        )
    }
}

/**
 * The settings-list caller must route a genuine 401 mid-remove to re-auth:
 * Settings → Servers → edit form → Danger zone → Remove → Delete → 401 →
 * onLogout (back to login), exactly like the detail-page caller.
 */
class SettingsRemoveReauthTest : RemoveServerStandInBase() {

    @Test
    fun remove401FromSettingsEditFormRoutesToReauth() {
        deleteStatus = 401
        var back = 0
        var logout = 0
        compose.setContent {
            SettingsScreen(onBack = { back++ }, onLogout = { logout++ })
        }
        // Menu pane → Servers (click retries: the pane's version fetch
        // recomposes and can drop a click).
        clickUntil(
            "Servers", "Servers pane shows the server row",
            condition = { hasNode("test-srv") }
        )
        // Server list row → its Edit action (same retry discipline).
        var lastEditClickAt = 0L
        waitFor("edit form opens", deadlineMs = 30_000) {
            if (!hasNode("Edit server") &&
                System.currentTimeMillis() - lastEditClickAt >= 700L
            ) {
                compose.onNodeWithContentDescription("Edit test-srv").performClick()
                lastEditClickAt = System.currentTimeMillis()
            }
            assertTrue("edit form did not open", hasNode("Edit server"))
        }
        compose.onNodeWithText("Danger zone").performScrollTo()
        compose.onNodeWithText("Remove server").performScrollTo()
        compose.onNodeWithText("Remove server").performClick()
        clickUntil(
            "Remove server", "Delete server? dialog appears",
            condition = { hasNode("Delete server?") }
        )
        clickUntil(
            "Delete", "onLogout (back to login) after the 401",
            condition = { logout == 1 }
        )
        assertEquals(0, back)
        assertTrue("DELETE reached the backend", requests.contains("DELETE /api/servers/1"))
    }
}
