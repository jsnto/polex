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

    /** A step failed; [message] is the plain user-facing copy. */
    data class Error(val message: String) : UpdateState()
}
