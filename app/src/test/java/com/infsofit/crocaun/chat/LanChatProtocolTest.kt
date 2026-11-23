package com.infsofit.crocaun.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class LanChatProtocolTest {
    @Test
    fun `common room key matches Windows PBKDF2 parameters`() {
        val key = LanChatClient.deriveRoomKey("crocau-common")
        assertEquals("f1bad27cda1208ce1488d7a25c47f2706b430ad6ae2e0e1c240d9cbaa79f78f7", key.toHex())
    }

    @Test
    fun `room id matches Windows sha256 derivation`() {
        val key = LanChatClient.deriveRoomKey("crocau-common")
        assertEquals("f295d3c33f6de5e0", LanChatClient.roomIdForKey(key))
    }

    @Test
    fun `room password uses utf8 bytes`() {
        val key = LanChatClient.deriveRoomKey("пароль")
        assertEquals("5cc183965848a9632a76514727418d19eb63a77745ea7e565e379c90268cb97b", key.toHex())
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
