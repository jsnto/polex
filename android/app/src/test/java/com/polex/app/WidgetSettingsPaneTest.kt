package com.polex.app

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The settings screen's new Widget pane (Settings → Widget), driven
 * through the Compose UI test API — real composition, real clicks, real
 * OkHttp calls — with the backend stood in by a tiny local HTTP server
 * (the same pattern as EditServerRemoveTest):
 *
 *  - the pane is reachable from the settings menu and lists the LIVE
 *    server inventory (GET /api/servers) as a multi-select, all checked
 *    by default (the "show all" default);
 *  - the layout-mode switch (two-column grid, default ON) persists to
 *    [Prefs.widgetColumns] — 2 on, 1 off — and the checkbox/All/None
 *    edits persist to [Prefs.widgetSelectedServerIds]: an explicit set
 *    once the user touches a checkbox, an empty set for "None" (a real
 *    "show nothing" choice), and the UNSET state for "All" (the show-all
 *    default — `null`, distinct from the empty set), while the buttons
 *    stay disabled until the live server list has loaded;
 *  - every change triggers the immediate widget refresh path (a status
 *    fetch the stand-in records, followed by the widget re-render).
 *
 * The widget's own render application of these settings is covered by
 * [WidgetContentTest] (the pure logic) and the on-device verification.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class WidgetSettingsPaneTest {

    @get:Rule
    val compose = createAndroidComposeRule<TestDetailActivity>()

    private var serverThread: Thread? = null
    private lateinit var socket: ServerSocket
    private var base = ""
    /** One entry per request: "METHOD PATH". */
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()

    private val serversJson = """
        [{"id":1,"name":"srv-a","ip":"10.0.0.11","mac":"","port":22,"check":"tcp",
          "sshUser":"","sshKeyPath":"","sshPassword":"","poweroffCommand":""},
         {"id":2,"name":"srv-b","ip":"10.0.0.12","mac":"","port":22,"check":"ping",
          "sshUser":"","sshKeyPath":"","sshPassword":"","poweroffCommand":""}]
    """.trimIndent()

    private val statusJson = """
        [{"id":1,"name":"srv-a","ip":"10.0.0.11","mac":"","port":22,"check":"tcp",
          "sshUser":"","sshKeyPath":"","sshPassword":"","poweroffCommand":"",
          "online":true,"latencyMs":3,"lastChecked":"2025-01-01T00:00:00Z",
          "lastWolAt":"","lastShutdownAt":""},
         {"id":2,"name":"srv-b","ip":"10.0.0.12","mac":"","port":22,"check":"ping",
          "sshUser":"","sshKeyPath":"","sshPassword":"","poweroffCommand":"",
          "online":false,"latencyMs":0,"lastChecked":"2025-01-01T00:00:00Z",
          "lastWolAt":"","lastShutdownAt":""}]
    """.trimIndent()

    /** /api/servers response override for the failure tests (null = 200 + [serversJson]). */
    @Volatile
    private var serverListResponse: Pair<Int, String>? = null

    @Before
    fun setUp() {
        requests.clear()
        serverListResponse = null
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
            requests.add("$method $path")
            while (true) {
                val h = in_.readLine() ?: break
                if (h.isEmpty()) break
            }
            val (code, body) = when {
                method == "GET" && path == "/api/servers" -> serverListResponse ?: (200 to serversJson)
                method == "GET" && path == "/api/status" -> 200 to statusJson
                method == "GET" && path == "/api/version" -> 200 to """{"version":"1.2"}"""
                else -> 404 to """{"error":"unexpected: $method $path"}"""
            }
            val b = body.toByteArray()
            val out = s.getOutputStream()
            out.write(
                (
                    "HTTP/1.1 $code OK\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${b.size}\r\nConnection: close\r\n\r\n"
                ).toByteArray()
            )
            out.write(b)
            out.flush()
        }
    }

    /** Opens Settings → Widget and waits for the live server list to load. */
    private fun openWidgetPane() {
        compose.setContent { SettingsScreen(onBack = {}, onLogout = {}) }
        waitFor("settings menu with the Widget entry") {
            compose.onNodeWithText("Widget", useUnmergedTree = false).assertExists()
        }
        compose.onNodeWithText("Widget").performClick()
        waitFor("Widget pane listed the live servers") {
            compose.onNodeWithText("srv-a").assertIsDisplayed()
            compose.onNodeWithText("srv-b").assertIsDisplayed()
        }
    }

    private fun ctx(): Context = compose.activity

    private fun hasNode(text: String): Boolean =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    /** Polls [block] until it stops throwing (real-time; the pane's HTTP
     *  calls and coroutine callbacks take a few hundred ms on the JVM). */
    private fun waitFor(what: String, deadlineMs: Long = 30_000, block: () -> Unit) {
        val end = System.currentTimeMillis() + deadlineMs
        while (true) {
            try {
                block()
                return
            } catch (e: Throwable) {
                // The timeout check lives inside the catch: a failed attempt
                // at or past the deadline throws with that attempt's error.
                if (System.currentTimeMillis() >= end) {
                    throw AssertionError("timed out waiting for: $what", e)
                }
            }
            Thread.sleep(100)
        }
    }

    @Test
    fun paneOffersLayoutModeAndTheLiveServerMultiSelect() {
        openWidgetPane()
        // Defaults: two columns (switch on), all servers selected. The
        // M3 checkbox exposes its state as ToggleableState (On/Off), so the
        // checked assertion is assertIsOn (assertIsSelected matches the
        // Selected property the M3 checkbox does not set).
        compose.onNodeWithContentDescription("Two-column grid").assertIsDisplayed()
        compose.onNodeWithContentDescription("Two-column grid").assertIsOn()
        compose.onNodeWithContentDescription("Show srv-a in widget").assertIsOn()
        compose.onNodeWithContentDescription("Show srv-b in widget").assertIsOn()
        assertEquals("default: unset selection (show all)", null, Prefs.widgetSelectedServerIds(ctx()))
        assertEquals(WIDGET_COLUMNS_2, Prefs.widgetColumns(ctx()))
    }

    @Test
    fun layoutModeSwitchPersistsOneAndTwoColumns() {
        openWidgetPane()
        compose.onNodeWithContentDescription("Two-column grid").performClick()
        waitFor("one column persisted") {
            assertEquals(WIDGET_COLUMNS_1, Prefs.widgetColumns(ctx()))
        }
        compose.onNodeWithContentDescription("Two-column grid").performClick()
        waitFor("two columns persisted") {
            assertEquals(WIDGET_COLUMNS_2, Prefs.widgetColumns(ctx()))
        }
    }

    @Test
    fun uncheckingAserverPersistsAnExplicitSelection() {
        openWidgetPane()
        compose.onNodeWithContentDescription("Show srv-a in widget").performClick()
        waitFor("selection persisted as the explicit remaining set") {
            assertEquals(setOf(2), Prefs.widgetSelectedServerIds(ctx()))
        }
        compose.onNodeWithContentDescription("Show srv-b in widget").performClick()
        waitFor("unchecking the last server stores an EMPTY set (show nothing)") {
            assertEquals(emptySet<Int>(), Prefs.widgetSelectedServerIds(ctx()))
        }
        // Re-checking restores.
        compose.onNodeWithContentDescription("Show srv-b in widget").performClick()
        waitFor("re-check persisted") {
            assertEquals(setOf(2), Prefs.widgetSelectedServerIds(ctx()))
        }
    }

    @Test
    fun allAndNoneButtonsSetTheWholeSelection() {
        openWidgetPane()
        compose.onNodeWithText("None").performClick()
        waitFor("None stored the explicit empty set (show nothing)") {
            assertEquals(emptySet<Int>(), Prefs.widgetSelectedServerIds(ctx()))
        }
        // "All" is the show-all default, not a stored snapshot of the
        // live ids: it unsets the selection, so the widget lists every
        // live server (including ones added later).
        compose.onNodeWithText("All").performClick()
        waitFor("All unset the selection (show all, the default)") {
            assertNull(Prefs.widgetSelectedServerIds(ctx()))
        }
    }

    @Test
    fun allAndNoneAreDisabledWhileTheServerListIsUnavailable() {
        // Regression: with the server list failed to load, a tap on "All"
        // persisted an EMPTY set (the "show nothing" state) — the opposite
        // of the user's action. The buttons are gated on the loaded list,
        // so a failed load leaves the selection unset.
        serverListResponse = 500 to """{"error":"boom"}"""
        compose.setContent { SettingsScreen(onBack = {}, onLogout = {}) }
        waitFor("settings menu with the Widget entry") {
            compose.onNodeWithText("Widget", useUnmergedTree = false).assertExists()
        }
        compose.onNodeWithText("Widget").performClick()
        waitFor("the failed load surfaced as the pane's error message") {
            compose.onNodeWithText("Cannot load servers", substring = true).assertIsDisplayed()
        }
        compose.onNodeWithText("All").assertIsNotEnabled()
        compose.onNodeWithText("None").assertIsNotEnabled()
        compose.onNodeWithText("All").performClick()
        assertEquals(
            "a failed load must not persist a selection",
            null,
            Prefs.widgetSelectedServerIds(ctx()),
        )
    }

    @Test
    fun aNonJsonServerListBodyShowsTheErrorStateInsteadOfCrashing() {
        // A 200 with a non-JSON body (a foreign service at the configured
        // URL) used to throw out of the pane's coroutine; it must surface
        // as the pane's error state instead.
        serverListResponse = 200 to "<html>not a server list</html>"
        compose.setContent { SettingsScreen(onBack = {}, onLogout = {}) }
        waitFor("settings menu with the Widget entry") {
            compose.onNodeWithText("Widget", useUnmergedTree = false).assertExists()
        }
        compose.onNodeWithText("Widget").performClick()
        waitFor("the bad 200 body surfaced as the pane's error message") {
            compose.onNodeWithText("Cannot load servers (invalid server list)").assertIsDisplayed()
        }
        assertTrue(
            "no server rows after the bad 200 body",
            compose.onAllNodesWithText("srv-a").fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithText("All").assertIsNotEnabled()
    }

    @Test
    fun aChangeTriggersTheImmediateWidgetRefreshPath() {
        openWidgetPane()
        // The pane's load itself does not poll status; the menu's version
        // poll is the only /api/version call so far.
        compose.onNodeWithContentDescription("Two-column grid").performClick()
        waitFor("the change issued a fresh status fetch (the refresh path)") {
            assertTrue(
                "no /api/status poll after the change: $requests",
                requests.contains("GET /api/status"),
            )
        }
    }

    @Test
    fun defaultsAreTwoColumnsAndShowAllWhenUnset() {
        // A fresh install (no stored widget settings) reads as the
        // defaults the widget applies: 2 columns, all servers.
        assertEquals(WIDGET_COLUMNS_2, Prefs.widgetColumns(ctx()))
        assertNull(Prefs.widgetSelectedServerIds(ctx()))
    }
}
