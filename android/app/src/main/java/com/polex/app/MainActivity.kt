package com.polex.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    /**
     * The platform installer's result route (the settings "Update" area's
     * ActivityResult launcher, registered before any launch): a rejected
     * install (e.g. INSTALL_FAILED_UPDATE_INCOMPATIBLE) reaches
     * [UpdateManager.onInstallResult] through it instead of being
     * swallowed. The install-settings jump itself is fire-and-forget (a
     * plain startActivity), so every result on this launcher is the
     * installer's.
     */
    private lateinit var installResult: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installResult = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) { Updates.manager(this).onInstallResult(it) }
        // The engine's installer launch route: every installer launch (the
        // pane's Install tap, the settings-return auto-install) goes
        // through this ActivityResult launcher, so the installer's RESULT
        // is always captured (a rejected install surfaces through the
        // engine's onInstallResult instead of being swallowed).
        Updates.manager(this).installLaunchRoute = { intent ->
            installResult.launch(intent)
        }

        // Keep the widget fresh. WorkManager's minimum periodic interval is
        // 15 minutes; the widget also refreshes on every in-app status poll
        // (see MainScreen), so real-world staleness is much lower.
        val request = PeriodicWorkRequestBuilder<StatusWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("polex-status", ExistingPeriodicWorkPolicy.KEEP, request)

        // Belt-and-braces widget refresh: the WorkManager job above can be
        // deferred by the system (doze/battery optimization); this
        // self-rescheduling inexact alarm fires ~15 min after each tick and
        // enqueues the same one-shot fetch (see WidgetAlarm.kt).
        WidgetAlarm.schedule(this)

        // Restore the auth token into the API client (survives process death
        // via Prefs; the in-memory Api.authToken does not).
        Prefs.token(this)?.let { Api.authToken = it }

        // Seed the widget's cache immediately on first run instead of waiting
        // up to 15 minutes for the first periodic pass.
        if (Prefs.cache(this) == null) {
            StatusWorker.enqueueNow(this)
        }

        setContent {
            PoleXApp()
        }
    }

    override fun onResume() {
        super.onResume()
        // The settings "Update" area's install-settings return (the
        // "install unknown apps" permission may have been granted, or still
        // be off): the engine re-checks and launches the kept install or
        // offers the retry path. A no-op on every other resume (the
        // engine's own flag only arms after it sent the user to the
        // settings screen). The auto-install launches through the engine's
        // registered ActivityResult route (set in onCreate), so a rejected
        // install surfaces (the engine's onInstallResult) instead of
        // idling at "Ready to install".
        Updates.manager(this).onActivityResumed(this)
    }
}

@Composable
fun PoleXApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    // 0 = main, 1 = settings, 2 = login, 3 = server detail.
    var route by rememberSaveable {
        mutableIntStateOf(if (Prefs.hasToken(context)) 0 else 2)
    }
    // id of the server the detail page (route 3) shows.
    var detailId by rememberSaveable { mutableIntStateOf(0) }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF81C784),
            secondary = Color(0xFF4CAF50),
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF0B1220)) {
            when (route) {
                0 -> MainScreen(
                    onSettings = { route = 1 },
                    onOpenServer = {
                        detailId = it
                        route = 3
                    },
                    onAuthError = {
                        Prefs.clearToken(context)
                        Api.authToken = null
                        route = 2
                    },
                )
                1 -> SettingsScreen(
                    onBack = { route = 0 },
                    onLogout = {
                        Prefs.clearToken(context)
                        Api.authToken = null
                        route = 2
                    },
                )
                3 -> ServerDetailScreen(
                    serverId = detailId,
                    onBack = { route = 0 },
                    onAuthError = {
                        Prefs.clearToken(context)
                        Api.authToken = null
                        route = 2
                    },
                )
                else -> LoginScreen(onLogin = { route = 0 })
            }
        }
    }
}
