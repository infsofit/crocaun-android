package com.infsofit.crocaun

import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.infsofit.crocaun.chat.ChatFile
import com.infsofit.crocaun.chat.ChatMessage
import com.infsofit.crocaun.chat.LanChatClient
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ChatScreen(client: LanChatClient, startupError: String?, revision: Int) {
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    var selectedRoomId by remember(client) { mutableStateOf<String?>(null) }
    var roomLabel by remember { mutableStateOf("Room") }
    var roomPassword by remember { mutableStateOf("") }
    var peerAddress by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDownload by remember { mutableStateOf<ChatFile?>(null) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = pendingDownload
        if (uri != null && file != null) {
            Thread {
                val result = client.downloadFile(file, uri)
                mainHandler.post { error = result.exceptionOrNull()?.message ?: "File saved." }
            }.start()
        }
        pendingDownload = null
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val roomId = selectedRoomId
        if (uri != null && roomId != null) {
            Thread {
                val result = client.sendFile(roomId, uri)
                mainHandler.post { error = result.exceptionOrNull()?.message ?: "File offer sent." }
            }.start()
        }
    }

    val rooms = remember(revision) { client.roomLabels() }
    LaunchedEffect(revision, rooms) {
        if (selectedRoomId == null && rooms.isNotEmpty()) selectedRoomId = rooms.first().first
    }
    val selected = selectedRoomId?.let { id -> rooms.firstOrNull { it.first == id } }
    val messages = remember(revision, selectedRoomId) { selectedRoomId?.let(client::messages).orEmpty() }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("LAN chat", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            text = if (startupError != null) "Chat listener unavailable: $startupError" else "${client.peerCount()} connected · TCP 29014",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = peerAddress,
                onValueChange = { peerAddress = it },
                label = { Text("Windows IP[:port]") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { error = client.connect(peerAddress).exceptionOrNull()?.message ?: "Connecting…" }) {
                Text("Connect")
            }
        }
        Text(
            "Devices must be on a reachable LAN/VPN. Automatic discovery and Internet chat are not enabled yet.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(roomLabel, { roomLabel = it }, label = { Text("Room") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(roomPassword, { roomPassword = it }, label = { Text("Password") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = {
                val result = client.addRoom(roomLabel, roomPassword)
                selectedRoomId = result.getOrNull() ?: selectedRoomId
                error = result.exceptionOrNull()?.message
            }) { Text("Join") }
        }
        if (rooms.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rooms.forEach { (id, label) ->
                    OutlinedButton(onClick = { selectedRoomId = id }) {
                        Text(if (id == selectedRoomId) "• $label" else label)
                    }
                }
            }
        }
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (messages.isEmpty()) {
                item { Text("No messages in ${selected?.second ?: "this room"} yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(messages, key = { it.id }) { message ->
                ChatMessageCard(message) { file ->
                    pendingDownload = file
                    saveFile.launch(file.name.ifBlank { "crocaun-file" })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Message") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
                enabled = selectedRoomId != null,
            )
            IconButton(enabled = selectedRoomId != null, onClick = { pickFile.launch(arrayOf("*/*")) }) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Attach file")
            }
            IconButton(enabled = selectedRoomId != null && draft.isNotBlank(), onClick = {
                val roomId = selectedRoomId ?: return@IconButton
                val result = client.sendText(roomId, draft)
                error = result.exceptionOrNull()?.message
                if (result.isSuccess) draft = ""
            }) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
internal fun ChatHistoryScreen(client: LanChatClient, revision: Int) {
    val rooms = remember(revision) { client.roomLabels() }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Chat history", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        if (rooms.isEmpty()) {
            Text("Join a chat room to load its encrypted local history.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                rooms.forEach { (id, label) ->
                    item(key = "room-$id") {
                        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                    items(client.messages(id).takeLast(250), key = { "$id-${it.id}" }) { message ->
                        ChatHistoryLine(message)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatHistoryLine(message: ChatMessage) {
    val content = if (message.kind == "file" && message.file != null) {
        "[file] ${message.file.name} (${android.text.format.Formatter.formatFileSize(LocalContext.current, message.file.size)})"
    } else message.text.orEmpty()
    Text(
        "${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(message.timestamp))}  ${message.name}: $content",
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ChatMessageCard(message: ChatMessage, onDownload: (ChatFile) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message.name.ifBlank { "?" }, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(message.timestamp)), style = MaterialTheme.typography.labelSmall)
            }
            if (message.kind == "file" && message.file != null) {
                val file = message.file
                Text("${file.name} · ${android.text.format.Formatter.formatFileSize(LocalContext.current, file.size)}")
                if (!file.token.isNullOrBlank() && file.addresses.isNotEmpty()) {
                    OutlinedButton(onClick = { onDownload(file) }) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Save file")
                    }
                } else {
                    Text("No direct LAN download address is available.", style = MaterialTheme.typography.bodySmall)
                }
                if (!message.text.isNullOrBlank()) Text(message.text)
            } else if (message.kind == "msg") {
                Text(message.text.orEmpty())
            }
        }
    }
}
