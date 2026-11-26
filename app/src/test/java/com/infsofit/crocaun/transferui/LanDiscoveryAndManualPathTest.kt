package com.infsofit.crocaun.transferui

import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanDiscoveryAndManualPathTest {
    @Test
    fun `parses Windows Crocaun CROCAU1 peer response`() {
        val payload = "CROCAU1!{\"id\":\"win-123\",\"name\":\"Office PC\",\"port\":29009,\"role\":\"send\"}"
            .toByteArray(Charsets.UTF_8)

        val peer = parseLanPeerReply(payload, "192.168.1.20", "android-1", "send")

        assertEquals(LanPeer("win-123", "Office PC", "192.168.1.20", 29009, "send"), peer)
    }

    @Test
    fun `rejects invalid prefix roles self peers and ports`() {
        fun parse(json: String, selfId: String = "android-1", wantedRole: String = "recv") =
            parseLanPeerReply("CROCAU1!$json".toByteArray(), "192.168.1.20", selfId, wantedRole)

        assertNull(parse("{\"id\":\"peer\",\"name\":\"PC\",\"port\":29009,\"role\":\"send\"}"))
        assertNull(parse("{\"id\":\"android-1\",\"name\":\"Self\",\"port\":29009,\"role\":\"recv\"}", wantedRole = ""))
        assertNull(parse("{\"id\":\"peer\",\"name\":\"PC\",\"port\":70000,\"role\":\"recv\"}"))
        assertNull(parse("{\"id\":\"peer\",\"name\":\"PC\",\"port\":29009,\"role\":\"unknown\"}", wantedRole = ""))
        assertNull(parse("not json", wantedRole = ""))
    }

    @Test
    fun `deduplicates only through scanner identity key fields`() {
        val peerA = parseLanPeerReply(
            "CROCAU1!{\"id\":\"same\",\"name\":\"PC\",\"port\":29009,\"role\":\"recv\"}".toByteArray(),
            "10.0.0.5",
            "android",
            "recv",
        )
        val peerB = parseLanPeerReply(
            "CROCAU1!{\"id\":\"same\",\"name\":\"PC\",\"port\":29010,\"role\":\"recv\"}".toByteArray(),
            "10.0.0.5",
            "android",
            "recv",
        )
        assertEquals(peerA?.id, peerB?.id)
        assertEquals(peerA?.address, peerB?.address)
    }

    @Test
    fun `narrows broad IPv4 networks to local slash 24`() {
        val hosts = ipv4HostsForScan("10.8.7.23", 16)
        assertEquals(254, hosts.size)
        assertEquals("10.8.7.1", hosts.first())
        assertEquals("10.8.7.254", hosts.last())
    }

    @Test
    fun `keeps normal subnet bounds and rejects unusable prefixes`() {
        val hosts = ipv4HostsForScan("192.168.5.42", 24)
        assertEquals(254, hosts.size)
        assertEquals("192.168.5.1", hosts.first())
        assertEquals("192.168.5.254", hosts.last())
        assertTrue(ipv4HostsForScan("192.168.5.1", 31).isEmpty())
        assertTrue(ipv4HostsForScan("not-an-ip", 24).isEmpty())
    }

    @Test
    fun `parses one absolute path per line and removes matching quotes`() {
        val first = File("/storage/emulated/0/Download/hello world.txt").path
        val second = File("/storage/emulated/0/DCIM/photo.jpg").path
        assertEquals(
            listOf(first, second),
            parseManualPathInput("  \"$first\"  \n\n'$second'\n"),
        )
    }

    @Test
    fun `rejects empty relative and mismatched quoted path entries`() {
        assertFailsWithMessage("Enter at least one absolute file path.") { parseManualPathInput("  \n\t") }
        assertFailsWithMessage("Enter an absolute Android file path, one path per line.") { parseManualPathInput("relative/file.txt") }
        assertFailsWithMessage("Put each quoted file path on its own line and close the quote.") {
            parseManualPathInput("\"/tmp/file.txt'")
        }
    }

    @Test
    fun `validates manually entered paths are existing regular readable files`() {
        val root = createTempDirectory(prefix = "crocaun-paths-").toFile()
        try {
            val file = File(root, "document.txt").apply { writeText("hello") }
            val directory = File(root, "folder").apply { mkdir() }
            assertEquals(listOf(file), validateManualPathFiles(listOf(file.absolutePath)))
            assertFailsWithMessage("File does not exist:") {
                validateManualPathFiles(listOf(File(root, "missing.txt").absolutePath))
            }
            assertFailsWithMessage("Path is not a regular file:") {
                validateManualPathFiles(listOf(directory.absolutePath))
            }
            assertFalse(directory.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun assertFailsWithMessage(expectedPrefix: String, block: () -> Unit) {
        val error = try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            expected
        }
        assertTrue("${error.message} should start with $expectedPrefix", error.message.orEmpty().startsWith(expectedPrefix))
    }
}
