package com.infsofit.crocaun.chat

import android.content.Context
import android.net.Uri
import android.os.Build
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Exact data shapes used by the Windows Crocaun local-chat protocol. */
data class ChatFile(
    @SerializedName("name") val name: String,
    @SerializedName("size") val size: Long,
    @SerializedName("tok") val token: String? = null,
    @SerializedName("addrs") val addresses: List<String> = emptyList(),
    @SerializedName("l") val link: String? = null,
    @SerializedName("k") val key: String? = null,
)

data class ChatMessage(
    @SerializedName("u") val uid: String,
    @SerializedName("n") val name: String,
    @SerializedName("ts") val timestamp: Long,
    @SerializedName("k") val kind: String,
    @SerializedName("x") val text: String? = null,
    @SerializedName("f") val file: ChatFile? = null,
    @Transient val id: String = "",
)

data class ChatFrame(
    @SerializedName("i") val id: String,
    @SerializedName("ts") val timestamp: Long,
    @SerializedName("c") val content: String,
)

/** Small client for Windows' newline-delimited TCP chat protocol (port 29014). */
class LanChatClient(
    context: Context,
    private val onChanged: () -> Unit = {},
) : Closeable {
    companion object {
        const val TCP_PORT = 29014
        private const val MAX_LINE_BYTES = 2 * 1024 * 1024
        private const val MAX_HISTORY = 5000
        private const val CHAT_SALT = "crocau-chat-v1"
        private val gson = Gson()
        private val random = SecureRandom()

        internal fun deriveRoomKey(password: String): ByteArray = pbkdf2(
            password.toByteArray(StandardCharsets.UTF_8),
            CHAT_SALT.toByteArray(StandardCharsets.UTF_8), 20_000, 32,
        )

        internal fun roomIdForKey(key: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(key + "|room-id".toByteArray(StandardCharsets.UTF_8))
            return digest.take(8).joinToString("") { "%02x".format(it) }
        }

        private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(password, "HmacSHA256"))
            val block = salt + byteArrayOf(0, 0, 0, 1)
            var u = mac.doFinal(block)
            val result = u.copyOf()
            for (round in 1 until iterations) {
                u = mac.doFinal(u)
                for (i in result.indices) result[i] = (result[i].toInt() xor u[i].toInt()).toByte()
            }
            return result.copyOf(length)
        }
    }

    private data class Room(
        val label: String,
        val id: String,
        val key: ByteArray,
        val historyFile: File,
        val messages: MutableList<ChatMessage> = mutableListOf(),
        val frames: LinkedHashMap<String, ChatFrame> = linkedMapOf(),
    )

    private data class Peer(val socket: Socket, val output: OutputStream, val id: String, val rooms: MutableSet<String>)

    private val appContext = context.applicationContext
    private val dataDir = File(appContext.filesDir, "chat-lan").apply { mkdirs() }
    private val uid = appContext.getSharedPreferences("crocaun-chat", Context.MODE_PRIVATE)
        .getString("uid", null)?.takeIf { it.isNotBlank() } ?: randomHex(8).also {
            appContext.getSharedPreferences("crocaun-chat", Context.MODE_PRIVATE).edit().putString("uid", it).apply()
        }
    private val displayName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android"
    private val executor = Executors.newCachedThreadPool()
    private val rooms = ConcurrentHashMap<String, Room>()
    private val peers = ConcurrentHashMap<String, Peer>()
    private val files = ConcurrentHashMap<String, File>().apply {
        val index = File(dataDir, "files.json")
        if (index.isFile) runCatching {
            val type = object : TypeToken<Map<String, String>>() {}.type
            val saved: Map<String, String> = gson.fromJson(index.readText(StandardCharsets.UTF_8), type)
            saved.forEach { (token, path) -> File(path).takeIf { it.isFile }?.let { put(token, it) } }
        }
    }
    private val nodeId = randomHex(6)
    @Volatile private var server: ServerSocket? = null
    @Volatile private var closed = false

    fun start(): Result<Unit> = runCatching {
        if (server != null) return@runCatching Unit
        val listener = ServerSocket(TCP_PORT, 32, InetAddress.getByName("0.0.0.0"))
        server = listener
        executor.execute {
            while (!closed) {
                try {
                    val socket = listener.accept()
                    executor.execute { serve(socket) }
                } catch (_: Exception) {
                    if (!closed) onChanged()
                }
            }
        }
    }

    fun addRoom(label: String, password: String): Result<String> = runCatching {
        require(password.isNotEmpty()) { "Enter a chat password." }
        val key = deriveRoomKey(password)
        val id = roomIdForKey(key)
        val room = rooms.computeIfAbsent(id) {
            val history = File(File(dataDir, "history"), "$id.log").apply { parentFile?.mkdirs() }
            val created = Room(label.ifBlank { id }, id, key, history)
            loadHistory(created)
            created
        }
        announceRooms()
        post(room, ChatMessage(uid, displayName, System.currentTimeMillis(), "hi"))
        onChanged()
        room.id
    }

    fun roomLabels(): List<Pair<String, String>> = rooms.values
        .map { it.id to it.label }.sortedBy { it.second.lowercase() }

    fun messages(roomId: String): List<ChatMessage> = rooms[roomId]?.messages?.toList().orEmpty()

    fun peerCount(): Int = peers.size

    fun connect(address: String): Result<Unit> = runCatching {
        val raw = address.trim()
        require(raw.isNotEmpty()) { "Enter an IP address or host name." }
        val endpoint = if (raw.contains(":")) raw else "$raw:$TCP_PORT"
        val split = endpoint.lastIndexOf(':')
        val host = endpoint.substring(0, split).removePrefix("[").removeSuffix("]")
        val port = endpoint.substring(split + 1).toIntOrNull() ?: TCP_PORT
        require(port in 1..65535) { "Invalid port." }
        executor.execute {
            try {
                val socket = Socket()
                socket.connect(java.net.InetSocketAddress(host, port), 5000)
                serve(socket)
            } catch (_: Exception) {
                onChanged()
            }
        }
    }

    fun sendText(roomId: String, text: String): Result<Unit> = runCatching {
        val cleaned = text.trim()
        require(cleaned.isNotEmpty()) { "Message is empty." }
        require(cleaned.codePointCount(0, cleaned.length) <= 8000) { "Message is too long (maximum 8000 characters)." }
        val room = rooms[roomId] ?: error("Select a chat room first.")
        post(room, ChatMessage(uid, displayName, System.currentTimeMillis(), "msg", text = cleaned))
        onChanged()
    }

    /** Copies SAF content into private staging storage, then announces the Windows-compatible LAN file offer. */
    fun sendFile(roomId: String, uri: Uri, note: String? = null): Result<Unit> = runCatching {
        val room = rooms[roomId] ?: error("Select a chat room first.")
        val resolver = appContext.contentResolver
        val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: "shared-file"
        val staged = File(File(dataDir, "outgoing").apply { mkdirs() }, randomHex(16)).also { target ->
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open the selected document." }.use { source ->
                    target.outputStream().use { output -> source.copyTo(output) }
                }
            }
        }
        val token = randomHex(16)
        files[token] = staged
        synchronized(files) {
            File(dataDir, "files.json").writeText(gson.toJson(files.mapValues { it.value.absolutePath }), StandardCharsets.UTF_8)
        }
        val message = ChatMessage(
            uid, displayName, System.currentTimeMillis(), "file",
            text = note?.takeIf { it.isNotBlank() },
            file = ChatFile(name, staged.length(), token, localAddresses()),
        )
        post(room, message)
        onChanged()
    }

    /** Streams a Windows-compatible `get` request into a SAF destination URI. */
    fun downloadFile(file: ChatFile, destination: Uri): Result<Unit> = runCatching {
        require(!file.token.isNullOrBlank()) { "This file offer has no LAN download token." }
        require(file.addresses.isNotEmpty()) { "The sender did not advertise a reachable address." }
        var failure: Exception? = null
        for (address in file.addresses) {
            try {
                val socket = Socket()
                val lastColon = address.lastIndexOf(':')
                val host = address.substring(0, lastColon).removePrefix("[").removeSuffix("]")
                val port = address.substring(lastColon + 1).toInt()
                socket.connect(java.net.InetSocketAddress(host, port), 5000)
                socket.soTimeout = 15_000
                socket.use { connection ->
                    val input = connection.getInputStream()
                    val output = connection.getOutputStream()
                    // Windows sends hello immediately after accept; its server reads our get frame next.
                    writeLine(output, wire("get", "tok" to file.token))
                    var response: JsonObject? = null
                    for (attempt in 0 until 3) {
                        val line = readLine(input) ?: error("The sender closed the connection.")
                        val obj = gson.fromJson(line, JsonObject::class.java)
                        if (obj.get("t")?.asString == "file") {
                            response = obj
                            break
                        }
                    }
                    val header = response ?: error("The sender did not return a file response.")
                    require(header.get("ok")?.asBoolean == true) { "The sender no longer offers this file." }
                    val size = header.get("size")?.asLong ?: error("Missing file size.")
                    connection.soTimeout = 0
                    val outputDocument = appContext.contentResolver.openOutputStream(destination)
                        ?: error("Cannot open the destination document.")
                    outputDocument.use { dest -> copyExactly(input, dest, size) }
                }
                return@runCatching Unit
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: IllegalStateException("Download failed.")
    }

    override fun close() {
        closed = true
        runCatching { server?.close() }
        peers.values.forEach { runCatching { it.socket.close() } }
        executor.shutdownNow()
    }

    private fun serve(socket: Socket) {
        var peer: Peer? = null
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            writeLine(output, helloWire())
            val first = readLine(input)?.let { gson.fromJson(it, JsonObject::class.java) } ?: return
            val type = first.get("t")?.asString.orEmpty()
            if (type == "get") {
                serveGet(socket, output, first.get("tok")?.asString.orEmpty())
                return
            }
            if (type != "hello") return
            val id = first.get("id")?.asString.orEmpty()
            if (id.isBlank() || id == nodeId) return
            socket.soTimeout = 0
            val shared = first.getAsJsonArray("rooms")?.mapNotNull { it.asString }?.toMutableSet() ?: mutableSetOf()
            peer = Peer(socket, output, id, shared)
            peers[id]?.socket?.close()
            peers[id] = peer
            syncWith(peer)
            onChanged()

            while (!closed) {
                val line = readLine(input) ?: break
                val wire = gson.fromJson(line, JsonObject::class.java) ?: continue
                when (wire.get("t")?.asString) {
                    "who" -> onChanged()
                    "rooms" -> {
                        peer.rooms.clear()
                        wire.getAsJsonArray("rooms")?.forEach { peer.rooms.add(it.asString) }
                        syncWith(peer)
                    }
                    "have" -> {
                        val room = rooms[wire.get("r")?.asString]
                        if (room != null) {
                            val have = wire.getAsJsonArray("ids")?.map { it.asString }?.toSet().orEmpty()
                            room.frames.values.toList().asReversed().filter { it.id !in have }.take(400).forEach { frame -> sendFrame(peer, room.id, frame) }
                        }
                    }
                    "m" -> {
                        val roomId = wire.get("r")?.asString ?: continue
                        val room = rooms[roomId] ?: continue
                        val frame = ChatFrame(
                            wire.get("i")?.asString.orEmpty(),
                            wire.get("ts")?.asLong ?: 0L,
                            wire.get("c")?.asString.orEmpty(),
                        )
                        val message = addFrame(room, frame, persist = true) ?: continue
                        broadcast(roomId, frame, peer)
                        if (message.kind !in setOf("hi", "clear")) onChanged()
                    }
                }
            }
        } catch (_: SocketTimeoutException) {
            // Incomplete peer handshake; close it without poisoning the UI.
        } catch (_: Exception) {
            // Broken/disconnected LAN peers are expected; the UI reflects the remaining peer set.
        } finally {
            if (peer != null && peers[peer.id] === peer) peers.remove(peer.id)
            runCatching { socket.close() }
            onChanged()
        }
    }

    private fun helloWire(): JsonObject = wire(
        "hello",
        "id" to nodeId,
        "u" to uid,
        "n" to displayName,
        "port" to TCP_PORT,
        "addrs" to localAddressesJson(),
        "rooms" to rooms.keys.toList(),
        "gossip" to emptyList<String>(),
    )

    private fun announceRooms() {
        val roomIds = rooms.keys.toList()
        peers.values.forEach { runCatching { writeLine(it.output, wire("rooms", "rooms" to roomIds)) } }
    }

    private fun syncWith(peer: Peer) {
        rooms.values.filter { it.id in peer.rooms }.forEach { room ->
            runCatching {
                val ids = room.frames.keys.toList().takeLast(400)
                writeLine(peer.output, wire("have", "r" to room.id, "ids" to ids))
            }
        }
    }

    private fun post(room: Room, message: ChatMessage) {
        val id = randomHex(8)
        val frame = seal(room, message, id)
        addFrame(room, frame, persist = true)
        broadcast(room.id, frame, null)
    }

    private fun broadcast(roomId: String, frame: ChatFrame, except: Peer?) {
        peers.values.filter { it !== except && roomId in it.rooms }.forEach { sendFrame(it, roomId, frame) }
    }

    private fun sendFrame(peer: Peer, roomId: String, frame: ChatFrame) {
        runCatching {
            writeLine(peer.output, wire("m", "r" to roomId, "i" to frame.id, "ts" to frame.timestamp, "c" to frame.content))
        }
    }

    private fun seal(room: Room, message: ChatMessage, id: String): ChatFrame {
        val plain = gson.toJsonTree(message).asJsonObject.apply { remove("id") }.toString().toByteArray(StandardCharsets.UTF_8)
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(room.key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(room.id.toByteArray(StandardCharsets.UTF_8))
        val ciphertext = cipher.doFinal(plain)
        val encoded = Base64.getEncoder().withoutPadding().encodeToString(nonce + ciphertext)
        return ChatFrame(id, message.timestamp, encoded)
    }

    private fun open(room: Room, frame: ChatFrame): ChatMessage? = runCatching {
        require(frame.id.isNotBlank() && frame.id.length <= 64)
        val bytes = Base64.getDecoder().decode(frame.content)
        require(bytes.size >= 29)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(room.key, "AES"), GCMParameterSpec(128, bytes, 0, 12))
        cipher.updateAAD(room.id.toByteArray(StandardCharsets.UTF_8))
        val message = gson.fromJson(String(cipher.doFinal(bytes, 12, bytes.size - 12), StandardCharsets.UTF_8), ChatMessage::class.java)
        message.copy(id = frame.id)
    }.getOrNull()

    private fun addFrame(room: Room, frame: ChatFrame, persist: Boolean): ChatMessage? {
        if (frame.id.isBlank() || room.frames.containsKey(frame.id)) return null
        val message = open(room, frame) ?: return null
        room.frames[frame.id] = frame
        room.messages.add(message)
        room.messages.sortBy { it.timestamp }
        if (room.messages.size > MAX_HISTORY) {
            val old = room.messages.removeAt(0)
            room.frames.remove(old.id)
            rewriteHistory(room)
        } else if (persist) {
            room.historyFile.appendText(gson.toJson(frame) + "\n", StandardCharsets.UTF_8)
        }
        return message
    }

    private fun loadHistory(room: Room) {
        if (!room.historyFile.exists()) return
        room.historyFile.useLines(StandardCharsets.UTF_8) { lines ->
            lines.forEach { line -> runCatching { gson.fromJson(line, ChatFrame::class.java) }.getOrNull()?.let { addFrame(room, it, persist = false) } }
        }
        if (room.messages.size > MAX_HISTORY) rewriteHistory(room)
    }

    private fun rewriteHistory(room: Room) {
        room.historyFile.writeText(room.messages.joinToString(separator = "\n", postfix = if (room.messages.isEmpty()) "" else "\n") {
            gson.toJson(room.frames[it.id])
        }, StandardCharsets.UTF_8)
    }

    private fun serveGet(socket: Socket, output: OutputStream, token: String) {
        val file = files[token]
        if (file == null || !file.isFile) {
            writeLine(output, wire("file", "ok" to false))
            return
        }
        writeLine(output, wire("file", "ok" to true, "size" to file.length()))
        file.inputStream().use { it.copyTo(output) }
        output.flush()
    }

    private fun localAddressesJson(): JsonArray = JsonArray().also { array -> localAddresses().forEach(array::add) }

    private fun localAddresses(): List<String> = runCatching {
        java.util.Collections.list(NetworkInterface.getNetworkInterfaces()).flatMap { network ->
            if (!network.isUp || network.isLoopback) emptyList() else network.inetAddresses.toList()
                .filterIsInstance<Inet4Address>().filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                .map { "${it.hostAddress}:$TCP_PORT" }
        }.distinct()
    }.getOrDefault(emptyList())

    private fun copyExactly(input: InputStream, output: OutputStream, size: Long) {
        require(size >= 0) { "Invalid content length." }
        val buffer = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) error("File transfer ended before all bytes arrived.")
            output.write(buffer, 0, read)
            remaining -= read
        }
        output.flush()
    }

    private fun writeLine(output: OutputStream, value: JsonObject) {
        synchronized(output) {
            output.write(value.toString().toByteArray(StandardCharsets.UTF_8))
            output.write('\n'.code)
            output.flush()
        }
    }

    /** Deliberately reads one byte at a time, so a JSON reader never buffers raw file payload bytes. */
    private fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (bytes.size() <= MAX_LINE_BYTES) {
            val next = input.read()
            if (next < 0) return if (bytes.size() == 0) null else String(bytes.toByteArray(), StandardCharsets.UTF_8)
            if (next == '\n'.code) return String(bytes.toByteArray(), StandardCharsets.UTF_8).trimEnd('\r')
            bytes.write(next)
        }
        error("Chat protocol line exceeds 2 MiB.")
    }

    private fun wire(type: String, vararg fields: Pair<String, Any?>): JsonObject = JsonObject().apply {
        addProperty("t", type)
        fields.forEach { (key, value) ->
            when (value) {
                null -> Unit
                is String -> addProperty(key, value)
                is Boolean -> addProperty(key, value)
                is Number -> addProperty(key, value)
                is JsonArray -> add(key, value)
                is Collection<*> -> add(key, gson.toJsonTree(value).asJsonArray)
                else -> add(key, gson.toJsonTree(value))
            }
        }
    }

    private fun randomHex(bytes: Int): String = ByteArray(bytes).also(random::nextBytes)
        .joinToString("") { "%02x".format(it) }
}
