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
    // The single power control is in flight (a wake packet or a power-off
    // command is being sent).
    var powerBusy by remember { mutableStateOf(false) }
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
    // Server awaiting power-action confirmation (non-null = show the dialog).
    // The action follows the server's current state: online → power off
    // (over SSH), offline → wake (Wake-on-LAN).
    var powerTarget by remember { mutableStateOf<SrvStatus?>(null) }

    suspend fun fetchStatus(): Api.Result =
        Api.safe { Api.get("${Api.base(context)}/api/status") }

    // One fetch + auto-retry cycle (3 retries, 2 s apart) on
    // connection-level failure, then applies the final result to the UI.
    // Connection failures never touch the token; only a real 401 re-auths.
    suspend fun runRefresh() {
        val result = runFetchCycle(
            tracker,
            onAttempt = { n ->
                // Silent while the app is connected: the screen only appears
                // after the cycle exhausts (below). While it is already
                // visible (a manual-Retry cycle) keep its live progress.
                if (disconnected) retryAttempts = n
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
            // The screen appears only when the cycle ends at the connection
            // level — i.e. only after every silent auto-retry failed. Its
            // Retry button (or the toolbar/pull-to-refresh) starts a new cycle.
            Disconnect.screenVisibleAfterCycle(result.code) -> {
                disconnected = true
                retryAttempts = tracker.attempts
            }
            else -> {
                // Reachable backend, other error: keep the inline error bar.
                disconnected = false
                error = "Server error ${result.code}: ${friendlyError(result.body)}"
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

    /** The single power action, chosen by the server's current state:
     *  online → power off over SSH, offline → send a wake packet. */
    fun sendPower(s: SrvStatus) {
        if (powerBusy) return
        powerBusy = true
        scope.launch {
            val result = if (s.online) Api.sendShutdown(context, s.id) else Api.sendWol(context, s.id)
            powerBusy = false
            val ok = result.code in 200..299 && parseOk(result.body)
            snackbar.showSnackbar(
                when {
                    s.online && ok -> "Shutdown command sent to ${s.name}"
                    s.online -> "Shutdown failed: ${friendlyError(result.body)}"
                    ok -> "Wake packet sent to ${s.name}"
                    else -> "WOL failed: ${friendlyError(result.body)}"
                }
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
                        busy = powerBusy,
                        onPower = { powerTarget = s },
                        onClick = { onOpenServer(s.id) },
                    )
                }
            }
        }
    }

    // Power-action confirmation dialog. The action follows the server's
    // current state: online → power off over SSH, offline → send a wake
    // packet; the confirm button is enabled only for the available
    // direction (SSH configured / MAC set).
    powerTarget?.let { s ->
        AlertDialog(
            onDismissRequest = { powerTarget = null },
            title = { Text(if (s.online) "Power off ${s.name}?" else "Send wake to ${s.name}?") },
            text = {
                Text(
                    when {
                        s.online && s.sshConfigured() ->
                            "The power off command (${s.effectivePoweroffCommand()}) will be sent to ${s.name} (${s.ip}) over SSH. It only works while the server's OS is responsive — a wedged or unreachable server will not power off."
                        s.online ->
                            "This server has no SSH power-off configured (SSH user plus a key path and/or password), so it cannot be powered off from the app."
                        s.mac.isNotEmpty() ->
                            "A Wake-on-LAN packet will be sent to ${s.mac} (${s.ip})."
                        else ->
                            "This server has no MAC address configured, so WOL cannot be sent."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val target = s
                        powerTarget = null
                        sendPower(target)
                    },
                    enabled = (if (s.online) s.sshConfigured() else s.mac.isNotEmpty()) && !powerBusy,
                ) {
                    Text(if (s.online) "Power off" else "Power on", color = if (s.online) OfflineRed else Color.Unspecified)
                }
            },
            dismissButton = {
                TextButton(onClick = { powerTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
fun ServerCard(
    s: SrvStatus,
    busy: Boolean,
    onPower: () -> Unit,
    onClick: () -> Unit,
) {
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
                    if (s.lastWolAt.isNotEmpty()) add("last wake ${relativeTime(s.lastWolAt)}")
                    if (s.lastShutdownAt.isNotEmpty()) add("last shutdown ${relativeTime(s.lastShutdownAt)}")
                }.joinToString("  ·  ")
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // The single power toggle: wake when offline, power off when
            // online — dimmed for the unavailable direction (no MAC / no SSH
            // config), like the old wake/shutdown buttons.
            PowerButton(
                enabled = (if (s.online) s.sshConfigured() else s.mac.isNotEmpty()) && !busy,
                busy = busy,
                online = s.online,
                onClick = onPower,
            )
        }
    }
}

/** Power toggle control, shared by the dashboard and the server detail
 *  page. The stroke-based power symbol (`R.drawable.ic_power`, open circle +
 *  vertical line) is the single power control: server online → tapping
 *  powers it OFF (over SSH), server offline → tapping powers it ON (wake
 *  packet). Tinted by the server's status (green online, red offline) and
 *  dimmed when the action for the current direction is unavailable (no SSH
 *  config / no MAC configured / a power action in flight). */
@Composable
fun PowerButton(enabled: Boolean, busy: Boolean, online: Boolean, onClick: () -> Unit) {
    val tint = if (online) OnlineGreen else OfflineRed
    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_power),
            contentDescription = if (busy) {
                if (online) "Powering off…" else "Waking…"
            } else if (online) {
                if (enabled) "Online — tap to power off" else "Online"
            } else {
                if (enabled) "Offline — tap to wake" else "Offline"
            },
            tint = tint.copy(alpha = if (enabled) 1f else 0.35f),
            modifier = Modifier.size(28.dp),
        )
    }
}
