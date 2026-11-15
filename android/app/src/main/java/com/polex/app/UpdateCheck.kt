package com.polex.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * The app's self-update support (pure, JVM-testable, no network, no
 * Android): how a GitHub release tag normalizes to a version, how that
 * compares (numerically) to the running build, and how the public
 * `/releases/latest` response is parsed into the two facts the settings
 * "Update" area needs (the release's tag and the APK asset to download).
 *
 * The app is sideloaded as an APK (there is no store), so "update to
 * latest" means fetching the newest public release of the `jsnto/polex`
 * repository and installing its APK asset. The version ordering is shared
 * with the app-vs-backend version check ([compareVersions] in Versions.kt —
 * numeric per dot-separated component, never a string compare); the
 * network + download + install plumbing lives in [UpdateManager].
 */

/**
 * Strips a release tag's leading version marker: `"v1.3"` -> `"1.3"`,
 * `"V1.3"` -> `"1.3"`, `"1.3"` -> `"1.3"` (a tag with no marker is its own
 * version). Surrounding whitespace is trimmed.
 */
fun releaseVersion(tag: String): String {
    val trimmed = tag.trim()
    return if (trimmed.startsWith("v", ignoreCase = true)) trimmed.drop(1) else trimmed
}

/**
 * Whether the release [tag] (its version per [releaseVersion]) is STRICTLY
 * newer than [currentVersion] — numeric per-component via [compareVersions],
 * so `"v1.10" > "1.9"`. Equal (the running build IS the latest) or older is
 * not an update, so the update button stays disabled.
 */
fun releaseIsNewer(tag: String, currentVersion: String): Boolean =
    compareVersions(releaseVersion(tag), currentVersion) > 0

/** One asset of a GitHub release: its file name and the url to download it from. */
data class ReleaseAsset(val name: String, val downloadUrl: String)

/** The GitHub `/releases/latest` response narrowed to what the app uses: the release's tag and its assets. */
data class LatestRelease(val tagName: String, val assets: List<ReleaseAsset>)

/**
 * Parses a GitHub `/releases/latest` response body into a [LatestRelease].
 * The body is the shape GitHub returns (a `tag_name` string and an
 * `assets` array of objects carrying, among other fields, `name` and
 * `browser_download_url`); the other fields are ignored, and a response
 * with no `assets` field at all reads as an empty list. Throws on a body
 * that is not a release object (no `tag_name`) or an asset missing its
 * `name`/`browser_download_url` (the caller turns that into a plain
 * "couldn't check" message).
 */
fun parseLatestRelease(json: String): LatestRelease {
    val o = JSONObject(json)
    val tagName = o.getString("tag_name")
    val assets = mutableListOf<ReleaseAsset>()
    val arr = o.optJSONArray("assets") ?: JSONArray()
    for (i in 0 until arr.length()) {
        val a = arr.getJSONObject(i)
        assets += ReleaseAsset(a.getString("name"), a.getString("browser_download_url"))
    }
    return LatestRelease(tagName, assets)
}

/**
 * Picks the asset to install: the first asset whose name ends in `.apk`
 * (case-insensitive). The file name is NOT hard-coded — a release with a
 * differently named APK still installs. `null` when the release carries no
 * `.apk` asset (the caller reports "no APK to install").
 */
fun pickApkAsset(release: LatestRelease): ReleaseAsset? =
    release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }

/**
 * `android.provider.Settings.ACTION_MANAGE_APP_INSTALL_SETTINGS` (API 30+):
 * the per-app "install unknown apps" settings page. The pruned compile stub
 * omits that `Settings` constant, so the literal value the platform uses.
 */
const val INSTALL_SETTINGS_ACTION_MODERN = "android.settings.MANAGE_APP_INSTALL_SETTINGS"

/**
 * `android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` (API 23+,
 * legacy): the "install unknown apps" settings action. With a `package:`
 * data uri it opens the same per-app page on newer builds. The pruned
 * compile stub omits that `Settings` constant, so the literal value the
 * platform uses.
 */
const val INSTALL_SETTINGS_ACTION_LEGACY = "android.settings.MANAGE_UNKNOWN_APP_SOURCES"

/**
 * The "install unknown apps" settings action to open, chosen from the two
 * actions the platform offers by which of them actually RESOLVE on this
 * device (the caller's resolver check against the package manager): the
 * modern per-app action when it resolves, the legacy action when it does
 * not (some newer builds have no target activity for the modern action —
 * the Pixel 7 Pro round logged `unable to resolve Intent` for it while the
 * legacy action opened the same page), and `null` when neither resolves
 * (the caller must then explain the requirement in a plain message instead
 * of launching a dead intent).
 */
fun pickInstallSettingsAction(modernResolves: Boolean, legacyResolves: Boolean): String? =
    when {
        modernResolves -> INSTALL_SETTINGS_ACTION_MODERN
        legacyResolves -> INSTALL_SETTINGS_ACTION_LEGACY
        else -> null
    }

/**
 * Maps the platform installer's rejection onto plain user-facing copy:
 * names the concrete platform failure ([rawFailure] — the installer result's
 * `EXTRA_FAILED_REQUEST` extra, e.g. `INSTALL_FAILED_UPDATE_INCOMPATIBLE`)
 * and says what to do about it. An unknown or missing failure still says
 * the install was rejected (and how to retry) — a rejection is never
 * silently swallowed.
 */
fun installFailureMessage(rawFailure: String?): String {
    val code = rawFailure?.trim()?.ifEmpty { null }
    return when (code) {
        "INSTALL_FAILED_UPDATE_INCOMPATIBLE" ->
            "Android rejected the install (INSTALL_FAILED_UPDATE_INCOMPATIBLE): this APK is " +
                "signed with a different key than the PoleX that is installed. Uninstall the " +
                "current PoleX, then install this one again."
        "INSTALL_FAILED_USER_RESTRICTED" ->
            "Android rejected the install (INSTALL_FAILED_USER_RESTRICTED): the phone's " +
                "settings block installs from this app. Allow \"Install unknown apps\" for " +
                "PoleX again, then tap Install."
        "INSTALL_FAILED_NO_STORAGE",
        "INSTALL_FAILED_INSUFFICIENT_STORAGE",
        ->
            "Android rejected the install: the phone has no storage left for it. Free up " +
                "space, then tap Install again."
        "INSTALL_PARSE_FAILED_NO_CERTIFICATES",
        "INSTALL_PARSE_FAILED_NO_CERT",
        ->
            "Android rejected the install: the APK is damaged or not signed. Download the " +
                "APK again, then install it."
        else ->
            "Android rejected the install" + (code?.let { " ($it)" } ?: "") +
                ". Tap Install to try again; if it keeps failing, download the APK again."
    }
}

/**
 * The settings "Update" area's lifecycle. From an unchecked [Idle], a check
 * settles to [UpToDate] (the running build is the newest) or
 * [UpdateAvailable] (a newer release exists and can be downloaded); the
 * download runs through [Downloading] (with a separate progress flow) to
 * [ReadyToInstall] (the APK is on disk, ready for the platform installer);
 * any step can settle to [Error] with a plain user-facing message (a
 * network failure, no published release, or no APK asset).
 */
sealed class UpdateState {
    /** No check has been made yet. */
    object Idle : UpdateState()

    /** A check is in flight. */
    object Checking : UpdateState()

    /** Checked; the running build is the newest ([latestVersion] is the release that was checked). */
    data class UpToDate(val currentVersion: String, val latestVersion: String) : UpdateState()

    /** Checked; [latestVersion] is newer than [currentVersion] and [release] carries its APK. */
    data class UpdateAvailable(
        val currentVersion: String,
        val latestVersion: String,
        val release: LatestRelease,
    ) : UpdateState()

    /** Downloading [latestVersion] (the progress is the manager's `progress` flow). */
    data class Downloading(val latestVersion: String) : UpdateState()

    /** [latestVersion] is downloaded to [apkPath]; install it to update. */
    data class ReadyToInstall(val latestVersion: String, val apkPath: String) : UpdateState()

    /**
     * [latestVersion] is installed through the platform installer (its
     * RESULT was OK); the APK is in and restarting the app runs it.
     */
    data class Installed(val latestVersion: String) : UpdateState()

    /** A step failed; [message] is the plain user-facing copy. */
    data class Error(val message: String) : UpdateState()
}
