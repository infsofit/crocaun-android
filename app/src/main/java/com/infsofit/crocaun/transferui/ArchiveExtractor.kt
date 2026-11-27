package com.infsofit.crocaun.transferui

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipInputStream

/** Extracts a ZIP archive into a new sibling directory under [stagingRoot]. */
internal fun extractZipArchive(archive: File, stagingRoot: File): List<File> {
    if (!archive.name.lowercase(Locale.ROOT).endsWith(".zip")) return emptyList()
    if (!archive.isFile) throw IOException("ZIP archive is not a regular file: $archive")
    val emptyArchiveSignature = verifyZipSignature(archive)

    if (!stagingRoot.exists() && !stagingRoot.mkdirs()) {
        throw IOException("Could not create staging directory: $stagingRoot")
    }
    if (!stagingRoot.isDirectory) throw IOException("Staging path is not a directory: $stagingRoot")

    val archiveBaseName = archive.name.dropLast(4).ifBlank { "archive" }
    val destination = File(stagingRoot, "${windowsCompatibleFileName(archiveBaseName)}-unpacked")
    // Do not reuse or delete a pre-existing directory. This also makes cleanup ownership clear.
    if (!destination.mkdir()) throw IOException("Extraction directory already exists or could not be created: $destination")

    try {
        val rootCanonical = destination.canonicalFile
        val stagingCanonical = stagingRoot.canonicalFile
        if (!isStrictChild(stagingCanonical.path, rootCanonical.path)) {
            throw IOException("Extraction directory is outside the staging directory")
        }

        val extracted = mutableListOf<File>()
        var entryCount = 0
        var expandedBytes = 0L
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > MAX_ENTRIES) throw IOException("ZIP contains more than $MAX_ENTRIES entries")

                val components = validatedComponents(entry.name)
                if (components.isEmpty()) {
                    zip.closeEntry()
                    continue
                }

                val parent = makeParentDirectories(rootCanonical, components.dropLast(1))
                val safeName = windowsCompatibleFileName(components.last())
                val wanted = File(parent, safeName)
                val output = if (entry.isDirectory) {
                    if (wanted.exists() && wanted.isDirectory) wanted else uniqueSibling(parent, safeName).also {
                        if (!it.mkdir()) throw IOException("Could not create directory: $it")
                    }
                } else {
                    uniqueSibling(parent, safeName)
                }
                assertInside(rootCanonical, output)

                if (!entry.isDirectory) {
                    if (!output.createNewFile()) throw IOException("Could not create output file without overwriting: $output")
                    FileOutputStream(output, false).use { fileOut ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            if (expandedBytes > MAX_EXPANDED_BYTES - count) {
                                throw IOException("ZIP expands beyond the $MAX_EXPANDED_BYTES-byte limit")
                            }
                            expandedBytes += count
                            fileOut.write(buffer, 0, count)
                        }
                    }
                    extracted += output
                }
                zip.closeEntry()
            }
        }
        if (entryCount == 0 && !emptyArchiveSignature) {
            throw IOException("ZIP archive contains no readable entries: $archive")
        }
        return extracted
    } catch (failure: Throwable) {
        try {
            deleteTree(destination)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
}

private fun validatedComponents(entryName: String): List<String> {
    // ZIP paths conventionally use '/', but treat backslashes as separators too so Windows paths
    // cannot become ordinary-looking filenames when extracted on a Unix host.
    val normalized = entryName.replace('\\', '/')
    if (normalized.startsWith('/') || DRIVE_PREFIX.containsMatchIn(normalized)) {
        throw IOException("ZIP entry has an absolute path: $entryName")
    }
    val components = normalized.split('/')
        .filter { it.isNotEmpty() && it != "." }
    if (components.any { it == ".." }) throw IOException("ZIP entry traverses its extraction directory: $entryName")
    return components
}

private fun makeParentDirectories(root: File, components: List<String>): File {
    var parent = root
    for (component in components) {
        val safeComponent = windowsCompatibleFileName(component)
        val requested = File(parent, safeComponent)
        val directory = if (requested.exists() && requested.isDirectory) {
            requested
        } else {
            uniqueSibling(parent, safeComponent).also {
                if (!it.mkdir()) throw IOException("Could not create directory: $it")
            }
        }
        assertInside(root, directory)
        parent = directory
    }
    return parent
}

/** Choose a free sibling name, including when an earlier ZIP entry used that output name. */
private fun uniqueSibling(parent: File, safeName: String): File {
    val first = File(parent, safeName)
    if (!first.exists()) return first

    val extensionStart = safeName.lastIndexOf('.').takeIf { it > 0 } ?: safeName.length
    val base = safeName.substring(0, extensionStart)
    val extension = safeName.substring(extensionStart)
    var serial = 2
    while (true) {
        val suffix = " ($serial)"
        val maxBaseLength = (MAX_COMPONENT_LENGTH - suffix.length - extension.length).coerceAtLeast(1)
        val candidate = File(parent, base.take(maxBaseLength) + suffix + extension)
        if (!candidate.exists()) return candidate
        serial++
    }
}

private fun assertInside(root: File, target: File) {
    val rootPath = root.canonicalPath
    val targetPath = target.canonicalPath
    if (!isStrictChild(rootPath, targetPath)) {
        throw IOException("ZIP entry resolves outside its extraction directory")
    }
}

private fun isStrictChild(parentPath: String, childPath: String): Boolean {
    val prefix = if (parentPath.endsWith(File.separator)) parentPath else parentPath + File.separator
    return childPath.startsWith(prefix)
}

private fun deleteTree(directory: File) {
    val children = directory.listFiles()
    if (children != null) {
        for (child in children) {
            if (child.isDirectory && !java.nio.file.Files.isSymbolicLink(child.toPath())) deleteTree(child)
            else if (child.exists() && !child.delete()) throw IOException("Could not remove partial extraction file: $child")
        }
    }
    if (directory.exists() && !directory.delete()) throw IOException("Could not remove partial extraction directory: $directory")
}

private fun verifyZipSignature(archive: File): Boolean {
    archive.inputStream().buffered().use { input ->
        val signature = ByteArray(4)
        var signatureBytesRead = 0
        while (signatureBytesRead < signature.size) {
            val read = input.read(signature, signatureBytesRead, signature.size - signatureBytesRead)
            if (read < 0) break
            signatureBytesRead += read
        }
        val localHeader = signatureBytesRead == 4 && signature.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))
        if (localHeader) return false

        val emptyArchive = signatureBytesRead == 4 && signature.contentEquals(byteArrayOf(0x50, 0x4b, 0x05, 0x06))
        if (!emptyArchive) throw IOException("File does not have a ZIP signature: $archive")

        // The classic empty ZIP consists of a complete 22-byte EOCD record and optional comment.
        val eocdRest = ByteArray(18)
        var restBytesRead = 0
        while (restBytesRead < eocdRest.size) {
            val read = input.read(eocdRest, restBytesRead, eocdRest.size - restBytesRead)
            if (read < 0) throw IOException("ZIP end-of-central-directory record is truncated: $archive")
            restBytesRead += read
        }
        val commentLength = readLittleEndianShort(eocdRest, 16)
        val isEmptyEocd = readLittleEndianShort(eocdRest, 0) == 0 &&
            readLittleEndianShort(eocdRest, 2) == 0 &&
            readLittleEndianShort(eocdRest, 4) == 0 &&
            readLittleEndianShort(eocdRest, 6) == 0 &&
            readLittleEndianInt(eocdRest, 8) == 0L &&
            readLittleEndianInt(eocdRest, 12) == 0L &&
            archive.length() == 22L + commentLength
        if (!isEmptyEocd) throw IOException("ZIP empty-archive record is malformed: $archive")
        return true
    }
}

private fun readLittleEndianShort(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

private fun readLittleEndianInt(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xff) or
        ((bytes[offset + 1].toLong() and 0xff) shl 8) or
        ((bytes[offset + 2].toLong() and 0xff) shl 16) or
        ((bytes[offset + 3].toLong() and 0xff) shl 24)

private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
private const val MAX_ENTRIES = 10_000
private const val MAX_EXPANDED_BYTES = 8L * 1024L * 1024L * 1024L
private const val COPY_BUFFER_SIZE = 64 * 1024
private const val MAX_COMPONENT_LENGTH = 180
