package com.polex.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
 * Drives the real [ServerDetailScreen] — the "Remove server" button in the
 * Settings card and its "Delete server?" confirm dialog — through the
 * Compose UI test API (real composition, real clicks, real OkHttp calls),
 * with the backend stood in by a tiny local HTTP server that records every
 * request it receives.
 *
 * The screen code under test is the production code: the button, the dialog,
 * [Api.delete], and the success/401/error branches of `removeServer`.
 * (The backend itself is exercised separately against the real Go binary.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerDetailRemoveTest {

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
     *  the endpoints the [ServerDetailScreen] under test calls. */
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

    private fun composeScreen(onBack: () -> Unit, onAuthError: () -> Unit) {
        compose.setContent {
            ServerDetailScreen(serverId = 1, onBack = onBack, onAuthError = onAuthError)
        }
        // The screen scrolls; in the small test window the Settings card
        // starts below the fold, so wait for existence, then scroll into view.
        waitFor("status card with the Remove server button") {
            compose.onNodeWithText("Remove server").assertExists()
        }
        compose.onNodeWithText("Remove server").performScrollTo()
        compose.onNodeWithText("Remove server").assertIsDisplayed()
    }

    private fun openRemoveDialog() {
        compose.onNodeWithText("Remove server").performClick()
        waitFor("Delete server? dialog") {
            compose.onNodeWithText("Delete server?").assertIsDisplayed()
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
    fun removeButtonShownInSettingsCardBesideEditSettings() {
        var back = 0
        composeScreen(onBack = { back++ }, onAuthError = {})
        compose.onNodeWithText("Settings").performScrollTo()
        compose.onNodeWithText("Settings").assertIsDisplayed()
        compose.onNodeWithText("Edit settings").performScrollTo()
        compose.onNodeWithText("Edit settings").assertIsDisplayed()
        compose.onNodeWithText("Remove server").assertIsDisplayed()
        assertEquals(0, back)
    }

    @Test
    fun confirmRemovesServerAndReturnsToDashboard() {
        var back = 0
        composeScreen(onBack = { back++ }, onAuthError = {})
        openRemoveDialog()
        // Dialog mirrors the server list's wording, with this server's name + IP.
        compose
            .onNodeWithText("\"test-srv\" (192.168.1.50) will be removed.")
            .assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        waitFor("DELETE /api/servers/1 to reach the backend") {
            assertTrue("no DELETE recorded yet", deletes.isNotEmpty())
        }
        assertEquals("DELETE /api/servers/1 auth=Bearer tok123", deletes.first())
        waitFor("return to the dashboard (onBack)") { assertEquals(1, back) }
    }

    @Test
    fun cancelLeavesTheServerInPlace() {
        var back = 0
        composeScreen(onBack = { back++ }, onAuthError = {})
        openRemoveDialog()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Delete server?").assertDoesNotExist()
        // Give a (wrong) async DELETE a chance to happen, then prove it didn't.
        Thread.sleep(500)
        assertTrue("cancel must not send a DELETE", deletes.isEmpty())
        assertEquals(0, back)
        compose.onNodeWithText("Remove server").performScrollTo()
        compose.onNodeWithText("Remove server").assertIsDisplayed()
    }

    @Test
    fun failedDeleteShowsInlineErrorAndStaysOnPage() {
        deleteMode = DeleteMode.NOT_FOUND
        var back = 0
        composeScreen(onBack = { back++ }, onAuthError = {})
        openRemoveDialog()
        compose.onNodeWithText("Delete").performClick()
        waitFor("inline 'Remove failed' error") {
            compose.onNodeWithText("Remove failed:", substring = true).assertIsDisplayed()
        }
        assertEquals(0, back)
        // Still on the detail page: the button is still there, dialog gone.
        compose.onNodeWithText("Remove server").performScrollTo()
        compose.onNodeWithText("Remove server").assertIsDisplayed()
        compose.onNodeWithText("Delete server?").assertDoesNotExist()
    }

    @Test
    fun rejectedSessionRoutesToReauth() {
        deleteMode = DeleteMode.UNAUTHORIZED
        var authError = 0
        composeScreen(onBack = {}, onAuthError = { authError++ })
        openRemoveDialog()
        compose.onNodeWithText("Delete").performClick()
        waitFor("onAuthError (back to login)") { assertEquals(1, authError) }
    }
}
