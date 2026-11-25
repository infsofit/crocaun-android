package com.infsofit.crocaun

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.infsofit.crocaun.transferui.TransferItem
import com.infsofit.crocaun.transferui.windowsCompatibleFileName

internal class CrocTransferUiState {
    var busy by mutableStateOf(false)
    var stagingSend by mutableStateOf(false)
    var exporting by mutableStateOf(false)
    var selectedSendFile by mutableStateOf<TransferItem?>(null)
    var sendPassword by mutableStateOf("")
    var sendStatus by mutableStateOf<String?>(null)
    var sendError by mutableStateOf<String?>(null)
    var receivePassword by mutableStateOf("")
    var receiveStatus by mutableStateOf<String?>(null)
    var receiveError by mutableStateOf<String?>(null)
    var exportStatus by mutableStateOf<String?>(null)
    var receivedItems by mutableStateOf<List<TransferItem>>(emptyList())
    var pendingExport by mutableStateOf<TransferItem?>(null)
}

@Composable
internal fun CrocSendScreen(controller: CrocTransferController, state: CrocTransferUiState) {
    val context = LocalContext.current
    val selectDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            state.sendError = null
            state.sendStatus = context.getString(R.string.transfer_copying_file)
            state.stagingSend = true
            state.selectedSendFile?.let(controller::discard)
            state.selectedSendFile = null
            controller.stageSelectedDocument(uri) { result ->
                state.stagingSend = false
                result.onSuccess { item ->
                    state.selectedSendFile = item
                    state.sendStatus = context.getString(R.string.transfer_file_ready)
                }.onFailure { error ->
                    state.sendStatus = null
                    state.sendError = readableError(context.getString(R.string.transfer_stage_failed), error, state.sendPassword)
                }
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.transfer_send_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.transfer_send_description), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(
            enabled = !state.busy && !state.stagingSend && !state.exporting,
            onClick = { selectDocument.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.AttachFile, contentDescription = null)
            Text(stringResource(R.string.transfer_choose_file), modifier = Modifier.padding(start = 8.dp))
        }
        state.selectedSendFile?.let { item ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.transfer_selected_file), style = MaterialTheme.typography.labelMedium)
                    Text(item.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
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
            enabled = !state.busy && !state.stagingSend && !state.exporting && state.selectedSendFile != null && state.sendPassword.isNotBlank(),
            onClick = {
                val item = state.selectedSendFile ?: return@Button
                state.sendError = null
                state.sendStatus = context.getString(R.string.transfer_connecting)
                state.busy = true
                controller.send(
                    item,
                    state.sendPassword,
                    onStatus = { status -> if (state.busy) state.sendStatus = displayStatus(context, status) },
                    onComplete = { result ->
                        state.busy = false
                        result.onSuccess {
                            state.sendStatus = context.getString(R.string.transfer_send_complete)
                            state.selectedSendFile = null
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

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(R.string.transfer_receive_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.transfer_receive_description), style = MaterialTheme.typography.bodyMedium)
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
                    state.receivePassword,
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
