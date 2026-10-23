package com.polex.app

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The power flow shared by the dashboard (MainScreen) and the server
 * detail page (ServerDetailScreen): the confirmation dialog for the single
 * power control, and the send + result handling. The action follows the
 * server's current state — online → power off over SSH, offline → wake
 * (Wake-on-LAN) — and the confirm button is enabled only for the available
 * direction (SSH configured / MAC set).
 */

/** The single power action, chosen by the server's current state:
 *  online → power off over SSH, offline → send a wake packet. Sends the
 *  request and returns the snackbar message for the outcome: the success
 *  rule (a 2xx with `ok:true`) and the four outcome messages are the same
 *  on both screens. */
suspend fun sendPowerAction(context: Context, s: SrvStatus): String {
    val result = if (s.online) Api.sendShutdown(context, s.id) else Api.sendWol(context, s.id)
    val ok = result.code in 200..299 && parseOk(result.body)
    return when {
        s.online && ok -> "Shutdown command sent to ${s.name}"
        s.online -> "Shutdown failed: ${friendlyError(result.body)}"
        ok -> "Wake packet sent to ${s.name}"
        else -> "WOL failed: ${friendlyError(result.body)}"
    }
}

/** Power-action confirmation dialog. The action follows the server's
 *  current state: online → power off over SSH, offline → send a wake
 *  packet; the confirm button is enabled only for the available
 *  direction (SSH configured / MAC set) and while no action is in flight. */
@Composable
fun PowerConfirmDialog(
    s: SrvStatus,
    busy: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
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
                onClick = onConfirm,
                enabled = (if (s.online) s.sshConfigured() else s.mac.isNotEmpty()) && !busy,
            ) {
                Text(if (s.online) "Power off" else "Power on", color = if (s.online) OfflineRed else Color.Unspecified)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
