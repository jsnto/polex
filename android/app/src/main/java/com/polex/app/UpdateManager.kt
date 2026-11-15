package com.polex.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * The installer result's failure extra — the platform's `Intent
 * EXTRA_FAILED_REQUEST` (API 26+), the string the platform installer puts
 * on a rejected install's result data (e.g. `INSTALL_FAILED_UPDATE_INCOMPATIBLE`).
 * The pruned compile stub omits that `Intent` constant, so the literal
 * value the platform uses (same treatment as the settings-action literals
 * in [INSTALL_SETTINGS_ACTION_MODERN] / [INSTALL_SETTINGS_ACTION_LEGACY]).
 */
const val INSTALLER_FAILED_REQUEST_EXTRA = "android.content.pm.extra.INSTALL_FAILED_REQUEST"

/**
 * Process-wide holder for the settings "Update" area's [UpdateManager].
 * The app has no ViewModel; this is the one long-lived Android-aware object
 * the settings pane and the activity's onResume (the return from the
 * "install unknown apps" settings screen) both reach.
 */
object Updates {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile
    private var _manager: UpdateManager? = null

    fun manager(context: Context): UpdateManager =
        _manager ?: UpdateManager(context.applicationContext, scope).also { _manager = it }

    /**
     * Test seam: installs a manager built with a stand-in HTTP client (the
     * Robolectric UI test routes the releases API over loopback to a
     * disposable local stand-in). `null` restores the default.
     */
    fun installForTests(manager: UpdateManager?) {
        _manager = manager
    }
}

/**
 * The settings "Update" area's engine: checks the public GitHub releases
 * of the app's own repository for a newer build, downloads the newest
 * release's APK asset to the app's storage with progress, and hands it to
 * the platform's installer (gated on the phone's "install unknown apps"
 * permission).
 *
 * The app is sideloaded as an APK (there is no store), so this is the only
 * update path. The check calls `GET /releases/latest` with NO embedded
 * token — the repository is public and the request carries only an
 * `Accept` + `User-Agent` header. The version ordering and the response
 * parse are the pure domain in UpdateCheck.kt ([parseLatestRelease],
 * [releaseVersion], [releaseIsNewer], [pickApkAsset]); this class is the
 * Android-aware plumbing around them (the OkHttp fetch + streamed download,
 * the [FileProvider] uri, the install intent, the `REQUEST_INSTALL_PACKAGES`
 * gate, and the installer's result — a rejected install settles to the
 * plain error copy, see [onInstallResult]).
 */
class UpdateManager(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Test seam: the HTTP client (a real [OkHttpClient] by default). */
    private val client: OkHttpClient = OkHttpClient(),
) {

    // ---- state (the pane renders this) ----

    /**
     * The area's lifecycle (see [UpdateState]). The pane reads it to pick
     * its copy and which buttons are live.
     */
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)

    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * The download's progress, `0f` to `1f` (meaningful only while the
     * state is [UpdateState.Downloading]; a response with no known length
     * jumps straight to `1f` when it lands).
     */
    private val _progress = MutableStateFlow(0f)

    val progress: StateFlow<Float> = _progress.asStateFlow()

    /**
     * Whether the user went to the phone's "install unknown apps" settings
     * and has not yet come back: set when [requestInstall] has to open
     * those settings instead of installing, consumed (and acted on) by
     * [onActivityResumed] when the activity returns.
     */
    @Volatile
    private var awaitingPermission = false

    /**
     * The installer's launch route: the hosting activity registers the
     * ActivityResult launcher (MainActivity.onCreate) and hands this engine
     * its launch function, so the installer's RESULT reaches
     * [onInstallResult] (a rejected install surfaces as the error copy
     * instead of being swallowed). Every installer launch in the engine
     * (the pane's Install tap, the settings-return auto-install) goes
     * through it. When the activity never registered a route (never the
     * case in the production app — MainActivity always does), the engine
     * falls back to a fire-and-forget `startActivity`.
     */
    @Volatile
    var installLaunchRoute: ((Intent) -> Unit)? = null

    /** The engine's installer launch: the registered route, or fire-and-forget. */
    private fun launchInstaller(activity: Activity, intent: Intent) {
        installLaunchRoute?.invoke(intent) ?: activity.startActivity(intent)
    }

    // ---- the check ----

    /**
     * Checks the releases page for a newer build. The running version is
     * [BuildConfig.VERSION_NAME]; the newest release's tag (a leading
     * `v`/`V` stripped) is compared to it NUMERICALLY per component
     * (never by string, so `1.10 > 1.9`). The result settles to
     * [UpdateState.UpToDate] (equal or older) or [UpdateState.UpdateAvailable]
     * (strictly newer), or [UpdateState.Error] on a network failure, no
     * published release, or an unexpected response.
     */
    fun checkForLatest() {
        if (_state.value is UpdateState.Checking) return
        _state.value = UpdateState.Checking
        scope.launch {
            try {
                val release = fetchLatestRelease()
                val current = BuildConfig.VERSION_NAME
                val latest = releaseVersion(release.tagName)
                _state.value = if (releaseIsNewer(release.tagName, current)) {
                    UpdateState.UpdateAvailable(current, latest, release)
                } else {
                    UpdateState.UpToDate(current, latest)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Error(checkErrorMessage(e))
            }
        }
    }

    // ---- the download + install ----

    /**
     * Downloads the newest release's APK asset (the [pickApkAsset] pick —
     * the first `.apk` in the release's assets, never a hard-coded file
     * name) into the app's storage, reporting [progress]. On success the
     * state settles to [UpdateState.ReadyToInstall] with the APK's path;
     * on a missing asset or a download failure it settles to
     * [UpdateState.Error]. Only callable while [UpdateState.UpdateAvailable].
     */
    fun updateToLatest() {
        val available = _state.value as? UpdateState.UpdateAvailable ?: return
        val asset = pickApkAsset(available.release)
        if (asset == null) {
            _state.value = UpdateState.Error("That release has no APK to install.")
            return
        }
        _state.value = UpdateState.Downloading(available.latestVersion)
        _progress.value = 0f
        scope.launch {
            try {
                val file = downloadTo(asset.downloadUrl, apkFile()) { p -> _progress.value = p }
                _state.value = UpdateState.ReadyToInstall(available.latestVersion, file.absolutePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Error(downloadErrorMessage(e))
            }
        }
    }

    /**
     * The user tapped the install action while [UpdateState.ReadyToInstall]:
     * when the phone's "install unknown apps" permission is granted, launch
     * the platform installer over the APK's [FileProvider] uri — through
     * the activity's registered ActivityResult route ([installLaunchRoute],
     * so a rejected install surfaces, see [onInstallResult]); when it is
     * not, explain the requirement in a plain message and open the phone's
     * own install-settings screen (the action that actually resolves on
     * this device, see [pickInstallSettingsAction]; the download is kept
     * and [onActivityResumed] offers the retry path on the return).
     */
    fun requestInstall(activity: Activity) {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        val uri = installUri(ready.apkPath)
        if (uri == null) {
            toast("The downloaded file is missing. Download it again to install.")
            return
        }
        if (canInstallPackages()) {
            launchInstaller(activity, installIntent(uri))
        } else {
            awaitingPermission = true
            val action = installSettingsAction()
            if (action == null) {
                // Neither settings action resolves on this device: the
                // permission can only be granted by hand.
                toast(
                    "Android requires the \"install unknown apps\" permission for this app. " +
                        "Find \"Install unknown apps\" in the phone's settings, allow it for " +
                        "PoleX, then come back and tap Install again.",
                )
            } else {
                toast(
                    "Android requires the \"install unknown apps\" permission for this app. " +
                        "Allow it in the phone's settings, then come back and tap Install again.",
                )
                runCatching { activity.startActivity(manageInstallSettingsIntent(action)) }
            }
        }
    }

    /**
     * Called from the activity's onResume (the return from the install
     * settings, or any resume). Only acts when [requestInstall] sent the
     * user to the settings screen: if the permission is now granted the
     * install is launched automatically (the kept APK) — through the
     * same ActivityResult route [requestInstall] uses, so a rejected
     * install surfaces there too — otherwise the download is kept and the
     * retry path (the Install button) is offered.
     */
    fun onActivityResumed(activity: Activity) {
        if (!awaitingPermission) return
        awaitingPermission = false
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        if (canInstallPackages()) {
            installUri(ready.apkPath)?.let { launchInstaller(activity, installIntent(it)) }
        } else {
            toast(
                "The \"install unknown apps\" permission is still off. " +
                    "The download is kept - tap Install to try again.",
            )
        }
    }

    /**
     * The platform installer's RESULT (delivered by the ActivityResult
     * launcher the activity registered as [installLaunchRoute]): a success settles the
     * area to [UpdateState.Installed] (the APK is in; restarting the app
     * runs it). A platform rejection carries its failure code in the
     * result data ([INSTALLER_FAILED_REQUEST_EXTRA] — e.g.
     * `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; the platform delivers a
     * rejected install with a CANCELED-style result), and settles the
     * area to [UpdateState.Error] with the concrete failure and the
     * remedy, via [installFailureMessage], instead of idling at "Ready to
     * install". The user's own dismissal of the installer (back) carries
     * no failure code: the area stays at [UpdateState.ReadyToInstall] —
     * not a failure, the Install button is the retry. Any other non-OK
     * result still surfaces that the install was rejected (never idles
     * silently).
     */
    fun onInstallResult(result: ActivityResult) {
        val ready = _state.value as? UpdateState.ReadyToInstall ?: return
        if (result.resultCode == Activity.RESULT_OK) {
            _state.value = UpdateState.Installed(ready.latestVersion)
            return
        }
        // A non-OK result: the failure extra is the discriminator — the
        // platform sets it on a rejection (even when the result code is a
        // plain CANCELED), and only the user's own dismissal carries none.
        val failure = result.data?.getStringExtra(INSTALLER_FAILED_REQUEST_EXTRA)
        when {
            failure != null && failure.isNotBlank() -> {
                _state.value = UpdateState.Error(installFailureMessage(failure))
            }
            result.resultCode == Activity.RESULT_CANCELED -> {
                // The user dismissed the installer: stay ReadyToInstall.
            }
            else -> {
                // Rejected, but no readable failure code: still surface it.
                _state.value = UpdateState.Error(installFailureMessage(null))
            }
        }
    }

    // ---- the platform seams (kept here so the pane stays presentational) ----

    /** Whether the phone allows this app to install packages (API 26+; the app's min). */
    fun canInstallPackages(): Boolean =
        context.packageManager.canRequestPackageInstalls()

    /** The APK's [FileProvider] uri (the installer's grant), or `null` when the file is gone. */
    fun installUri(apkPath: String): Uri? {
        val file = File(apkPath)
        if (!file.exists()) return null
        return runCatching {
            FileProvider.getUriForFile(context, fileProviderAuthority, file)
        }.getOrNull()
    }

    private fun installIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, APK_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /**
     * The "install unknown apps" settings action that actually opens on
     * this device: the modern per-app action when it resolves, the legacy
     * one when it does not (it has no target activity on some newer builds
     * — e.g. the Pixel 7 Pro round), `null` when neither resolves (the
     * caller explains the requirement in a plain message instead).
     */
    private fun installSettingsAction(): String? =
        pickInstallSettingsAction(
            actionResolves(INSTALL_SETTINGS_ACTION_MODERN),
            actionResolves(INSTALL_SETTINGS_ACTION_LEGACY),
        )

    /** The resolver check: at least one target activity for [action]. */
    private fun actionResolves(action: String): Boolean =
        runCatching {
            context.packageManager.queryIntentActivities(Intent(action), 0).isNotEmpty()
        }.getOrDefault(false)

    /** The phone's own "install unknown apps" screen for this app (API 26+). */
    private fun manageInstallSettingsIntent(action: String): Intent =
        Intent(action).apply {
            data = Uri.parse("package:" + context.packageName)
        }

    private val fileProviderAuthority: String
        get() = context.packageName + FILE_PROVIDER_AUTHORITY_SUFFIX

    /** Where a downloaded APK lives (the app-internal folder the provider exposes). */
    private fun apkFile(): File {
        val dir = File(context.filesDir, "updates")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "polex-update.apk")
    }

    private fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    // ---- the network (the pure parse lives in UpdateCheck.kt) ----

    /**
     * Fetches the `/releases/latest` response and parses it (on the IO
     * dispatcher; the body parse is the pure [parseLatestRelease]). A 404
     * (no published release) is its own error, and any other non-2xx
     * answer or network failure is an IO error.
     */
    private suspend fun fetchLatestRelease(): LatestRelease =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "polex")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                when {
                    response.isSuccessful -> parseLatestRelease(body)
                    response.code == 404 -> throw NoReleasesException()
                    else -> throw IOException("GitHub answered with code ${response.code}")
                }
            }
        }

    /**
     * Downloads [url] to [target] (on the IO dispatcher), streaming it and
     * reporting [onProgress] (`0f`..`1f`; a response with no known length
     * reports `1f` when it lands). A non-2xx answer or an empty body is an
     * IO error.
     */
    private suspend fun downloadTo(url: String, target: File, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(url).header("User-Agent", "polex").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("The download answered with code ${response.code}")
                }
                val body = response.body ?: throw IOException("The download came back empty")
                val total = body.contentLength()
                var received = 0L
                val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                target.outputStream().use { out ->
                    body.byteStream().use { input ->
                        while (true) {
                            val n = input.read(buffer)
                            if (n == -1) break
                            out.write(buffer, 0, n)
                            received += n
                            if (total > 0) onProgress((received.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (total <= 0) onProgress(1f)
                target
            }
        }

    // ---- plain error copy (a failure never crashes; it is a message) ----

    private fun checkErrorMessage(e: Throwable): String =
        when (e) {
            is NoReleasesException -> "There are no published releases yet. Check back later."
            is IOException -> "Couldn't reach the releases page. Check the connection and try again."
            else -> "Couldn't check for updates. Try again."
        }

    private fun downloadErrorMessage(e: Throwable): String =
        when (e) {
            is IOException -> "The download didn't finish. Check the connection and try again."
            else -> "The download didn't finish. Try again."
        }

    /** No published release exists (the GitHub API answers 404 for /releases/latest). */
    private class NoReleasesException : IOException("No releases published yet")

    private companion object {
        /** The app's own public releases (unauthenticated; the repo is public, so no token). */
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/jsnto/polex/releases/latest"

        /** The FileProvider authority (the manifest's `${applicationId}.update.fileprovider`). */
        const val FILE_PROVIDER_AUTHORITY_SUFFIX = ".update.fileprovider"

        /** The APK's mime type (the install intent's data type). */
        const val APK_MIME_TYPE = "application/vnd.android.package-archive"

        /** The download's stream-buffer size (64 KiB). */
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024

    }
}
