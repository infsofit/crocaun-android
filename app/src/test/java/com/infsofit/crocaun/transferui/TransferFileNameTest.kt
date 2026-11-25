package com.infsofit.crocaun.transferui

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferFileNameTest {
    @Test
    fun `preserves ordinary unicode filenames`() {
        assertEquals("Отчёт_été.txt", windowsCompatibleFileName("Отчёт_été.txt"))
    }

    @Test
    fun `replaces Windows-invalid characters and path separators`() {
        assertEquals("report__final_.txt", windowsCompatibleFileName("report:/final?.txt"))
    }

    @Test
    fun `prefixes reserved Windows device names`() {
        assertEquals("_CON.txt", windowsCompatibleFileName("CON.txt"))
        assertEquals("_COM3.log", windowsCompatibleFileName("COM3.log"))
    }

    @Test
    fun `falls back when the selected name is empty after sanitization`() {
        assertEquals("crocaun-file", windowsCompatibleFileName("..."))
    }
}
