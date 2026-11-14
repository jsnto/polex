package com.polex.app

import android.content.Intent
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPackageManager
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Behavioral coverage for the settings "Update" area driven against the
 * REAL product: the real [SettingsScreen] menu → the real [UpdatePane] →
 * the real [UpdateManager] (its fetch, the org.json parse, the streamed
 * download, the [androidx.core.content.FileProvider] uri, and the
 * "install unknown apps" gate) → the real platform install intents.
 *
 * The one seam is the manager's own documented one (its `client`
 * constructor parameter, "a real [OkHttpClient] by default"): the client
 * routes the product's hardcoded `api.github.com` url over loopback to a
 * DISPOSABLE local stand-in of the releases API ([FakeReleasesApi]) that
 * serves GitHub-shaped responses, so every branch is drivable
 * deterministically while the product code (the url, the request, the
 * parse, the download, the state, the intents) runs unmodified. A real
 * `startActivity` on the real activity records the intent the shadow sees
 * (the install-settings jump and the installer launch are asserted on the
 * recorded intents). The backend stand-in is not needed for this flow
 * (the releases check is tokenless and points at the public GitHub API,
 * not the backend).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class UpdatePaneUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<TestDetailActivity>()

    /** The product's [UpdateManager] with the stand-in client (see [openUpdatePane]). */
    private lateinit var manager: UpdateManager

    @Before
    fun setUp() {
        // Point the backend URL at a dead port: the menu's /api/version
        // probe fails fast (the releases check never touches the backend).
        val prefs = compose.activity.getSharedPreferences("polex", Context.MODE_PRIVATE)
        prefs.edit().putString("baseUrl", "http://127.0.0.1:1").putString("authToken", "tok123").commit()
        Api.authToken = "tok123"
    }

    @After
    fun tearDown() {
        Updates.installForTests(null)
        Api.authToken = null
    }

    // ------------------------------------------------------------------
    // The happy path: check → available update → streamed download → the
    // install-settings gate → the permission grant → the installer launch.
    // ------------------------------------------------------------------

    @Test
    fun updateAvailableDownloadsTheApkAndInstallsItAfterThePermissionGrant() {
        val api = FakeReleasesApi().also { it.start() }
        try {
            val apkBytes = ByteArray(APK_SIZE_BYTES) { (it % 251).toByte() }
            api.downloads[APK_ASSET_NAME] = apkBytes
            api.latestBody = releaseJson(
                tag = "v1.5",
                assets = listOf(asset(APK_ASSET_NAME, "http://127.0.0.1:${api.port}/download/$APK_ASSET_NAME")),
            )
            val activity = openUpdatePane(routingClient(api.port))

            // The pane renders the running build and the unchecked state.
            compose.onNodeWithText("polex v1.4").assertExists()
            compose.onNodeWithText("Check the releases page to see if a newer version is out.").assertIsDisplayed()

            // The check fetches the (stand-in) releases page and settles to
            // an available update: the published v1.5 against the running 1.4.
            compose.onNodeWithText("Check for latest version").performClick()
            waitFor("the check to settle to update available") {
                compose.onNodeWithText("Update available: v1.5 (you have v1.4).").assertIsDisplayed()
            }

            // The download streams the asset (the throttled transfer keeps
            // the Downloading state observable), then settles ready.
            compose.onNodeWithText("Update to latest").performClick()
            waitFor("the download to be in flight (the throttled transfer keeps it observable)") {
                check(compose.onAllNodesWithText("Downloading v1.5", substring = true).fetchSemanticsNodes().isNotEmpty())
            }
            waitFor("the download to settle ready") {
                compose.onNodeWithText("Downloaded v1.5. Install it to update.").assertIsDisplayed()
            }

            // The APK landed on disk with the asset's exact bytes, and the
            // progress flow reached the end of the transfer.
            val apkFile = File(activity.filesDir, "updates/polex-update.apk")
            check(apkFile.readBytes().contentEquals(apkBytes)) {
                "the downloaded APK must carry the served asset's bytes"
            }
            waitFor("the progress to reach 1f") {
                check(manager.progress.value == 1f)
            }

            // The permission is still off: tapping Install explains the
            // requirement and opens the phone's own "install unknown apps"
            // screen for this app (the download is kept).
            compose.onNodeWithText("Install now").performClick()
            waitFor("the install-settings intent to launch") {
                checkNotNull(shadowOf(activity).peekNextStartedActivity())
            }
            val settingsIntent = shadowOf(activity).getNextStartedActivity()
            check(settingsIntent.action == ACTION_MANAGE_APP_INSTALL_SETTINGS) {
                "the engine must open the phone's install-settings screen, got ${settingsIntent.action}"
            }
            check(settingsIntent.dataString == "package:com.polex.app") {
                "the install-settings screen must be the one for this app, got ${settingsIntent.dataString}"
            }

            // The user grants the permission and comes back: the activity's
            // onResume hook (the engine's own return path) launches the
            // installer over the kept APK's FileProvider uri.
            shadowOf(activity.packageManager).setCanRequestPackageInstalls(true)
            manager.onActivityResumed(activity)
            val installIntent = shadowOf(activity).getNextStartedActivity()
            check(installIntent.action == Intent.ACTION_VIEW) {
                "the installer takes an ACTION_VIEW, got ${installIntent.action}"
            }
            check(installIntent.type == "application/vnd.android.package-archive") {
                "the installer intent must type the APK, got ${installIntent.type}"
            }
            check(installIntent.dataString!!.startsWith("content://com.polex.app.update.fileprovider/")) {
                "the installer intent must carry the FileProvider uri, got ${installIntent.dataString}"
            }
            check(installIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                "the installer intent must grant the read on the uri, got flags ${installIntent.flags}"
            }
        } finally {
            api.stop()
        }
    }

    // ------------------------------------------------------------------
    // The published release is OLDER than the running build: the check
    // settles UpToDate and names the RUNNING version; no update to take.
    // ------------------------------------------------------------------

    @Test
    fun upToDateWhenThePublishedReleaseIsOlderNamesTheRunningVersion() {
        val api = FakeReleasesApi().also { it.start() }
        try {
            api.latestBody = releaseJson(
                tag = "v1.2",
                assets = listOf(asset(APK_ASSET_NAME, "http://127.0.0.1:${api.port}/download/$APK_ASSET_NAME")),
            )
            openUpdatePane(routingClient(api.port))

            compose.onNodeWithText("Check for latest version").performClick()
            waitFor("the check to settle up to date (the published v1.2 is older than the running 1.4)") {
                compose.onNodeWithText("You're up to date (current version v1.4).").assertIsDisplayed()
            }
            // No update to take: the download and install actions stay
            // disabled.
            compose.onNodeWithText("Update to latest").assertIsNotEnabled()
            compose.onNodeWithText("Install now").assertIsNotEnabled()

            // The menu row's live subtitle says the same once back on the menu.
            compose.onNodeWithContentDescription("Back").performClick()
            waitFor("the menu row to show the up-to-date state") {
                compose.onNodeWithText("Up to date (v1.4)").assertExists()
            }
        } finally {
            api.stop()
        }
    }

    // ------------------------------------------------------------------
    // No published release exists: the GitHub API answers 404 for
    // /releases/latest - its own plain message, not a crash.
    // ------------------------------------------------------------------

    @Test
    fun noPublishedReleasesSurfacesThePlainMessage() {
        val api = FakeReleasesApi().also { it.start() }
        try {
            api.latestStatus = 404
            api.latestBody = """{"message":"Not Found","documentation_url":"https://docs.github.com"}"""
            openUpdatePane(routingClient(api.port))

            compose.onNodeWithText("Check for latest version").performClick()
            waitFor("the 404 to surface its plain message") {
                compose.onNodeWithText("There are no published releases yet. Check back later.").assertIsDisplayed()
            }
        } finally {
            api.stop()
        }
    }

    // ------------------------------------------------------------------
    // A newer release that carries no APK asset: the check reports the
    // update, and the download action refuses with its own message (no
    // write attempted).
    // ------------------------------------------------------------------

    @Test
    fun aNewerReleaseWithoutAnApkAssetRefusesTheDownload() {
        val api = FakeReleasesApi().also { it.start() }
        try {
            api.latestBody = releaseJson(
                tag = "v1.5",
                assets = listOf(asset("release-notes.txt", "http://127.0.0.1:${api.port}/download/release-notes.txt")),
            )
            openUpdatePane(routingClient(api.port))

            compose.onNodeWithText("Check for latest version").performClick()
            waitFor("the check to report the update (the release has no APK asset)") {
                compose.onNodeWithText("Update available: v1.5 (you have v1.4).").assertIsDisplayed()
            }
            compose.onNodeWithText("Update to latest").performClick()
            waitFor("the refusal message") {
                compose.onNodeWithText("That release has no APK to install.").assertIsDisplayed()
            }
        } finally {
            api.stop()
        }
    }

    // ------------------------------------------------------------------
    // The releases page is unreachable (connection refused): the check
    // settles to the connection-failure message, never a crash.
    // ------------------------------------------------------------------

    @Test
    fun anUnreachableReleasesPageSurfacesTheConnectionMessage() {
        val port = deadPort()
        openUpdatePane(routingClient(port))

        compose.onNodeWithText("Check for latest version").performClick()
        waitFor("the connection failure to surface its plain message") {
            compose.onNodeWithText("Couldn't reach the releases page. Check the connection and try again.").assertIsDisplayed()
        }
    }

    // ------------------------------------------------------------------
    // Fixtures + helpers
    // ------------------------------------------------------------------

    /**
     * Stands up the real settings menu + the real Update pane with the
     * process-wide manager's client (the manager's own test seam) pointed
     * at [port] - the product's code paths (the hardcoded url, the parse,
     * the download, the state, the intents) all run unmodified.
     */
    private fun openUpdatePane(client: OkHttpClient): android.app.Activity {
        manager = UpdateManager(
            compose.activity.applicationContext,
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            client,
        )
        Updates.installForTests(manager)
        compose.setContent { SettingsScreen(onBack = {}, onLogout = {}) }
        waitFor("settings menu with the Update entry") {
            compose.onNodeWithText("Update", useUnmergedTree = false).assertExists()
        }
        compose.onNodeWithText("Update", useUnmergedTree = false).performClick()
        waitFor("the Update pane to render") {
            compose.onNodeWithText("Current version").assertIsDisplayed()
        }
        return compose.activity
    }

    /**
     * The manager's documented client seam pointed at a loopback port: a
     * REAL [OkHttpClient] whose one interceptor re-routes every request
     * (the product builds the hardcoded `api.github.com` url; only the
     * network route is swapped).
     */
    private fun routingClient(port: Int): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val routed = chain.request().url.newBuilder()
                .scheme("http")
                .host("127.0.0.1")
                .port(port)
                .build()
            chain.proceed(chain.request().newBuilder().url(routed).build())
        }.build()

    /** A port that refuses connections (a bound-then-closed server socket). */
    private fun deadPort(): Int = ServerSocket(0).use { it.localPort }

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

    /** GitHub `/releases/latest`-shaped JSON (extra fields, like the real API's, must be tolerated). */
    private fun releaseJson(tag: String, assets: List<Pair<String, String>>): String =
        """{"tag_name":"$tag","name":"$tag","draft":false,"prerelease":false,"created_at":"2026-11-14T00:00:00Z","assets":[""" +
            assets.joinToString(",") { (name, url) -> """{"name":"$name","browser_download_url":"$url","size":1,"state":"uploaded"}""" } +
            "]}"

    /** One release asset: its file name + the url to download it from. */
    private fun asset(name: String, url: String): Pair<String, String> = name to url

    /**
     * The disposable local stand-in of the GitHub releases API: a minimal
     * HTTP/1.1 server on a private loopback port serving the
     * `/releases/latest` JSON (configurable status + body) and the download
     * assets, the download throttled in chunks so the download's progress
     * state is observable on the UI. `Connection: close` per response.
     */
    private class FakeReleasesApi {
        private val serverSocket = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        private var thread: Thread? = null

        val port: Int get() = serverSocket.localPort

        @Volatile
        var latestStatus: Int = 200
        @Volatile
        var latestBody: String = ""
        val downloads = HashMap<String, ByteArray>()

        fun start() {
            thread = Thread {
                while (true) {
                    val client = try {
                        serverSocket.accept()
                    } catch (e: java.net.SocketException) {
                        break // the socket was closed by stop()
                    }
                    Thread { handle(client) }.also {
                        it.isDaemon = true
                        it.start()
                    }
                }
            }.also {
                it.isDaemon = true
                it.start()
            }
        }

        fun stop() {
            try {
                serverSocket.close()
            } catch (e: Exception) {
                // already closed
            }
            thread?.join(2_000)
        }

        private fun handle(client: Socket) {
            client.use { s ->
                val input = s.getInputStream().bufferedReader()
                val line = input.readLine() ?: return
                val path = line.split(" ").getOrNull(1) ?: "/"
                var status = 404
                var body: ByteArray = ByteArray(0)
                var contentType = "application/json"
                var throttle = false
                when {
                    path == LATEST_PATH -> {
                        status = latestStatus
                        body = latestBody.toByteArray()
                    }
                    path.startsWith("/download/") -> {
                        val data = downloads[path.removePrefix("/download/")]
                        if (data != null) {
                            status = 200
                            body = data
                            contentType = "application/vnd.android.package-archive"
                            throttle = true
                        }
                    }
                }
                val output = s.getOutputStream()
                output.write(
                    (
                        "HTTP/1.1 $status OK\r\nContent-Type: $contentType\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    ).toByteArray()
                )
                if (throttle) {
                    var offset = 0
                    while (offset < body.size) {
                        val chunk = minOf(CHUNK_BYTES, body.size - offset)
                        output.write(body, offset, chunk)
                        output.flush()
                        offset += chunk
                        Thread.sleep(CHUNK_DELAY_MS)
                    }
                } else {
                    output.write(body)
                    output.flush()
                }
            }
        }

        private companion object {
            const val LATEST_PATH = "/repos/jsnto/polex/releases/latest"
            const val CHUNK_BYTES = 8 * 1024
            const val CHUNK_DELAY_MS = 15L
        }
    }

    private companion object {
        const val APK_ASSET_NAME = "polex-release.apk"
        const val APK_SIZE_BYTES = 1024 * 1024
        const val ACTION_MANAGE_APP_INSTALL_SETTINGS = "android.settings.MANAGE_APP_INSTALL_SETTINGS"
    }
}
