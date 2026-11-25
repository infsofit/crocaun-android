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

    @Test
    fun `disambiguates duplicate names for a multi-file batch`() {
        assertEquals(
            listOf("report.txt", "report (2).txt", "REPORT (3).TXT", "notes"),
            uniqueWindowsCompatibleFileNames(listOf("report.txt", "report.txt", "REPORT.TXT", "notes")),
        )
    }

    @Test
    fun `disambiguates names after Windows sanitization`() {
        assertEquals(
            listOf("bad_name.txt", "bad_name (2).txt"),
            uniqueWindowsCompatibleFileNames(listOf("bad:name.txt", "bad?name.txt")),
        )
    }
}
