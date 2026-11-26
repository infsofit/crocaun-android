package com.infsofit.crocaun.transferui

import android.os.SystemClock
import com.google.gson.JsonParser
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal data class LanPeer(
    val id: String,
    val name: String,
    val address: String,
    val port: Int,
    val role: String,
)

/** A cancellable, bounded UDP scan compatible with Windows Crocaun's CROCAU1 protocol. */
internal class LanDiscoveryOperation {
    private val cancelled = AtomicBoolean(false)

    @Volatile
    private var activeSocket: DatagramSocket? = null

    fun cancel() {
        cancelled.set(true)
        activeSocket?.close()
    }

    fun isCancelled(): Boolean = cancelled.get()

    fun scan(selfId: String, wantedRole: String, timeoutMs: Long = DEFAULT_SCAN_TIMEOUT_MS): List<LanPeer> {
        if (cancelled.get()) return emptyList()
        val targets = discoveryTargets()
        if (targets.isEmpty()) return emptyList()

        val socket = DatagramSocket(null as SocketAddress?)
        activeSocket = socket
        try {
            if (cancelled.get()) return emptyList()
            socket.reuseAddress = true
            socket.broadcast = true
            socket.bind(InetSocketAddress(0))
            val query = "$DISCOVERY_QUERY_PREFIX$selfId|$wantedRole".toByteArray(Charsets.UTF_8)
            val peers = linkedMapOf<String, LanPeer>()
            val perRoundWait = (timeoutMs.coerceIn(1_000L, MAX_SCAN_TIMEOUT_MS) / 2).coerceAtLeast(1L)

            repeat(2) {
                if (cancelled.get()) return peers.values.sortedWith(PEER_ORDER)
                targets.forEach { target ->
                    if (cancelled.get()) return@forEach
                    runCatching {
                        socket.send(DatagramPacket(query, query.size, target))
                    }
                }

                val deadline = SystemClock.elapsedRealtime() + perRoundWait
                while (!cancelled.get()) {
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) break
                    socket.soTimeout = remaining.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
                    val buffer = ByteArray(MAX_DATAGRAM_BYTES)
                    val response = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(response)
                    } catch (_: SocketTimeoutException) {
                        break
                    } catch (error: Exception) {
                        if (cancelled.get()) break
                        throw error
                    }
                    val sourceIp = response.address.hostAddress.orEmpty().substringBefore('%')
                    val peer = parseLanPeerReply(
                        response.data.copyOf(response.length),
                        sourceIp,
                        selfId,
                        wantedRole,
                    ) ?: continue
                    peers.putIfAbsent("${peer.address}|${peer.id}", peer)
                }
            }
            return peers.values.sortedWith(PEER_ORDER)
        } finally {
            activeSocket = null
            socket.close()
        }
    }

    private fun discoveryTargets(): List<InetSocketAddress> {
        val ownAddresses = linkedSetOf<String>()
        val hostTargets = linkedSetOf<String>()
        val broadcastTargets = linkedSetOf(DEFAULT_BROADCAST)
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }.getOrDefault(emptyList())

        interfaces.forEach { networkInterface ->
            if (cancelled.get()) return emptyList()
            val usable = runCatching { networkInterface.isUp && !networkInterface.isLoopback }.getOrDefault(false)
            if (!usable) return@forEach
            networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                val address = interfaceAddress.address as? Inet4Address ?: return@forEach
                if (address.isLoopbackAddress || address.isLinkLocalAddress) return@forEach
                val ip = address.hostAddress.orEmpty()
                ownAddresses += ip
                val prefix = interfaceAddress.networkPrefixLength.toInt()
                if (prefix !in 1..30) return@forEach
                val parts = ipv4Octets(ip) ?: return@forEach
                val host = octetsToLong(parts)
                val effectivePrefix = if (prefix < BROAD_SCAN_CUTOFF_PREFIX) 24 else prefix
                val mask = ipv4Mask(effectivePrefix)
                val network = host and mask
                val narrowedBroadcast = network or (IPV4_MASK xor mask)
                broadcastTargets += longToIpv4(narrowedBroadcast)
                ipv4HostsForScan(ip, prefix).forEach(hostTargets::add)
            }
        }

        val targets = (broadcastTargets + hostTargets.filterNot(ownAddresses::contains))
            .distinct()
            .sorted()
            .take(MAX_SCAN_TARGETS)
        return targets.mapNotNull { host ->
            runCatching { InetSocketAddress(InetAddress.getByName(host), DISCOVERY_PORT) }.getOrNull()
        }
    }

    private companion object {
        val PEER_ORDER = compareBy<LanPeer>({ it.name.lowercase() }, { it.address }, { it.id })
        const val DISCOVERY_PORT = 29008
        const val DISCOVERY_QUERY_PREFIX = "CROCAU1?"
        const val DEFAULT_BROADCAST = "255.255.255.255"
        const val MAX_DATAGRAM_BYTES = 1024
        const val MAX_SCAN_TARGETS = 2048
        const val BROAD_SCAN_CUTOFF_PREFIX = 22
        const val DEFAULT_SCAN_TIMEOUT_MS = 3_500L
        const val MAX_SCAN_TIMEOUT_MS = 8_000L
    }
}

internal fun parseLanPeerReply(
    payload: ByteArray,
    sourceIp: String,
    selfId: String,
    wantedRole: String,
): LanPeer? {
    val raw = payload.toString(Charsets.UTF_8)
    if (!raw.startsWith("CROCAU1!")) return null
    if (ipv4Octets(sourceIp) == null) return null
    val peerJson = runCatching { JsonParser.parseString(raw.substring("CROCAU1!".length)).asJsonObject }.getOrNull() ?: return null
    val id = runCatching { peerJson.get("id")?.asString }.getOrNull().orEmpty().trim()
    val name = runCatching { peerJson.get("name")?.asString }.getOrNull().orEmpty()
        .filterNot { it.isISOControl() }.trim().take(MAX_PEER_NAME_LENGTH)
    val port = runCatching { peerJson.get("port")?.asInt ?: -1 }.getOrDefault(-1)
    val role = runCatching { peerJson.get("role")?.asString }.getOrNull().orEmpty().trim()
    if (id.isEmpty() || id == selfId || port !in 1..65_535) return null
    if (role != ROLE_SEND && role != ROLE_RECEIVE) return null
    if (wantedRole.isNotEmpty() && role != wantedRole) return null
    return LanPeer(
        id = id,
        name = name.ifEmpty { "Crocaun device" },
        address = sourceIp,
        port = port,
        role = role,
    )
}

/** Returns usable IPv4 hosts; networks wider than /22 are narrowed to the local /24. */
internal fun ipv4HostsForScan(ipv4: String, prefixLength: Int): List<String> {
    val octets = ipv4Octets(ipv4) ?: return emptyList()
    if (prefixLength !in 1..30) return emptyList()
    val prefix = if (prefixLength < 22) 24 else prefixLength
    val address = octetsToLong(octets)
    val mask = ipv4Mask(prefix)
    val network = address and mask
    val hostCount = 1L shl (32 - prefix)
    if (hostCount <= 2) return emptyList()
    return (1 until hostCount - 1).map { offset -> longToIpv4(network + offset) }
}

internal fun parseManualPathInput(input: String): List<String> {
    val paths = input.lineSequence().mapNotNull { rawLine ->
        var path = rawLine.trim()
        if (path.isEmpty()) return@mapNotNull null
        val startsQuoted = path.startsWith('"') || path.startsWith('\'')
        val endsQuoted = path.endsWith('"') || path.endsWith('\'')
        if (startsQuoted || endsQuoted) {
            require(startsQuoted && endsQuoted && path.length >= 2 && path.first() == path.last()) {
                "Put each quoted file path on its own line and close the quote."
            }
            path = path.substring(1, path.length - 1).trim()
        }
        require(path.isNotEmpty()) { "A file path is empty." }
        require('\u0000' !in path) { "A file path contains an invalid character." }
        require(File(path).isAbsolute) { "Enter an absolute Android file path, one path per line." }
        path
    }.toList()
    require(paths.isNotEmpty()) { "Enter at least one absolute file path." }
    return paths
}

internal fun validateManualPathFiles(paths: List<String>): List<File> {
    require(paths.isNotEmpty()) { "Enter at least one absolute file path." }
    return paths.map { path ->
        val file = File(path)
        require(file.isAbsolute) { "Enter an absolute Android file path, one path per line." }
        require(file.exists()) { "File does not exist: $path" }
        require(file.isFile) { "Path is not a regular file: $path" }
        require(file.canRead()) { "File is not readable by Crocaun: $path" }
        file
    }
}

private fun ipv4Octets(ipv4: String): IntArray? {
    val parts = ipv4.split('.')
    if (parts.size != 4) return null
    val octets = parts.map { it.toIntOrNull() ?: return null }
    if (octets.any { it !in 0..255 }) return null
    return octets.toIntArray()
}

private fun octetsToLong(octets: IntArray): Long =
    (octets[0].toLong() shl 24) or (octets[1].toLong() shl 16) or (octets[2].toLong() shl 8) or octets[3].toLong()

private fun ipv4Mask(prefix: Int): Long =
    (IPV4_MASK shl (32 - prefix)) and IPV4_MASK

private fun longToIpv4(value: Long): String = listOf(24, 16, 8, 0)
    .joinToString(".") { shift -> ((value ushr shift) and 0xff).toString() }

private const val IPV4_MASK = 0xffff_ffffL
private const val MAX_PEER_NAME_LENGTH = 80
private const val ROLE_SEND = "send"
private const val ROLE_RECEIVE = "recv"
