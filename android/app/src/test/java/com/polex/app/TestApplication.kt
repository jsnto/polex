package com.polex.app

import android.app.Application
import android.content.pm.ActivityInfo
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowPackageManager

/**
 * Test-only [Application] for the Compose UI tests ([ServerDetailRemoveTest]).
 *
 * Robolectric loads the app's MERGED (production) manifest, and
 * [TestDetailActivity] exists only in the unit-test source set, so it never
 * appears there. Registering it in the test-only [ShadowPackageManager] makes
 * the activity-scenario-based compose rule able to launch it, without the
 * registration ever shipping in the production APK or main source set.
 */
class TestApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        val pm = Shadows.shadowOf(packageManager) as ShadowPackageManager
        pm.addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = "com.polex.app"
                name = "com.polex.app.TestDetailActivity"
                nonLocalizedLabel = "TestDetailActivity"
                exported = false
                applicationInfo = packageManager.getApplicationInfo(packageName, 0)
            },
        )
    }
}
