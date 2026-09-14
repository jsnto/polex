package com.polex.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
