package com.polex.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * Drives the real [ServerDetailScreen] → [EditServerScreen] surface — the
 * "Remove server" button in the edit form's Danger zone section and its
 * "Delete server?" confirm dialog — through the Compose UI test API (real
 * composition, real clicks, real OkHttp calls), with the backend stood in
 * by a tiny local HTTP server that records every request it receives.
 *
 * The screen code under test is the production code: the Danger zone, the
 * dialog, [Api.delete], and the success/401/error branches of
 * `removeServer`. (The backend itself is exercised separately against the
 * real Go binary.)
 *
 * [TestApplication] (via `@Config(application=...)`) registers the
 * test-only [TestDetailActivity] host with Robolectric's ShadowPackageManager;
 * the activity class itself lives in the unit-test source set and is not in
 * the production manifest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class EditServerRemoveTest {

    @get:Rule
    val compose = createAndroidComposeRule<TestDetailActivity>()

    private var serverThread: Thread? = null
    private lateinit var socket: ServerSocket
    private var base = ""
    // One entry per DELETE request: "METHOD PATH auth=<header>".
    private val deletes = CopyOnWriteArrayList<String>()

    /** Which response the stand-in backend gives for the DELETE. */
    private enum class DeleteMode { SUCCESS, NOT_FOUND, UNAUTHORIZED }

    private var deleteMode = DeleteMode.SUCCESS

    private val statusJson = """
        [{"id":1,"name":"test-srv","ip":"192.168.1.50","mac":"AA:BB:CC:DD:EE:FF","port":22,
          "check":"tcp","sshUser":"","sshKeyPath":"","sshPassword":"","poweroffCommand":"",
          "online":true,"latencyMs":3,"lastChecked":"2025-01-01T00:00:00Z",
          "lastWolAt":"","lastShutdownAt":""}]
    """.trimIndent()

    @Before
    fun setUp() {
        deleteMode = DeleteMode.SUCCESS
        deletes.clear()
        socket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        base = "http://127.0.0.1:${socket.localPort}"
        serverThread = Thread {
            while (!socket.isClosed) {
                val conn = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                Thread { serve(conn) }.also { it.isDaemon = true }.start()
            }
        }.also { it.isDaemon = true; it.start() }

        // Point the app's shared prefs + the Api client at the stand-in backend.
        val prefs = compose.activity.getSharedPreferences("polex", Context.MODE_PRIVATE)
        prefs.edit().putString("baseUrl", base).putString("authToken", "tok123").commit()
        Api.authToken = "tok123"
    }

    @After
    fun tearDown() {
        socket.close()
        Api.authToken = null
    }

    /** Serves one connection: reads the request line + headers, answers with
     *  the endpoints the screen under test calls. */
    private fun serve(conn: Socket) {
        conn.use { s ->
            val in_ = s.getInputStream().bufferedReader()
            val line = in_.readLine() ?: return
            if (line.isBlank()) return
            val parts = line.split(" ")
            if (parts.size < 2) return
            val (method, path) = parts[0] to parts[1]
            var auth = ""
            while (true) {
                val h = in_.readLine() ?: break
                if (h.isEmpty()) break
                if (h.startsWith("Authorization:", ignoreCase = true)) {
                    auth = h.substringAfter(":").trim()
                }
            }
            val (code, body) = when {
                method == "GET" && path == "/api/status" -> 200 to statusJson
                method == "DELETE" && path == "/api/servers/1" -> {
                    deletes.add("$method $path auth=$auth")
                    when (deleteMode) {
                        DeleteMode.SUCCESS -> 200 to """{"ok":true}"""
                        DeleteMode.NOT_FOUND -> 404 to """{"error":"server not found"}"""
                        DeleteMode.UNAUTHORIZED -> 401 to """{"error":"unauthorized","ok":false}"""
                    }
                }
                else -> 404 to """{"error":"unexpected: $method $path"}"""
            }
            val b = body.toByteArray()
            val out = s.getOutputStream()
            val header = (
                "HTTP/1.1 $code OK\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${b.size}\r\nConnection: close\r\n\r\n"
            ).toByteArray()
            out.write(header)
            out.write(b)
            out.flush()
        }
    }

    private fun composeDetail(onBack: () -> Unit, onAuthError: () -> Unit) {
        compose.setContent {
            ServerDetailScreen(serverId = 1, onBack = onBack, onAuthError = onAuthError)
        }
        // The screen scrolls; in the small test window the Settings card
        // starts below the fold, so wait for existence, then scroll into view.
        waitFor("detail page Settings card") {
            compose.onNodeWithText("Settings").assertExists()
        }
        compose.onNodeWithText("Settings").performScrollTo()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    /** True when at least one node with exactly [text] is composed (a
     *  non-throwing existence check for the retry loops below). */
    private fun hasNode(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun openEditForm() {
        compose.onNodeWithText("Edit settings").performScrollTo()
        // Click retries: the detail page's 15 s auto-refresh recomposes the
        // screen, and a click landing mid-recomposition can be dropped.
        var opened = false
        waitFor("edit form (Danger zone reachable)") {
            opened = hasNode("Danger zone")
            if (!opened) compose.onNodeWithText("Edit settings").performClick()
            assertTrue("edit form did not open", opened)
        }
        compose.onNodeWithText("Remove server").performScrollTo()
        compose.onNodeWithText("Remove server").assertIsDisplayed()
    }

    private fun openRemoveDialog() {
        var shown = false
        waitFor("Delete server? dialog") {
            shown = hasNode("Delete server?")
            if (!shown) compose.onNodeWithText("Remove server").performClick()
            assertTrue("dialog did not appear", shown)
        }
        compose.onNodeWithText("Delete server?").assertIsDisplayed()
    }

    /** Clicks the dialog's [label] button, retrying while the dialog is still
     *  open (a dropped click leaves it open; once the click lands the dialog
     *  closes synchronously, so a second click never double-fires). Re-clicks
     *  are spaced 500 ms apart: during the dialog's exit animation the node
     *  still exists while the hit target behind it has already changed, so a
     *  rapid second click could land on the form. */
    private fun clickDialogButton(label: String, what: String) {
        var dismissed = false
        var lastClickAt = 0L
        waitFor(what) {
            val now = System.currentTimeMillis()
            if (hasNode(label) && now - lastClickAt >= 500L) {
                compose.onNodeWithText(label).performClick()
                lastClickAt = System.currentTimeMillis()
            }
            dismissed = !hasNode("Delete server?")
            assertTrue("dialog still open after $label", dismissed)
        }
    }

    /** Polls [block] until it stops throwing (real-time; the screen's HTTP
     *  calls and coroutine callbacks take a few hundred ms on the JVM).
     *  Catches Throwable: JUnit assertions throw AssertionError, an Error. */
    private fun waitFor(what: String, deadlineMs: Long = 30_000, block: () -> Unit) {
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

    @Test
    fun detailPageNoLongerCarriesTheRemoveButton() {
        var back = 0
        composeDetail(onBack = { back++ }, onAuthError = {})
        compose.onNodeWithText("Edit settings").performScrollTo()
        compose.onNodeWithText("Edit settings").assertIsDisplayed()
        // The button moved out of the detail page into the edit form.
        compose.onNodeWithText("Remove server").assertDoesNotExist()
        assertEquals(0, back)
    }

    @Test
    fun removeButtonShownInDangerZoneOfEditForm() {
        var back = 0
        composeDetail(onBack = { back++ }, onAuthError = {})
        openEditForm()
        compose.onNodeWithText("Danger zone").performScrollTo()
        compose.onNodeWithText("Danger zone").assertIsDisplayed()
        compose.onNodeWithText("Remove server").assertIsDisplayed()
        assertEquals(0, back)
    }

    @Test
    fun confirmRemovesServerAndReturnsToDashboard() {
        var back = 0
        composeDetail(onBack = { back++ }, onAuthError = {})
        openEditForm()
        openRemoveDialog()
        // Dialog mirrors the server list's wording, with this server's name + IP.
        compose
            .onNodeWithText("\"test-srv\" (192.168.1.50) will be removed.")
            .assertIsDisplayed()
        clickDialogButton("Delete", "DELETE accepted (dialog closed)")
        waitFor("DELETE /api/servers/1 to reach the backend") {
            assertTrue("no DELETE recorded yet", deletes.isNotEmpty())
        }
        assertEquals("DELETE /api/servers/1 auth=Bearer tok123", deletes.first())
        waitFor("return to the dashboard (onDeleted → onBack)") { assertEquals(1, back) }
    }

    @Test
    fun cancelLeavesTheServerInPlace() {
        var back = 0
        composeDetail(onBack = { back++ }, onAuthError = {})
        openEditForm()
        openRemoveDialog()
        clickDialogButton("Cancel", "dialog dismissed (Cancel)")
        compose.onNodeWithText("Delete server?").assertDoesNotExist()
        // Give a (wrong) async DELETE a chance to happen, then prove it didn't.
        Thread.sleep(500)
        assertTrue("cancel must not send a DELETE", deletes.isEmpty())
        assertEquals(0, back)
        compose.onNodeWithText("Remove server").performScrollTo()
        compose.onNodeWithText("Remove server").assertIsDisplayed()
    }

    @Test
    fun failedDeleteShowsInlineErrorAndStaysOnForm() {
        deleteMode = DeleteMode.NOT_FOUND
        var back = 0
        composeDetail(onBack = { back++ }, onAuthError = {})
        openEditForm()
        openRemoveDialog()
        clickDialogButton("Delete", "DELETE accepted (dialog closed)")
        // The failure is also surfaced as an error dialog (on device the
        // inline text below did not reliably render in the form).
        waitFor("error dialog appears") {
            compose.onNodeWithText("Remove failed").assertIsDisplayed()
        }
        waitFor("error dialog dismissed (OK keeps the inline message)") {
            compose.onNodeWithText("OK").performClick()
            compose.onNodeWithText("Remove failed").assertDoesNotExist()
        }
        // The inline error renders at the bottom of the (tall) form — below
        // the fold in the small test window — so scroll it into view.
        compose.onNodeWithText("Remove failed:", substring = true).performScrollTo()
        compose.onNodeWithText("Remove failed:", substring = true).assertIsDisplayed()
        assertEquals(0, back)
        // Still on the edit form: the button is still there, dialog gone.
        compose.onNodeWithText("Remove server").performScrollTo()
        compose.onNodeWithText("Remove server").assertIsDisplayed()
        compose.onNodeWithText("Delete server?").assertDoesNotExist()
    }

    @Test
    fun rejectedSessionRoutesToReauth() {
        deleteMode = DeleteMode.UNAUTHORIZED
        var authError = 0
        composeDetail(onBack = {}, onAuthError = { authError++ })
        openEditForm()
        openRemoveDialog()
        clickDialogButton("Delete", "DELETE accepted (dialog closed)")
        waitFor("onAuthError (back to login)") { assertEquals(1, authError) }
    }
}
