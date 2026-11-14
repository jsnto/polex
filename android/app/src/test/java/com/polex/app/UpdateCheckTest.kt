package com.polex.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's self-update support (pure, no network, no Android): the
 * release-tag -> version normalization (a leading `v`/`V` stripped), the
 * strictly-newer decision (NUMERIC per dot-separated component via
 * [compareVersions] in Versions.kt, so `1.10 > 1.9` — never a string
 * compare), and the parse of a GitHub `/releases/latest` response (a
 * fixture mimicking the API, including the `assets` array).
 *
 * The network fetch + download + install live in [UpdateManager]; this is
 * the ordering + parse truth it feeds, pinned here without a live network.
 */
class UpdateCheckTest {

    // ---- releaseVersion (strip the leading v) ----

    @Test
    fun `a leading v is stripped`() {
        assertEquals("1.3", releaseVersion("v1.3"))
        assertEquals("1.10", releaseVersion("v1.10"))
    }

    @Test
    fun `a leading V is stripped (case-insensitive marker)`() {
        assertEquals("1.3", releaseVersion("V1.3"))
    }

    @Test
    fun `a tag with no marker is its own version`() {
        assertEquals("1.3", releaseVersion("1.3"))
        assertEquals("1.2.3", releaseVersion("1.2.3"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("1.3", releaseVersion(" v1.3 "))
        assertEquals("1.3", releaseVersion("  1.3  "))
    }

    // ---- releaseIsNewer (strictly newer, numeric per component) ----

    @Test
    fun `a strictly newer tag is an update`() {
        assertTrue(releaseIsNewer("v1.3", "1.2"))
        assertTrue(releaseIsNewer("1.3.1", "1.3"))
        assertTrue(releaseIsNewer("2.0", "1.9"))
    }

    @Test
    fun `two digit component beats one digit numerically - not by string`() {
        // The exact case the spec calls out: a string compare says
        // "1.10" < "1.9" (because "1" < "9" at index 2); numerically 10 > 9.
        assertTrue("v1.10 must be newer than 1.9", releaseIsNewer("v1.10", "1.9"))
        assertFalse("1.9 must not be newer than 1.10", releaseIsNewer("v1.9", "1.10"))
    }

    @Test
    fun `an equal tag is not an update`() {
        assertFalse("the running build IS the latest", releaseIsNewer("v1.3", "1.3"))
        assertFalse(releaseIsNewer("1.3", "1.3"))
        // The app's two-component versionName vs a three-component tag.
        assertFalse(releaseIsNewer("v1.3.0", "1.3"))
    }

    @Test
    fun `an older tag is not an update`() {
        assertFalse(releaseIsNewer("v1.2", "1.3"))
        assertFalse(releaseIsNewer("v0.9", "1.0"))
    }

    // ---- parseLatestRelease (the GitHub /releases/latest shape) ----

    @Test
    fun `a realistic release fixture parses its tag and assets`() {
        val release = parseLatestRelease(FIXTURE_LATEST_RELEASE)
        assertEquals("v1.3", release.tagName)
        assertEquals(2, release.assets.size)
        // The APK asset (the one the update downloads).
        val apk = release.assets.first { it.name.endsWith(".apk") }
        assertEquals("polex-release.apk", apk.name)
        assertEquals(
            "https://github.com/jsnto/polex/releases/download/v1.3/polex-release.apk",
            apk.downloadUrl,
        )
        // A non-APK asset (a checksum) is also carried.
        val sha = release.assets.first { it.name.endsWith(".sha256") }
        assertEquals("polex-release.apk.sha256", sha.name)
    }

    @Test
    fun `unknown top-level and per-asset fields are ignored`() {
        // The fixture carries the API's many metadata fields (id, author,
        // html_url, content_type, size, ...); the parser reads only
        // tag_name + assets[].name + assets[].browser_download_url and
        // tolerates the rest.
        val release = parseLatestRelease(FIXTURE_LATEST_RELEASE)
        assertEquals("v1.3", release.tagName)
        assertEquals(2, release.assets.size)
    }

    @Test
    fun `a release with no assets field reads an empty list`() {
        val release = parseLatestRelease("""{"tag_name":"v1.4"}""")
        assertEquals("v1.4", release.tagName)
        assertTrue(release.assets.isEmpty())
    }

    @Test
    fun `a release with an explicit empty assets array reads an empty list`() {
        val release = parseLatestRelease("""{"tag_name":"v1.4","assets":[]}""")
        assertTrue(release.assets.isEmpty())
    }

    @Test
    fun `a body without a tag_name throws (the caller turns it into a plain message)`() {
        try {
            parseLatestRelease("""{"name":"no tag here"}""")
            throw AssertionError("parseLatestRelease must throw on a body without tag_name")
        } catch (e: org.json.JSONException) {
            // expected
        }
    }

    // ---- pickApkAsset (the install pick) ----

    @Test
    fun `pickApkAsset selects the apk asset`() {
        val release = parseLatestRelease(FIXTURE_LATEST_RELEASE)
        val apk = pickApkAsset(release)
        assertEquals("polex-release.apk", apk?.name)
        assertTrue(apk?.name?.endsWith(".apk") == true)
    }

    @Test
    fun `pickApkAsset selects the first apk when several exist`() {
        val json = """
            {
              "tag_name": "v1.4",
              "assets": [
                {"name": "polex-debug.apk", "browser_download_url": "https://x/debug.apk"},
                {"name": "polex-release.apk", "browser_download_url": "https://x/release.apk"}
              ]
            }
        """.trimIndent()
        val apk = pickApkAsset(parseLatestRelease(json))
        assertEquals("polex-debug.apk", apk?.name)
    }

    @Test
    fun `pickApkAsset matches the extension case-insensitively`() {
        val json = """
            {"tag_name":"v1.4","assets":[{"name":"POLEX-APP.APK","browser_download_url":"https://x/a.apk"}]}
        """.trimIndent()
        val apk = pickApkAsset(parseLatestRelease(json))
        assertEquals("POLEX-APP.APK", apk?.name)
    }

    @Test
    fun `pickApkAsset is null when there is no apk asset`() {
        val json = """
            {"tag_name":"v1.4","assets":[{"name":"checksums.txt","browser_download_url":"https://x/txt"}]}
        """.trimIndent()
        assertNull(pickApkAsset(parseLatestRelease(json)))
    }

    @Test
    fun `pickApkAsset is null for a release with no assets`() {
        assertNull(pickApkAsset(parseLatestRelease("""{"tag_name":"v1.4"}""")))
    }

    /**
     * A fixture mimicking the public GitHub `/releases/latest` response
     * (the shape the API returns): the `tag_name`, the many metadata
     * fields the parser must ignore, and an `assets` array whose entries
     * carry `name` + `browser_download_url` among other fields.
     */
    private companion object {
        val FIXTURE_LATEST_RELEASE = """
            {
              "url": "https://api.github.com/repos/jsnto/polex/releases/1000",
              "assets_url": "https://api.github.com/repos/jsnto/polex/releases/1000/assets",
              "upload_url": "https://uploads.github.com/repos/jsnto/polex/releases/1000/assets{?name,label}",
              "html_url": "https://github.com/jsnto/polex/releases/tag/v1.3",
              "id": 1000,
              "author": {"login": "jsnto", "id": 1},
              "node_id": "RE_kwDOX1000",
              "tag_name": "v1.3",
              "target_commitish": "master",
              "name": "PoleX v1.3",
              "draft": false,
              "prerelease": false,
              "created_at": "2026-11-14T12:00:00Z",
              "published_at": "2026-11-14T12:05:00Z",
              "assets": [
                {
                  "url": "https://api.github.com/repos/jsnto/polex/releases/assets/2000",
                  "id": 2000,
                  "node_id": "RA_kwDOX2000",
                  "name": "polex-release.apk",
                  "label": null,
                  "content_type": "application/vnd.android.package-archive",
                  "size": 19209989,
                  "download_count": 3,
                  "created_at": "2026-11-14T12:04:00Z",
                  "updated_at": "2026-11-14T12:04:00Z",
                  "browser_download_url": "https://github.com/jsnto/polex/releases/download/v1.3/polex-release.apk"
                },
                {
                  "url": "https://api.github.com/repos/jsnto/polex/releases/assets/2001",
                  "id": 2001,
                  "node_id": "RA_kwDOX2001",
                  "name": "polex-release.apk.sha256",
                  "label": null,
                  "content_type": "text/plain",
                  "size": 64,
                  "download_count": 1,
                  "created_at": "2026-11-14T12:04:00Z",
                  "updated_at": "2026-11-14T12:04:00Z",
                  "browser_download_url": "https://github.com/jsnto/polex/releases/download/v1.3/polex-release.apk.sha256"
                }
              ]
            }
        """.trimIndent()
    }
}
