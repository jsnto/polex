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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
 * that reuses the settings' add/edit form. Reached by tapping a server card
 * on the dashboard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerDetailScreen(serverId: Int, onBack: () -> Unit, onAuthError: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var status by remember { mutableStateOf<SrvStatus?>(null) }
    var error by remember { mutableStateOf("") }
    var wolBusy by remember { mutableStateOf(false) }
    var shutdownBusy by remember { mutableStateOf(false) }
    var wakeConfirm by remember { mutableStateOf(false) }
    var shutdownConfirm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    // Disconnected state (connection-level fetch failure, code -1): shows
    // the same dedicated full-screen page as the dashboard; the
    // session/token are left untouched.
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
                    error = "Server error ${result.code}: ${result.body}"
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

    fun sendWol() {
        val s = status ?: return
        if (wolBusy || s.mac.isEmpty()) return
        wolBusy = true
        scope.launch {
            val result = Api.sendWol(context, s.id)
            wolBusy = false
            val ok = result.code in 200..299 && parseOk(result.body)
            snackbar.showSnackbar(if (ok) "Wake packet sent to ${s.name}" else "WOL failed: ${result.body}")
        }
    }

    fun sendShutdown() {
        val s = status ?: return
        if (shutdownBusy || !s.sshConfigured()) return
        shutdownBusy = true
        scope.launch {
            val result = Api.sendShutdown(context, s.id)
            shutdownBusy = false
            val ok = result.code in 200..299 && parseOk(result.body)
            snackbar.showSnackbar(
                if (ok) "Shutdown command sent to ${s.name}" else "Shutdown failed: ${result.body}"
            )
        }
    }

    // Edit mode: take over this screen with the shared add/edit form.
    if (editing) {
        EditServerScreen(
            server = status?.let {
                SrvEntry(it.id, it.name, it.ip, it.mac, it.port, it.check, it.sshUser, it.sshKeyPath)
            },
            isNew = false,
            onBack = {
                editing = false
                refresh()
            },
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
                            WakeButton(
                                enabled = s.mac.isNotEmpty() && !wolBusy,
                                busy = wolBusy,
                                online = s.online,
                                onClick = { wakeConfirm = true },
                            )
                            Spacer(Modifier.width(8.dp))
                            ShutdownButton(
                                enabled = s.sshConfigured() && !shutdownBusy,
                                busy = shutdownBusy,
                                onClick = { shutdownConfirm = true },
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
                            if (s.sshConfigured()) "as ${s.sshUser} (key ${s.sshKeyPath})" else "not set (shutdown off)",
                        )
                        OutlinedButton(onClick = { editing = true }) {
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

    // Wake confirmation dialog.
    val confirmTarget = if (wakeConfirm) status else null
    confirmTarget?.let { s ->
        AlertDialog(
            onDismissRequest = { wakeConfirm = false },
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
                        wakeConfirm = false
                        sendWol()
                    },
                    enabled = s.mac.isNotEmpty() && !wolBusy,
                ) {
                    Text("Wake")
                }
            },
            dismissButton = {
                TextButton(onClick = { wakeConfirm = false }) { Text("Cancel") }
            },
        )
    }

    // Shutdown confirmation dialog.
    val shutdownTarget = if (shutdownConfirm) status else null
    shutdownTarget?.let { s ->
        AlertDialog(
            onDismissRequest = { shutdownConfirm = false },
            title = { Text("Shutdown ${s.name}?") },
            text = {
                Text(
                    if (s.sshConfigured()) {
                        "A clean shutdown (systemctl poweroff) will be sent to ${s.name} (${s.ip}) over SSH. It only works while the server's OS is responsive — a wedged or unreachable server will not power off."
                    } else {
                        "This server has no SSH shutdown configured (SSH user + key path), so it cannot be shut down from the app."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        shutdownConfirm = false
                        sendShutdown()
                    },
                    enabled = s.sshConfigured() && !shutdownBusy,
                ) {
                    Text("Shutdown", color = OfflineRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { shutdownConfirm = false }) { Text("Cancel") }
            },
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
