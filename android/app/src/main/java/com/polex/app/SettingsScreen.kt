package com.polex.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Which sub-screen the settings menu shows. */
private enum class SettingsPane { Menu, Backend, Servers }

/**
 * Minimal settings menu: Backend, Servers, Log out.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    var pane by remember { mutableStateOf(SettingsPane.Menu) }
    // Server being edited (null = not on the edit screen). SrvEntry carries
    // its own id, so no extra pair is needed.
    var editTarget by remember { mutableStateOf<SrvEntry?>(null) }

    // editTarget != null takes over the whole settings area (new or edit);
    // id == 0 is the "new server" sentinel (real ids start at 1).
    val editing = editTarget != null

    // Hardware/system back must navigate *within* settings (edit → servers →
    // menu → dashboard) instead of finishing the activity and exiting the app.
    BackHandler {
        when {
            editing -> {
                editTarget = null
                pane = SettingsPane.Servers
            }
            pane != SettingsPane.Menu -> pane = SettingsPane.Menu
            else -> onBack() // dashboard; its default back leaves the app
        }
    }

    when (pane) {
        SettingsPane.Menu -> MenuPane(
            onBackend = { pane = SettingsPane.Backend },
            onServers = { pane = SettingsPane.Servers },
            onLogout = onLogout,
            onBack = onBack,
        )
        SettingsPane.Backend -> if (!editing) BackendScreen(onBack = { pane = SettingsPane.Menu })
        SettingsPane.Servers -> if (!editing) ServersScreen(
            onBack = { pane = SettingsPane.Menu },
            onAdd = { editTarget = SrvEntry(0, "", "", "", 22, "tcp") },
            onEdit = { _, entry -> editTarget = entry },
        )
    }
    if (editing) {
        val target = editTarget!!
        EditServerScreen(
            server = if (target.id == 0) null else target,
            isNew = target.id == 0,
            onBack = {
                editTarget = null
                pane = SettingsPane.Servers
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MenuPane(
    onBackend: () -> Unit,
    onServers: () -> Unit,
    onLogout: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            MenuRow(icon = Icons.Default.Settings, title = "Backend", subtitle = Prefs.base(context), onClick = onBackend)
            MenuRow(icon = Icons.Filled.Dns, title = "Servers", subtitle = "Add, edit or remove servers", onClick = onServers)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
                Text("Log out")
            }
        }
    }
}

@Composable
private fun MenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.Default.KeyboardArrowRight, contentDescription = null)
        }
    }
}

/** Backend URL editor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackendScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var baseUrl by remember { mutableStateOf(Prefs.base(context)) }
    var saved by remember { mutableStateOf(false) }

    fun saveUrl() {
        Prefs.setBase(context, baseUrl)
        saved = true
        // Refresh widget cache against the (possibly new) backend.
        scope.launch {
            val r = Api.safe { Api.get("${Api.base(context)}/api/status") }
            if (r.code == 200) {
                Prefs.setCache(context, r.body)
                ServerWidget.refreshAll(context)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backend") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Backend URL") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { saveUrl() }, enabled = baseUrl.isNotBlank()) {
                            Text("Save")
                        }
                        if (saved) {
                            Spacer(Modifier.width(8.dp))
                            Text("Saved", color = OnlineGreen, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Text(
                "Plain HTTP on your LAN is intentional (usesCleartextTraffic=true).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Server list with Add / Edit / Delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (Int, SrvEntry) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<SrvEntry>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<SrvEntry?>(null) }
    // Measured row heights (px) drive the drag threshold, so a full row of
    // vertical drag moves exactly one slot.
    val rowHeights = remember { HashMap<Int, Int>() }
    // id of the row currently being dragged (0 = none).
    var draggingId by remember { mutableStateOf(0) }

    fun loadServers() {
        scope.launch {
            val result = Api.safe { Api.get("${Api.base(context)}/api/servers") }
            message = if (result.code == 200) {
                servers = parseServerArray(result.body)
                ""
            } else {
                "Cannot load servers (${result.body})"
            }
        }
    }

    LaunchedEffect(Unit) { loadServers() }

    fun deleteServer(s: SrvEntry) {
        scope.launch {
            val result = Api.safe { Api.delete("${Api.base(context)}/api/servers/${s.id}") }
            message = if (result.code in 200..299) "Server deleted." else "Delete failed: ${result.body}"
            loadServers()
        }
    }

    // Persist the current (possibly drag-reordered) order to the backend.
    fun commitOrder() {
        val order = servers.map { it.id }
        scope.launch {
            val result = Api.safe { Api.post("${Api.base(context)}/api/servers/reorder", reorderJson(order)) }
            message = if (result.code in 200..299) "" else "Reorder failed: ${result.body}"
            if (result.code !in 200..299) loadServers() // roll back to server order
        }
    }

    // Moves a server one slot toward `delta` (±1); false when the move would
    // leave the list.
    fun moveServer(index: Int, delta: Int): Boolean {
        val target = index + delta
        if (index !in 0..servers.lastIndex || target !in 0..servers.lastIndex) return false
        val l = servers.toMutableList()
        val item = l.removeAt(index)
        l.add(target, item)
        servers = l
        return true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Servers") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onAdd) {
                        Icon(Icons.Default.Add, contentDescription = "Add server")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (message.isNotEmpty()) {
                item {
                    Text(message, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (servers.isEmpty() && message.isEmpty()) {
                item {
                    Text(
                        "No servers yet — tap + to add one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(servers, key = { it.id }) { s ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onSizeChanged { rowHeights[s.id] = it.height },
                    elevation = if (draggingId == s.id) {
                        CardDefaults.cardElevation(defaultElevation = 8.dp)
                    } else {
                        CardDefaults.cardElevation()
                    },
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Drag handle: reorders the list (one row per full-row
                        // of vertical drag) and commits to the backend on release.
                        Icon(
                            imageVector = Icons.Filled.DragIndicator,
                            contentDescription = "Reorder ${s.name}",
                            tint = if (draggingId == s.id) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier
                                .size(24.dp)
                                .pointerInput(s.id) {
                                    var dragAccum = 0f
                                    var moved = false
                                    detectDragGestures(
                                        onDragStart = {
                                            draggingId = s.id
                                            dragAccum = 0f
                                            moved = false
                                        },
                                        onDragEnd = {
                                            draggingId = 0
                                            if (moved) commitOrder()
                                        },
                                        onDragCancel = {
                                            draggingId = 0
                                            if (moved) commitOrder()
                                        },
                                        onDrag = { change, dragAmount ->
                                            change.consume()
                                            dragAccum += dragAmount.y
                                            val threshold = (rowHeights[s.id] ?: 80) / 2f
                                            // Guard (instead of `return@onDrag`, whose label
                                            // doesn't resolve for suspend lambdas passed
                                            // by named argument) — skip if height unknown.
                                            if (threshold > 0f) {
                                                if (dragAccum >= threshold) {
                                                    val i = servers.indexOfFirst { it.id == s.id }
                                                    if (moveServer(i, +1)) {
                                                        dragAccum -= threshold
                                                        moved = true
                                                    } else {
                                                        dragAccum = 0f
                                                    }
                                                } else if (dragAccum <= -threshold) {
                                                    val i = servers.indexOfFirst { it.id == s.id }
                                                    if (moveServer(i, -1)) {
                                                        dragAccum += threshold
                                                        moved = true
                                                    } else {
                                                        dragAccum = 0f
                                                    }
                                                }
                                            }
                                        },
                                    )
                                },
                        )
                        Column(Modifier.weight(1f).padding(start = 4.dp)) {
                            Text(s.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${s.ip}  ·  ${if (s.check == "ping") "ping" else "tcp :${s.port}"}${if (s.mac.isNotEmpty()) "  ·  ${s.mac}" else "  ·  no MAC (WOL off)"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onEdit(s.id, s) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit ${s.name}")
                        }
                        IconButton(onClick = { deleteTarget = s }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete ${s.name}")
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete server?") },
            text = { Text("\"${target.name}\" (${target.ip}) will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    deleteServer(target)
                }) {
                    Text("Delete", color = OfflineRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

/** New-server / edit-server form (POST or PUT /api/servers). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditServerScreen(
    server: SrvEntry?,          // null = new server
    isNew: Boolean,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(server?.name ?: "") }
    var ip by remember { mutableStateOf(server?.ip ?: "") }
    var mac by remember { mutableStateOf(server?.mac ?: "") }
    var port by remember { mutableStateOf(server?.port?.toString() ?: "22") }
    var usePing by remember { mutableStateOf(server?.check == "ping") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    fun submit() {
        if (busy) return
        busy = true
        message = ""
        scope.launch {
            val json = serverToJson(name, ip, mac, port.toIntOrNull() ?: 22, if (usePing) "ping" else "tcp")
            val result = Api.safe {
                if (isNew || server == null) {
                    Api.post("${Api.base(context)}/api/servers", json)
                } else {
                    Api.put("${Api.base(context)}/api/servers/${server.id}", json)
                }
            }
            busy = false
            if (result.code in 200..299) {
                onBack()
            } else {
                message = "Save failed: ${result.body}"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "Add server" else "Edit server") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = ip,
                        onValueChange = { ip = it },
                        label = { Text("IP address") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = mac,
                        onValueChange = { mac = it.uppercase() },
                        label = { Text("MAC (AA:BB:CC:DD:EE:FF, optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = port,
                        onValueChange = { port = it.filter { c -> c.isDigit() } },
                        label = { Text("Check port (default 22)") },
                        singleLine = true,
                        enabled = !usePing,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Use ping (ICMP)", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Check uptime with ICMP echo instead of a TCP port connect (the check port is ignored).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = usePing, onCheckedChange = { usePing = it })
                    }
                }
            }
            if (message.isNotEmpty()) {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = { submit() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (busy) "Saving…" else if (isNew) "Add server" else "Save changes")
            }
        }
    }
}
