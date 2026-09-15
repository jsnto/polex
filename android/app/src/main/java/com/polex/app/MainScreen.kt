package com.polex.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

val OnlineGreen = Color(0xFF4CAF50)
val OfflineRed = Color(0xFFF44336)
val WarnAmber = Color(0xFFFB8C00)   // version mismatch highlight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onSettings: () -> Unit, onOpenServer: (Int) -> Unit, onAuthError: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var items by remember { mutableStateOf<List<SrvStatus>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var wolBusy by remember { mutableStateOf(false) }
    // Disconnected state (connection-level fetch failure, code -1): shows
    // the dedicated full-screen page; the session/token are left untouched.
    val tracker = remember { RetryTracker() }
    var disconnected by remember { mutableStateOf(false) }
    var retryAttempts by remember { mutableIntStateOf(0) }
    // True while a fetch cycle (auto-retries) is in flight; guards against
    // overlapping cycles from the 15 s loop, toolbar, pull-to-refresh.
    var cycling by remember { mutableStateOf(false) }
    // Pull-to-refresh: the spinner flag (true only while a pull is in flight —
    // the 15 s auto-refresh and the toolbar button don't show it) plus the
    // gesture state required by pullToRefresh.
    var isRefreshing by remember { mutableStateOf(false) }
    val pullState = remember { PullToRefreshState() }
    // Server awaiting wake confirmation (non-null = show the dialog).
    var wakeTarget by remember { mutableStateOf<SrvStatus?>(null) }

    suspend fun fetchStatus(): Api.Result =
        Api.safe { Api.get("${Api.base(context)}/api/status") }

    // One fetch + auto-retry cycle (3 retries, 2 s apart) on
    // connection-level failure, then applies the final result to the UI.
    // Connection failures never touch the token; only a real 401 re-auths.
    suspend fun runRefresh() {
        val result = runFetchCycle(
            tracker,
            onAttempt = { n ->
                disconnected = true
                retryAttempts = n
            },
        ) { fetchStatus() }
        cycling = false
        when {
            result.code == 200 -> {
                tracker.reset()
                retryAttempts = 0
                disconnected = false
                items = parseStatusArray(result.body)
                error = ""
                // Keep the widget's cache fresh too.
                Prefs.setCache(context, result.body)
                ServerWidget.refreshAll(context)
            }
            Disconnect.requiresReauth(result.code) -> {
                // Token rejected by a REACHABLE backend: back to login.
                disconnected = false
                onAuthError()
            }
            Disconnect.isDisconnected(result.code) -> {
                // Auto-retries exhausted; the disconnected page's Retry
                // button (or the toolbar/pull-to-refresh) starts a new cycle.
            }
            else -> {
                // Reachable backend, other error: keep the inline error bar.
                disconnected = false
                error = "Server error ${result.code}: ${result.body}"
            }
        }
        loading = false
    }

    fun refresh() {
        if (cycling) return
        cycling = true
        retryAttempts = 0
        scope.launch { runRefresh() }
    }

    // Pull-to-refresh variant: shows the spinner while the (launched) fetch
    // cycle is in flight. pullToRefresh's onRefresh is a plain (non-suspend)
    // lambda in material3 1.3.0, so the coroutine is launched here.
    fun pullRefresh() {
        if (cycling) return
        cycling = true
        scope.launch {
            isRefreshing = true
            runRefresh()
            isRefreshing = false
        }
    }

    // Auto-refresh every 15s (paused while disconnected: recovery happens
    // via the Retry button / toolbar / pull-to-refresh instead).
    LaunchedEffect(Unit) {
        while (true) {
            if (!disconnected) refresh()
            delay(15_000)
        }
    }

    fun sendWol(s: SrvStatus) {
        if (wolBusy) return
        wolBusy = true
        scope.launch {
            val result = Api.sendWol(context, s.id)
            wolBusy = false
            val ok = result.code in 200..299 && parseOk(result.body)
            snackbar.showSnackbar(
                if (ok) "Wake packet sent to ${s.name}" else "WOL failed: ${result.body}"
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("PoleX") },
                actions = {
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { pad ->
        if (disconnected) {
            DisconnectedScreen(
                attempts = retryAttempts,
                retrying = cycling,
                onRetry = { refresh() },
                modifier = Modifier.padding(pad),
            )
            return@Scaffold
        }
        // Plain (non-Lazy) Column inside a pullToRefresh Box: unlike a
        // LazyColumn, a short list that fits on screen still reports the
        // unconsumed downward drag, so pull-to-refresh works with a few
        // servers. (Fine for a home lab; switch back to LazyColumn if the
        // list ever grows large.)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .pullToRefresh(isRefreshing = isRefreshing, state = pullState, onRefresh = { pullRefresh() }),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (error.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = OfflineRed.copy(alpha = 0.12f)),
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(14.dp),
                            color = Color(0xFFF87171),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                if (items.isNotEmpty()) {
                    val up = items.count { it.online }
                    val latest = items.mapNotNull { parseEpoch(it.lastChecked) }.maxOrNull()
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = (if (up == items.size) OnlineGreen else OfflineRed).copy(alpha = 0.12f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "●",
                                color = if (up == items.size) OnlineGreen else OfflineRed,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "$up of ${items.size} online",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                text = if (latest != null) "checked ${relativeTime(latest)}" else "not checked yet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (items.isEmpty() && !loading && error.isEmpty()) {
                    Text(
                        "No servers configured.\nOpen settings to add servers.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                items.forEach { s ->
                    ServerCard(
                        s,
                        busy = wolBusy,
                        onWake = { wakeTarget = s },
                        onClick = { onOpenServer(s.id) },
                    )
                }
            }
        }
    }

    // Wake confirmation dialog.
    wakeTarget?.let { s ->
        AlertDialog(
            onDismissRequest = { wakeTarget = null },
            title = { Text("Wake ${s.name}?") },
            text = {
                Text(
                    if (s.mac.isNotEmpty()) {
                        "A Wake-on-LAN packet will be sent to ${s.mac} (${s.ip})."
                    } else {
                        "This server has no MAC address configured, so WOL cannot be sent."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = s
                        wakeTarget = null
                        sendWol(target)
                    },
                    enabled = s.mac.isNotEmpty() && !wolBusy,
                ) {
                    Text("Wake")
                }
            },
            dismissButton = {
                TextButton(onClick = { wakeTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun ServerCard(s: SrvStatus, busy: Boolean, onWake: () -> Unit, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(if (s.online) OnlineGreen else OfflineRed, CircleShape),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "${s.ip}  ·  ${if (s.check == "ping") "ping" else "tcp :${s.port}"}  ·  ${if (s.online) "${s.latencyMs} ms" else "offline"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val meta = buildList {
                    add("checked ${relativeTime(s.lastChecked)}")
                    if (s.lastWolAt.isNotEmpty()) add("last wake ${s.lastWolAt}")
                }.joinToString("  ·  ")
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            WakeButton(
                enabled = s.mac.isNotEmpty() && !busy,
                busy = busy,
                online = s.online,
                onClick = onWake,
            )
        }
    }
}

/** Power Wake control, shared by the dashboard and the server detail page.
 *  The stroke-based power symbol (`R.drawable.ic_power`, open circle +
 *  vertical line) tinted green when the server is online, red when offline,
 *  dimmed when it can't be used (no MAC configured / sending). */
@Composable
fun WakeButton(enabled: Boolean, busy: Boolean, online: Boolean, onClick: () -> Unit) {
    val tint = if (online) OnlineGreen else OfflineRed
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_power),
            contentDescription = if (busy) "Waking…" else if (online) "Online" else "Offline — tap to wake",
            tint = tint.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(28.dp),
        )
    }
}
