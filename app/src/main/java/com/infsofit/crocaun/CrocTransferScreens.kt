package com.infsofit.crocaun

import android.content.Intent
import android.net.Uri as AndroidUri
import android.os.Environment
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.infsofit.crocaun.transferui.CrocTransferController
import com.infsofit.crocaun.transferui.LanDiscoveryOperation
import com.infsofit.crocaun.transferui.LanPeer
import com.infsofit.crocaun.transferui.RelayServerCheck
import com.infsofit.crocaun.transferui.TransferItem
import com.infsofit.crocaun.transferui.windowsCompatibleFileName

internal class CrocTransferUiState {
    var busy by mutableStateOf(false)
    var stagingSend by mutableStateOf(false)
    var exporting by mutableStateOf(false)
    var selectedSendItems by mutableStateOf<List<TransferItem>>(emptyList())
    var sendPassword by mutableStateOf("")
    var sendStatus by mutableStateOf<String?>(null)
    var sendError by mutableStateOf<String?>(null)
    var manualPaths by mutableStateOf("")
    var manualAccessGranted by mutableStateOf(false)
    var manualAccessError by mutableStateOf<String?>(null)
    var sendDiscoveryBusy by mutableStateOf(false)
    var sendDiscoveryStatus by mutableStateOf<String?>(null)
    var sendDiscoveryOperation by mutableStateOf<LanDiscoveryOperation?>(null)
    var sendPeers by mutableStateOf<List<LanPeer>>(emptyList())
    var sendLanPeer by mutableStateOf<LanPeer?>(null)
    var receivePassword by mutableStateOf("")
    var receiveStatus by mutableStateOf<String?>(null)
    var receiveError by mutableStateOf<String?>(null)
    var receiveDiscoveryBusy by mutableStateOf(false)
    var receiveDiscoveryStatus by mutableStateOf<String?>(null)
    var receiveDiscoveryOperation by mutableStateOf<LanDiscoveryOperation?>(null)
    var receivePeers by mutableStateOf<List<LanPeer>>(emptyList())
    var receiveLanPeer by mutableStateOf<LanPeer?>(null)
    var relayCheckBusy by mutableStateOf(false)
    var relayChecks by mutableStateOf<List<RelayServerCheck>>(emptyList())
    var relayCheckError by mutableStateOf<String?>(null)
    var exportStatus by mutableStateOf<String?>(null)
    var receivedItems by mutableStateOf<List<TransferItem>>(emptyList())
    var pendingExport by mutableStateOf<TransferItem?>(null)
}

@Composable
internal fun CrocSendScreen(controller: CrocTransferController, state: CrocTransferUiState) {
    val context = LocalContext.current
    val selectDocuments = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            state.sendError = null
            state.sendStatus = context.getString(R.string.transfer_copying_files, uris.size)
            state.stagingSend = true
            controller.discard(state.selectedSendItems)
            state.selectedSendItems = emptyList()
            controller.stageSelectedDocuments(uris) { result ->
                state.stagingSend = false
                result.onSuccess { items ->
                    state.selectedSendItems = items
                    state.sendStatus = context.getString(R.string.transfer_files_ready, items.size)
                }.onFailure { error ->
                    state.sendStatus = null
                    state.sendError = readableError(context.getString(R.string.transfer_stage_failed), error, state.sendPassword)
                }
            }
        }
    }
    val allFilesAccessLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        state.manualAccessGranted = Environment.isExternalStorageManager()
    }
    LaunchedEffect(Unit) {
        state.manualAccessGranted = Environment.isExternalStorageManager()
    }

    fun stageManualPaths() {
        state.sendError = null
        state.sendStatus = context.getString(R.string.transfer_manual_staging)
        state.stagingSend = true
        controller.stageManualPaths(state.manualPaths) { result ->
            state.stagingSend = false
            result.onSuccess { items ->
                controller.discard(state.selectedSendItems)
                state.selectedSendItems = items
                state.sendStatus = context.getString(R.string.transfer_files_ready, items.size)
            }.onFailure { error ->
                state.sendStatus = null
                state.sendError = readableError(context.getString(R.string.transfer_stage_failed), error, state.sendPassword)
            }
        }
    }

    fun startSendDiscovery() {
        state.sendError = null
        state.sendPeers = emptyList()
        state.sendDiscoveryStatus = context.getString(R.string.transfer_discovery_scanning)
        state.sendDiscoveryBusy = true
        state.sendDiscoveryOperation = controller.discoverLanPeers("recv") { result ->
            state.sendDiscoveryBusy = false
            state.sendDiscoveryOperation = null
            result.onSuccess { peers ->
                state.sendPeers = peers
                state.sendDiscoveryStatus = context.getString(R.string.transfer_discovery_results, peers.size)
            }.onFailure { error ->
                state.sendDiscoveryStatus = null
                state.sendError = readableError(context.getString(R.string.transfer_discovery_failed), error, state.sendPassword)
            }
        }
    }

    val openAllFilesSettings = {
        val appSettings = Intent(
            AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            AndroidUri.parse("package:${context.packageName}"),
        )
        try {
            allFilesAccessLauncher.launch(appSettings)
        } catch (_: Exception) {
            val generalSettings = Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            runCatching { allFilesAccessLauncher.launch(generalSettings) }
                .onFailure { state.manualAccessError = context.getString(R.string.transfer_manual_access_settings_failed) }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.transfer_send_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.transfer_send_description), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            enabled = !state.busy && !state.stagingSend && !state.exporting,
            onClick = { selectDocuments.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.AttachFile, contentDescription = null)
            Text(stringResource(R.string.transfer_choose_files), modifier = Modifier.padding(start = 8.dp))
        }
        Text(stringResource(R.string.transfer_manual_paths_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.transfer_manual_paths_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            value = state.manualPaths,
            onValueChange = { state.manualPaths = it; state.sendError = null },
            label = { Text(stringResource(R.string.transfer_manual_paths_label)) },
            minLines = 2,
            maxLines = 5,
            enabled = !state.busy && !state.stagingSend,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            enabled = !state.busy && !state.stagingSend && !state.exporting && state.manualPaths.isNotBlank(),
            onClick = { stageManualPaths() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.transfer_stage_manual_paths))
        }
        Text(stringResource(R.string.transfer_manual_access_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!state.manualAccessGranted) {
            OutlinedButton(
                enabled = !state.busy && !state.stagingSend,
                onClick = { openAllFilesSettings() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.transfer_manual_access_action))
            }
        } else {
            Text(stringResource(R.string.transfer_manual_access_enabled), color = MaterialTheme.colorScheme.primary)
        }
        state.manualAccessError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Text(stringResource(R.string.transfer_local_devices_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.transfer_find_exchange_receivers_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(
            enabled = !state.busy && !state.sendDiscoveryBusy,
            onClick = { startSendDiscovery() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (state.sendDiscoveryBusy) R.string.transfer_discovery_scanning_button else R.string.transfer_find_devices))
        }
        if (state.sendDiscoveryBusy) {
            OutlinedButton(
                onClick = {
                    state.sendDiscoveryOperation?.cancel()
                    state.sendDiscoveryOperation = null
                    state.sendDiscoveryBusy = false
                    state.sendDiscoveryStatus = context.getString(R.string.transfer_discovery_cancelled)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.transfer_discovery_cancel)) }
        }
        state.sendDiscoveryStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.sendPeers.take(MAX_VISIBLE_PEERS).forEach { peer ->
            OutlinedButton(
                enabled = !state.busy,
                onClick = { state.sendLanPeer = peer },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(peerLabel(peer, state.sendLanPeer == peer))
            }
        }
        state.sendLanPeer?.let { peer ->
            Text(stringResource(R.string.transfer_local_target_selected, peer.address, peer.port), color = MaterialTheme.colorScheme.primary)
            OutlinedButton(enabled = !state.busy, onClick = { state.sendLanPeer = null }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.transfer_use_public_relays))
            }
        }
        if (state.selectedSendItems.isNotEmpty()) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.transfer_selected_files, state.selectedSendItems.size), style = MaterialTheme.typography.labelMedium)
                    state.selectedSendItems.take(MAX_VISIBLE_SELECTED_FILES).forEach { item ->
                        Text(item.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    }
                    val hiddenCount = state.selectedSendItems.size - MAX_VISIBLE_SELECTED_FILES
                    if (hiddenCount > 0) Text(stringResource(R.string.transfer_more_files, hiddenCount), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedTextField(
            value = state.sendPassword,
            onValueChange = { state.sendPassword = it; state.sendError = null },
            label = { Text(stringResource(R.string.transfer_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !state.busy && !state.stagingSend && !state.exporting && state.selectedSendItems.isNotEmpty() && state.sendPassword.isNotBlank(),
            onClick = {
                val items = state.selectedSendItems
                if (items.isEmpty()) return@Button
                state.sendError = null
                state.sendStatus = context.getString(R.string.transfer_connecting)
                state.busy = true
                controller.send(
                    items = items,
                    password = state.sendPassword,
                    relayAddress = state.sendLanPeer?.relayAddress().orEmpty(),
                    onStatus = { status -> if (state.busy) state.sendStatus = displayStatus(context, status) },
                    onComplete = { result ->
                        state.busy = false
                        result.onSuccess {
                            state.sendStatus = context.getString(R.string.transfer_send_complete, items.size)
                            state.selectedSendItems = emptyList()
                            state.sendPassword = ""
                        }.onFailure { error ->
                            state.sendStatus = null
                            state.sendError = readableError(context.getString(R.string.transfer_send_failed), error, state.sendPassword)
                        }
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (state.busy) R.string.transfer_sending else R.string.transfer_send_action))
        }
        state.sendStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.sendError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.transfer_keep_open), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CrocReceiveScreen(controller: CrocTransferController, state: CrocTransferUiState) {
    val context = LocalContext.current
    val exportDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val item = state.pendingExport
        state.pendingExport = null
        if (uri != null && item != null) {
            state.exporting = true
            state.exportStatus = null
            controller.exportToDocument(item, uri) { result ->
                state.exporting = false
                result.onSuccess {
                    state.receivedItems = state.receivedItems.filterNot { it.file.absolutePath == item.file.absolutePath }
                    state.exportStatus = context.getString(R.string.transfer_export_complete, item.displayName)
                }.onFailure { error ->
                    state.exportStatus = readableError(context.getString(R.string.transfer_export_failed), error, state.receivePassword)
                }
            }
        }
    }

    fun startReceiveDiscovery() {
        state.receiveError = null
        state.receivePeers = emptyList()
        state.receiveDiscoveryStatus = context.getString(R.string.transfer_discovery_scanning)
        state.receiveDiscoveryBusy = true
        state.receiveDiscoveryOperation = controller.discoverLanPeers("send") { result ->
            state.receiveDiscoveryBusy = false
            state.receiveDiscoveryOperation = null
            result.onSuccess { peers ->
                state.receivePeers = peers
                state.receiveDiscoveryStatus = context.getString(R.string.transfer_discovery_results, peers.size)
            }.onFailure { error ->
                state.receiveDiscoveryStatus = null
                state.receiveError = readableError(context.getString(R.string.transfer_discovery_failed), error, state.receivePassword)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.transfer_receive_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.transfer_receive_description), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.transfer_local_devices_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.transfer_find_waiting_senders_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(
            enabled = !state.busy && !state.receiveDiscoveryBusy,
            onClick = { startReceiveDiscovery() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (state.receiveDiscoveryBusy) R.string.transfer_discovery_scanning_button else R.string.transfer_find_devices))
        }
        if (state.receiveDiscoveryBusy) {
            OutlinedButton(
                onClick = {
                    state.receiveDiscoveryOperation?.cancel()
                    state.receiveDiscoveryOperation = null
                    state.receiveDiscoveryBusy = false
                    state.receiveDiscoveryStatus = context.getString(R.string.transfer_discovery_cancelled)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.transfer_discovery_cancel)) }
        }
        state.receiveDiscoveryStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.receivePeers.take(MAX_VISIBLE_PEERS).forEach { peer ->
            OutlinedButton(
                enabled = !state.busy,
                onClick = { state.receiveLanPeer = peer },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(peerLabel(peer, state.receiveLanPeer == peer)) }
        }
        state.receiveLanPeer?.let { peer ->
            Text(stringResource(R.string.transfer_local_target_selected, peer.address, peer.port), color = MaterialTheme.colorScheme.primary)
            OutlinedButton(enabled = !state.busy, onClick = { state.receiveLanPeer = null }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.transfer_use_public_relays))
            }
        }
        OutlinedTextField(
            value = state.receivePassword,
            onValueChange = { state.receivePassword = it; state.receiveError = null },
            label = { Text(stringResource(R.string.transfer_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !state.busy && !state.stagingSend && !state.exporting && state.receivePassword.isNotBlank(),
            onClick = {
                state.receiveError = null
                state.receiveStatus = context.getString(R.string.transfer_connecting)
                state.busy = true
                controller.receive(
                    password = state.receivePassword,
                    relayAddress = state.receiveLanPeer?.relayAddress().orEmpty(),
                    onStatus = { status -> if (state.busy) state.receiveStatus = displayStatus(context, status) },
                    onComplete = { result ->
                        state.busy = false
                        result.onSuccess { items ->
                            state.receivedItems = state.receivedItems + items
                            state.receiveStatus = context.getString(R.string.transfer_receive_complete, items.size)
                            state.receivePassword = ""
                        }.onFailure { error ->
                            state.receiveStatus = null
                            state.receiveError = readableError(context.getString(R.string.transfer_receive_failed), error, state.receivePassword)
                        }
                    },
                )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (state.busy) R.string.transfer_receiving else R.string.transfer_receive_action))
        }
        state.receiveStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.receiveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.exportStatus?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        if (state.receivedItems.isNotEmpty()) {
            Text(stringResource(R.string.transfer_received_files), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.receivedItems, key = { it.file.absolutePath }) { item ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(item.displayName, fontWeight = FontWeight.Medium)
                                if (item.relativePath != item.displayName) {
                                    Text(item.relativePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            OutlinedButton(
                                enabled = !state.busy && !state.exporting,
                                onClick = {
                                    state.pendingExport = item
                                    exportDocument.launch(windowsCompatibleFileName(item.displayName))
                                },
                            ) {
                                Icon(Icons.Filled.Download, contentDescription = null)
                                Text(stringResource(R.string.transfer_export), modifier = Modifier.padding(start = 6.dp))
                            }
                        }
                    }
                }
            }
        }
        if (state.exporting) Text(stringResource(R.string.transfer_exporting), color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.transfer_keep_open), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun CrocSettingsScreen(controller: CrocTransferController, state: CrocTransferUiState) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.nav_settings), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.transfer_relay_check_description), style = MaterialTheme.typography.bodyMedium)
        Button(
            enabled = !state.relayCheckBusy,
            onClick = {
                state.relayCheckBusy = true
                state.relayCheckError = null
                state.relayChecks = emptyList()
                controller.checkRelayServers { result ->
                    state.relayCheckBusy = false
                    result.onSuccess { checks -> state.relayChecks = checks }
                        .onFailure { error -> state.relayCheckError = readableError(context.getString(R.string.transfer_relay_check_failed), error, "") }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(if (state.relayCheckBusy) R.string.transfer_relay_check_running else R.string.transfer_relay_check_action))
        }
        state.relayCheckError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.relayChecks.forEach { check ->
            val result = if (check.available) {
                context.getString(R.string.transfer_relay_available, check.rttMillis ?: 0L)
            } else {
                context.getString(R.string.transfer_relay_unavailable, check.error.orEmpty())
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(check.address, fontWeight = FontWeight.SemiBold)
                    Text(result, color = if (check.available) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun LanPeer.relayAddress(): String = "$address:$port"

private fun peerLabel(peer: LanPeer, selected: Boolean): String {
    val selection = if (selected) "✓ " else ""
    val role = if (peer.role == "recv") " — ${peer.address}:${peer.port}" else " — ${peer.address}:${peer.port}"
    return "$selection${peer.name}$role"
}

private fun displayStatus(context: android.content.Context, status: String): String {
    return if (status.startsWith("connecting", ignoreCase = true)) {
        context.getString(R.string.transfer_connecting)
    } else {
        status
    }
}

private fun readableError(prefix: String, error: Throwable, password: String): String {
    val message = error.message.orEmpty()
    val redacted = if (password.isEmpty()) message else message.replace(password, "••••")
    val detail = redacted.ifBlank { error.javaClass.simpleName }
    return "$prefix: $detail"
}

private const val MAX_VISIBLE_SELECTED_FILES = 5
private const val MAX_VISIBLE_PEERS = 5
