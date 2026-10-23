package com.polex.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dedicated per-server page: live status (online dot, latency, last checked,
 * last wake) plus the configured settings (read-only) with an Edit button
 * that reuses the settings' add/edit form, whose Danger zone carries the
 * destructive remove-server action. Reached by tapping a server card on the
 * dashboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailScreen(serverId: Int, onBack: () -> Unit, onAuthError: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var status by remember { mutableStateOf<SrvStatus?>(null) }
    var error by remember { mutableStateOf("") }
    // The single power control is in flight (wake packet or power-off
    // command being sent).
    var powerBusy by remember { mutableStateOf(false) }
    var powerConfirm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    // Frozen copy of the server entry the edit form is showing, captured
    // when Edit settings is tapped. The form must not follow the live
    // `status` poll: if the server disappears (deleted elsewhere) while the
    // form is open, following the poll would drop the Danger zone and
    // silently turn the form into a new-server form whose Save would POST a
    // duplicate. With a frozen entry the form stays an edit form — Save and
    // Remove hit the (now missing) id and surface the backend's error.
    var editEntry by remember { mutableStateOf<SrvEntry?>(null) }
    // Disconnected state (connection-level fetch failure, code -1): shows
    // the dedicated full-screen page (the dashboard keeps its list, grayed
    // out, instead); the session/token are left untouched.
    val tracker = remember { RetryTracker() }
    var disconnected by remember { mutableStateOf(false) }
    var retryAttempts by remember { mutableIntStateOf(0) }
    // True while a fetch cycle (auto-retries) is in flight.
    var cycling by remember { mutableStateOf(false) }

    fun refresh() {
        if (cycling) return
        cycling = true
        retryAttempts = 0
        scope.launch {
            val result = runFetchCycle(
                tracker,
                onAttempt = { n ->
                    // Silent while the app is connected: the screen only
                    // appears after the cycle exhausts (below). While it is
                    // already visible (a manual-Retry cycle) keep its live
                    // progress.
                    if (disconnected) retryAttempts = n
                },
            ) { Api.safe { Api.get("${Api.base(context)}/api/status") } }
            cycling = false
            when {
                result.code == 200 -> {
                    tracker.reset()
                    retryAttempts = 0
                    disconnected = false
                    status = parseStatusArray(result.body).firstOrNull { it.id == serverId }
                    error = ""
                }
                Disconnect.requiresReauth(result.code) -> {
                    // Token rejected by a REACHABLE backend: back to login.
                    disconnected = false
                    onAuthError()
                    return@launch
                }
                // The screen appears only when the cycle ends at the
                // connection level — i.e. only after every silent
                // auto-retry failed. Its Retry button starts a new cycle.
                Disconnect.screenVisibleAfterCycle(result.code) -> {
                    disconnected = true
                    retryAttempts = tracker.attempts
                }
                else -> {
                    // Reachable backend, other error: keep the inline bar.
                    disconnected = false
                    error = "Server error ${result.code}: ${friendlyError(result.body)}"
                }
            }
        }
    }

    // System/hardware back must return to the dashboard (or out of the edit
    // form) instead of finishing the activity. (Declared after `refresh` —
    // local functions are only in scope from their declaration onward.)
    BackHandler {
        if (editing) {
            editing = false
            refresh()
        } else {
            onBack()
        }
    }

    // Keep the status live while this page is open (paused while
    // disconnected; recovery is via the Retry button instead).
    LaunchedEffect(Unit) {
        while (true) {
            if (!disconnected) refresh()
            delay(15_000)
        }
    }

    /** The single power action, chosen by the server's current state:
     *  online → power off over SSH, offline → send a wake packet. The send
     *  and the result/snackbar handling are the shared power flow (Power.kt). */
    fun sendPower() {
        val s = status ?: return
        if (powerBusy) return
        // The direction must be available (SSH configured / MAC set) — the
        // dialog's confirm button is gated on the same rule.
        if (s.online) {
            if (!s.sshConfigured()) return
        } else {
            if (s.mac.isEmpty()) return
        }
        powerBusy = true
        scope.launch {
            val message = sendPowerAction(context, s)
            powerBusy = false
            snackbar.showSnackbar(message)
        }
    }

    // Edit mode: take over this screen with the shared add/edit form, fed
    // by the frozen [editEntry] (see its declaration). The form's Danger
    // zone carries the destructive remove-server action; after a
    // successful delete go straight back to the dashboard — the server no
    // longer exists, so there is no in-page state to refresh.
    if (editing) {
        EditServerScreen(
            server = editEntry,
            isNew = false,
            onBack = {
                editing = false
                refresh()
            },
            onDeleted = onBack,
            onAuthError = onAuthError,
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(status?.name ?: "Server") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                onBack = onBack,
                modifier = Modifier.padding(pad),
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
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

            status?.let { s ->
                // ---- status ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "●",
                                color = if (s.online) OnlineGreen else OfflineRed,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = if (s.online) "Online" else "Offline",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = if (s.online) OnlineGreen else OfflineRed,
                            )
                            Spacer(Modifier.weight(1f))
                            // The single power toggle: wake when offline,
                            // power off when online — dimmed for the
                            // unavailable direction, like on the dashboard.
                            PowerButton(
                                enabled = (if (s.online) s.sshConfigured() else s.mac.isNotEmpty()) && !powerBusy,
                                busy = powerBusy,
                                online = s.online,
                                onClick = { powerConfirm = true },
                            )
                        }
                        Text(
                            text = if (s.online) "${s.latencyMs} ms" else "unreachable",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = buildList {
                                add("checked ${relativeTime(s.lastChecked)}")
                                if (s.lastWolAt.isNotEmpty()) add("last wake ${relativeTime(s.lastWolAt)}")
                                if (s.lastShutdownAt.isNotEmpty()) add("last shutdown ${relativeTime(s.lastShutdownAt)}")
                            }.joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // ---- settings ----
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Settings",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        DetailRow("IP", s.ip)
                        DetailRow("MAC", if (s.mac.isNotEmpty()) s.mac else "not set (WOL off)")
                        DetailRow("Check", if (s.check == "ping") "ping (ICMP)" else "TCP :${s.port}")
                        DetailRow(
                            "SSH",
                            if (s.sshConfigured()) {
                                val creds = buildList {
                                    if (s.sshKeyPath.isNotEmpty()) add("key ${s.sshKeyPath}")
                                    if (s.sshPassword.isNotEmpty()) add("password")
                                }.joinToString(" + ")
                                "as ${s.sshUser} ($creds)"
                            } else {
                                "not set (power off off)"
                            },
                        )
                        if (s.sshConfigured()) {
                            DetailRow("Power off", "sends ${s.effectivePoweroffCommand()} over SSH")
                        }
                        // The destructive remove-server action lives in the
                        // edit form's Danger zone, not here.
                        OutlinedButton(
                            onClick = {
                                editEntry = SrvEntry(
                                    s.id, s.name, s.ip, s.mac, s.port, s.check,
                                    s.sshUser, s.sshKeyPath, s.sshPassword, s.poweroffCommand,
                                )
                                editing = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Edit settings")
                        }
                    }
                }
            }

            if (status == null && error.isEmpty()) {
                Text("Server not found.", style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    // Power-action confirmation dialog — the shared power flow (Power.kt):
    // the action follows the server's current state, and the confirm button
    // is enabled only for the available direction (SSH configured / MAC set).
    val confirmTarget = if (powerConfirm) status else null
    confirmTarget?.let { s ->
        PowerConfirmDialog(
            s,
            busy = powerBusy,
            onConfirm = {
                powerConfirm = false
                sendPower()
            },
            onDismiss = { powerConfirm = false },
        )
    }

}

/** A label/value row for the settings section. */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
