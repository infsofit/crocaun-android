package com.infsofit.crocaun.transferui

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ArchiveExtractorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `extracts files in safe nested paths`() {
        val staging = temporaryFolder.newFolder("staging")
        val archive = zipFile(
            "nested.zip",
            "docs/notes.txt" to "hello",
            "media/photo.bin" to "pixels",
        )

        val extracted = extractZipArchive(archive, staging)

        assertEquals(2, extracted.size)
        assertEquals("hello", File(staging, "nested-unpacked/docs/notes.txt").readText())
        assertEquals("pixels", File(staging, "nested-unpacked/media/photo.bin").readText())
        assertTrue(extracted.all { it.isFile })
    }

    @Test
    fun `rejects traversal and absolute archive paths and removes partial output`() {
        val staging = temporaryFolder.newFolder("staging")
        val badEntries = listOf("../outside.txt", "/absolute.txt", "C:/drive.txt", "..\\escape.txt")

        badEntries.forEachIndexed { index, name ->
            val archive = zipFile("bad$index.zip", "safe/created.txt" to "partial", name to "escape")
            org.junit.Assert.assertThrows(IOException::class.java) {
                extractZipArchive(archive, staging)
            }
            assertFalse(File(staging, "bad$index-unpacked").exists())
        }
        assertFalse(File(temporaryFolder.root, "outside.txt").exists())
        assertFalse(File(temporaryFolder.root, "absolute.txt").exists())
    }

    @Test
    fun `assigns unique output names to duplicate ZIP entries`() {
        val staging = temporaryFolder.newFolder("staging")
        val archive = temporaryFolder.newFile("duplicates.zip")
        writeZipWithDuplicateStoredEntries(archive, "repeat.txt" to "first", "repeat.txt" to "second")

        val extracted = extractZipArchive(archive, staging)

        assertEquals(listOf("repeat.txt", "repeat (2).txt"), extracted.map { it.name })
        assertEquals("first", extracted[0].readText())
        assertEquals("second", extracted[1].readText())
    }

    @Test
    fun `ignores files without ZIP extension`() {
        val staging = temporaryFolder.newFolder("staging")
        val notZip = temporaryFolder.newFile("notes.txt")
        notZip.writeText("not a zip")

        assertTrue(extractZipArchive(notZip, staging).isEmpty())
        assertTrue(staging.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `removes partial extraction directory after malformed ZIP`() {
        val staging = temporaryFolder.newFolder("staging")
        val archive = temporaryFolder.newFile("malformed.zip")
        // A local-file signature with a truncated header causes ZipInputStream to fail on read.
        archive.writeBytes(byteArrayOf(0x50, 0x4b, 0x03, 0x04))

        org.junit.Assert.assertThrows(IOException::class.java) {
            extractZipArchive(archive, staging)
        }
        assertFalse(File(staging, "malformed-unpacked").exists())
    }

    @Test
    fun `accepts a valid empty ZIP archive`() {
        val staging = temporaryFolder.newFolder("staging")
        val archive = zipFile("empty.zip")

        assertTrue(extractZipArchive(archive, staging).isEmpty())
    }

    @Test
    fun `rejects an empty ZIP signature without a complete EOCD record`() {
        val staging = temporaryFolder.newFolder("staging")
        val archive = temporaryFolder.newFile("truncated-empty.zip")
        archive.writeBytes(byteArrayOf(0x50, 0x4b, 0x05, 0x06))

        org.junit.Assert.assertThrows(IOException::class.java) {
            extractZipArchive(archive, staging)
        }
        assertFalse(File(staging, "truncated-empty-unpacked").exists())
    }

    private fun zipFile(name: String, vararg entries: Pair<String, String>): File {
        val archive = temporaryFolder.newFile(name)
        ZipOutputStream(archive.outputStream()).use { zip ->
            entries.forEach { (path, contents) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(contents.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return archive
    }

    /** Writes a valid stored ZIP, including duplicate names (which ZipOutputStream disallows). */
    private fun writeZipWithDuplicateStoredEntries(archive: File, vararg entries: Pair<String, String>) {
        val output = ByteArrayOutputStream()
        val centralRecords = mutableListOf<ByteArray>()
        entries.forEach { (entryName, text) ->
            val name = entryName.toByteArray(Charsets.UTF_8)
            val contents = text.toByteArray(Charsets.UTF_8)
            val crc = CRC32().apply { update(contents) }.value.toInt()
            val localOffset = output.size()
            output.writeLeInt(0x04034b50)
            output.writeLeShort(20)
            output.writeLeShort(0)
            output.writeLeShort(0) // stored
            output.writeLeShort(0)
            output.writeLeShort(0)
            output.writeLeInt(crc)
            output.writeLeInt(contents.size)
            output.writeLeInt(contents.size)
            output.writeLeShort(name.size)
            output.writeLeShort(0)
            output.write(name)
            output.write(contents)

            val central = ByteArrayOutputStream()
            central.writeLeInt(0x02014b50)
            central.writeLeShort(20)
            central.writeLeShort(20)
            central.writeLeShort(0)
            central.writeLeShort(0)
            central.writeLeShort(0)
            central.writeLeShort(0)
            central.writeLeInt(crc)
            central.writeLeInt(contents.size)
            central.writeLeInt(contents.size)
            central.writeLeShort(name.size)
            central.writeLeShort(0)
            central.writeLeShort(0)
            central.writeLeShort(0)
            central.writeLeShort(0)
            central.writeLeInt(0)
            central.writeLeInt(localOffset)
            central.write(name)
            centralRecords += central.toByteArray()
        }

        val centralOffset = output.size()
        centralRecords.forEach(output::write)
        val centralSize = output.size() - centralOffset
        output.writeLeInt(0x06054b50)
        output.writeLeShort(0)
        output.writeLeShort(0)
        output.writeLeShort(entries.size)
        output.writeLeShort(entries.size)
        output.writeLeInt(centralSize)
        output.writeLeInt(centralOffset)
        output.writeLeShort(0)
        archive.writeBytes(output.toByteArray())
    }

    private fun ByteArrayOutputStream.writeLeShort(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeLeInt(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}
