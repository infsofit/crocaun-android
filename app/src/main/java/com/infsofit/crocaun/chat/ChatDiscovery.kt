package com.infsofit.crocaun.chat

import com.google.gson.JsonParser
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

private const val REPLY_PREFIX = "CROCAUCHAT1!"

/** A peer found through the Crocaun Windows-compatible chat discovery protocol. */
data class LanChatPeer(
    val id: String,
    val address: String,
    val port: Int,
)

/** Parse a Windows chat-discovery reply, returning null for malformed or ineligible peers. */
fun parseChatDiscoveryReply(payload: ByteArray, sourceIp: String, selfId: String): LanChatPeer? {
    val raw = payload.toString(Charsets.UTF_8)
    if (!raw.startsWith(REPLY_PREFIX)) return null
    if (!isIpv4Address(sourceIp)) return null

    val json = runCatching {
        JsonParser.parseString(raw.substring(REPLY_PREFIX.length)).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull() ?: return null

    val idElement = json.get("id") ?: return null
    if (!idElement.isJsonPrimitive || !idElement.asJsonPrimitive.isString) return null
    val id = idElement.asString.trim()
    if (id.isBlank() || id == selfId) return null

    val portElement = json.get("port") ?: return null
    if (!portElement.isJsonPrimitive || !portElement.asJsonPrimitive.isNumber) return null
    val port = portElement.asString.toIntOrNull() ?: return null
    if (port !in 1..65_535) return null

    return LanChatPeer(id = id, address = sourceIp, port = port)
}

/** A cancellable, bounded IPv4 UDP search for Crocaun chat peers. */
class ChatDiscoveryOperation {
    private val cancelled = AtomicBoolean(false)
    private val started = AtomicBoolean(false)

    @Volatile
    private var activeSocket: DatagramSocket? = null

    fun cancel() {
        cancelled.set(true)
        activeSocket?.close()
    }

    fun isCancelled(): Boolean = cancelled.get()

    /**
     * Searches broadcast and local unicast targets in two rounds. The requested timeout is
     * clamped to 1..8 seconds and is split between the rounds; the default is 3.5 seconds.
     */
    fun scan(selfId: String, timeoutMs: Long = DEFAULT_SCAN_TIMEOUT_MS): List<LanChatPeer> {
        if (cancelled.get() || !started.compareAndSet(false, true)) return emptyList()
        val targets = discoveryTargets()
        if (cancelled.get() || targets.isEmpty()) return emptyList()

        val socket = DatagramSocket(null as SocketAddress?)
        activeSocket = socket
        try {
            if (cancelled.get()) return emptyList()
            socket.broadcast = true
            socket.bind(InetSocketAddress(0))
            val query = (QUERY_PREFIX + selfId).toByteArray(Charsets.UTF_8)
            val foundPeers = mutableListOf<LanChatPeer>()
            val boundedTimeoutMs = timeoutMs.coerceIn(1L, MAX_SCAN_TIMEOUT_MS)
            val startedAt = System.nanoTime()
            val totalNanos = boundedTimeoutMs * NANOS_PER_MILLI

            for (round in 0 until ROUNDS) {
                if (cancelled.get()) break
                val overallDeadline = startedAt + totalNanos
                if (System.nanoTime() >= overallDeadline) break
                for (target in targets) {
                    if (cancelled.get() || System.nanoTime() >= overallDeadline) break
                    try {
                        socket.send(DatagramPacket(query, query.size, target))
                    } catch (_: SocketException) {
                        if (cancelled.get()) break
                    } catch (_: Exception) {
                        // A failed target should not prevent discovery on the remaining interfaces.
                    }
                }

                val roundDeadline = startedAt + totalNanos * (round + 1L) / ROUNDS
                while (!cancelled.get()) {
                    val remainingNanos = roundDeadline - System.nanoTime()
                    if (remainingNanos <= 0L) break
                    socket.soTimeout = (remainingNanos / NANOS_PER_MILLI)
                        .coerceAtLeast(1L)
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt()
                    val buffer = ByteArray(MAX_DATAGRAM_BYTES)
                    val response = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(response)
                    } catch (_: SocketTimeoutException) {
                        break
                    } catch (error: SocketException) {
                        if (cancelled.get()) break
                        throw error
                    }
                    val peer = parseChatDiscoveryReply(
                        response.data.copyOf(response.length),
                        response.address.hostAddress.orEmpty().substringBefore('%'),
                        selfId,
                    ) ?: continue
                    foundPeers += peer
                }
            }
            return deduplicateChatPeers(foundPeers)
        } finally {
            activeSocket = null
            socket.close()
        }
    }

    private fun discoveryTargets(): List<InetSocketAddress> {
        val ownAddresses = linkedSetOf<String>()
        val broadcastTargets = linkedSetOf(DEFAULT_BROADCAST)
        val hostTargets = linkedSetOf<String>()
        val interfaces = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        }.getOrDefault(emptyList())

        for (networkInterface in interfaces) {
            if (cancelled.get()) return emptyList()
            val usable = runCatching { networkInterface.isUp && !networkInterface.isLoopback }
                .getOrDefault(false)
            if (!usable) continue
            for (interfaceAddress in networkInterface.interfaceAddresses) {
                val address = interfaceAddress.address as? Inet4Address ?: continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isAnyLocalAddress || address.isMulticastAddress) continue
                val ip = address.hostAddress.orEmpty().substringBefore('%')
                val parts = ipv4Octets(ip) ?: continue
                ownAddresses += ip
                val originalPrefix = interfaceAddress.networkPrefixLength.toInt()
                if (originalPrefix !in 1..30) continue

                // Avoid accidentally scanning huge networks: use the local /24 for prefixes below /22.
                val prefix = if (originalPrefix < WIDE_NETWORK_CUTOFF_PREFIX) 24 else originalPrefix
                val addressValue = octetsToLong(parts)
                val mask = ipv4Mask(prefix)
                val network = addressValue and mask
                val broadcast = network or (IPV4_MASK xor mask)
                broadcastTargets += longToIpv4(broadcast)

                val hostCount = 1L shl (32 - prefix)
                for (offset in 1 until hostCount - 1) {
                    hostTargets += longToIpv4(network + offset)
                }
            }
        }

        // Reserve target slots for broadcast addresses before adding potentially numerous unicast hosts.
        val boundedAddresses = (broadcastTargets.toList() + hostTargets.filterNot(ownAddresses::contains))
            .distinct()
            .take(MAX_SCAN_TARGETS)
        return boundedAddresses.mapNotNull { ip ->
            runCatching {
                InetSocketAddress(InetAddress.getByName(ip), DISCOVERY_PORT)
            }.getOrNull()
        }
    }

    private companion object {
        const val QUERY_PREFIX = "CROCAUCHAT1?"
        const val REPLY_PREFIX = "CROCAUCHAT1!"
        const val DISCOVERY_PORT = 29_015
        const val DEFAULT_BROADCAST = "255.255.255.255"
        const val MAX_DATAGRAM_BYTES = 1_024
        const val MAX_SCAN_TARGETS = 2_048
        const val WIDE_NETWORK_CUTOFF_PREFIX = 22
        const val DEFAULT_SCAN_TIMEOUT_MS = 3_500L
        const val MAX_SCAN_TIMEOUT_MS = 8_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val ROUNDS = 2
    }
}

internal fun deduplicateChatPeers(peers: Iterable<LanChatPeer>): List<LanChatPeer> {
    val unique = linkedMapOf<String, LanChatPeer>()
    peers.forEach { peer -> unique.putIfAbsent("${peer.address}|${peer.id}", peer) }
    return unique.values.sortedWith(compareBy<LanChatPeer>({ it.address }, { it.id }, { it.port }))
}

private fun isIpv4Address(value: String): Boolean = ipv4Octets(value) != null

private fun ipv4Octets(ipv4: String): IntArray? {
    val parts = ipv4.split('.')
    if (parts.size != 4) return null
    val octets = parts.map { part ->
        if (part.isEmpty() || part.any { it !in '0'..'9' }) return null
        part.toIntOrNull()?.takeIf { it in 0..255 } ?: return null
    }
    return octets.toIntArray()
}

private fun octetsToLong(octets: IntArray): Long =
    (octets[0].toLong() shl 24) or (octets[1].toLong() shl 16) or
        (octets[2].toLong() shl 8) or octets[3].toLong()

private fun ipv4Mask(prefix: Int): Long = (IPV4_MASK shl (32 - prefix)) and IPV4_MASK

private fun longToIpv4(value: Long): String = listOf(24, 16, 8, 0)
    .joinToString(".") { shift -> ((value ushr shift) and 0xff).toString() }

private const val IPV4_MASK = 0xffff_ffffL
