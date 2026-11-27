package com.infsofit.crocaun.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatDiscoveryTest {
    @Test
    fun `parses valid Windows chat discovery reply`() {
        val peer = parseReply("CROCAUCHAT1!{\"id\":\"windows-node-7\",\"port\":29014}")

        assertEquals(LanChatPeer("windows-node-7", "192.168.1.42", 29014), peer)
    }

    @Test
    fun `rejects invalid prefix and malformed or non-object JSON`() {
        assertNull(parseReply("CROCAU1!{\"id\":\"peer\",\"port\":29014}"))
        assertNull(parseReply("CROCAUCHAT1!not-json"))
        assertNull(parseReply("CROCAUCHAT1![1,2,3]"))
        assertNull(parseReply("CROCAUCHAT1!{\"id\":\"peer\",\"port\":29014"))
    }

    @Test
    fun `rejects empty and self ids`() {
        assertNull(parseReply("CROCAUCHAT1!{\"id\":\"\",\"port\":29014}"))
        assertNull(parseReply("CROCAUCHAT1!{\"id\":7,\"port\":29014}"))
        assertNull(parseReply("CROCAUCHAT1!{\"id\":\"my-node\",\"port\":29014}", selfId = "my-node"))
    }

    @Test
    fun `rejects missing malformed string and out of range ports`() {
        val invalidPorts = listOf(
            "{\"id\":\"peer\"}",
            "{\"id\":\"peer\",\"port\":0}",
            "{\"id\":\"peer\",\"port\":65536}",
            "{\"id\":\"peer\",\"port\":-2}",
            "{\"id\":\"peer\",\"port\":29014.5}",
            "{\"id\":\"peer\",\"port\":\"29014\"}",
        )

        invalidPorts.forEach { json -> assertNull(parseReply("CROCAUCHAT1!$json")) }
    }

    @Test
    fun `rejects non IPv4 source addresses`() {
        val payload = reply("peer", 29014)
        listOf("localhost", "192.168.1", "256.1.1.1", "192.168.1.1:7", "2001:db8::1").forEach { source ->
            assertNull(parseChatDiscoveryReply(payload, source, "my-node"))
        }
    }

    @Test
    fun `peer data class equality covers id address and port`() {
        val peer = LanChatPeer("peer", "192.168.1.42", 29014)
        assertEquals(peer, LanChatPeer("peer", "192.168.1.42", 29014))
        assertNotEquals(peer, LanChatPeer("peer-2", "192.168.1.42", 29014))
        assertNotEquals(peer, LanChatPeer("peer", "192.168.1.43", 29014))
        assertNotEquals(peer, LanChatPeer("peer", "192.168.1.42", 29015))
    }

    @Test
    fun `deduplicates by address and id then sorts peers`() {
        val firstPeer = LanChatPeer("z-peer", "192.168.1.8", 29014)
        val samePeerIdentity = LanChatPeer("z-peer", "192.168.1.8", 29015)
        val peers = deduplicateChatPeers(
            listOf(
                firstPeer,
                LanChatPeer("b-peer", "192.168.1.3", 29014),
                samePeerIdentity,
                LanChatPeer("a-peer", "192.168.1.8", 29014),
                LanChatPeer("z-peer", "192.168.1.9", 29014),
            ),
        )

        assertEquals(
            listOf(
                LanChatPeer("b-peer", "192.168.1.3", 29014),
                LanChatPeer("a-peer", "192.168.1.8", 29014),
                firstPeer,
                LanChatPeer("z-peer", "192.168.1.9", 29014),
            ),
            peers,
        )
    }

    private fun parseReply(payload: String, selfId: String = "my-node"): LanChatPeer? =
        parseChatDiscoveryReply(payload.toByteArray(Charsets.UTF_8), "192.168.1.42", selfId)

    private fun reply(id: String, port: Int): ByteArray =
        "CROCAUCHAT1!{\"id\":\"$id\",\"port\":$port}".toByteArray(Charsets.UTF_8)
}
