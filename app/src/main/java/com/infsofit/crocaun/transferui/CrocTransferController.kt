package com.infsofit.crocaun.transferui

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.infsofit.crocaun.transfer.Transfer
import com.infsofit.crocaun.transfer.TransferSession
import org.json.JSONArray
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal data class TransferItem(
    val file: File,
    val displayName: String,
    val relativePath: String,
    val stagingDirectory: File,
)

internal data class RelayServerCheck(
    val address: String,
    val available: Boolean,
    val rttMillis: Long?,
    val error: String?,
)

internal data class ReceiveBatch(
    val items: List<TransferItem>,
    val warnings: List<String> = emptyList(),
)

internal data class ReceiveSaveResult(
    val remainingItems: List<TransferItem>,
    val savedCount: Int,
    val error: Throwable? = null,
)

internal class CrocTransferController(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "crocaun-croc-transfer").apply { isDaemon = true }
    }
    private val networkWorker = Executors.newFixedThreadPool(3) { task ->
        Thread(task, "crocaun-network-discovery").apply { isDaemon = true }
    }
    private val statusPoller = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "crocaun-croc-status").apply { isDaemon = true }
    }
    private val discoveryId = UUID.randomUUID().toString().replace("-", "")
    private val activeDiscoveries = ConcurrentHashMap.newKeySet<LanDiscoveryOperation>()

    fun stageSelectedDocuments(uris: List<Uri>, onComplete: (Result<List<TransferItem>>) -> Unit) {
        worker.execute {
            val result = runCatching { copyDocumentsToPrivateStaging(uris) }
            mainHandler.post { onComplete(result) }
        }
    }

    fun stageSelectedFolder(treeUri: Uri, onComplete: (Result<List<TransferItem>>) -> Unit) {
        worker.execute {
            val result = runCatching { copyDocumentTreeToPrivateStaging(treeUri) }
            mainHandler.post { onComplete(result) }
        }
    }

    fun stageManualPaths(input: String, onComplete: (Result<List<TransferItem>>) -> Unit) {
        worker.execute {
            val result = runCatching { copyManualPathsToPrivateStaging(input) }
            mainHandler.post { onComplete(result) }
        }
    }

    fun discoverLanPeers(wantedRole: String, onComplete: (Result<List<LanPeer>>) -> Unit): LanDiscoveryOperation {
        val operation = LanDiscoveryOperation()
        activeDiscoveries.add(operation)
        networkWorker.execute {
            val result = runCatching { operation.scan(discoveryId, wantedRole) }
            activeDiscoveries.remove(operation)
            if (!operation.isCancelled()) {
                mainHandler.post {
                    if (!operation.isCancelled()) onComplete(result)
                }
            }
        }
        return operation
    }

    fun checkRelayServers(
        timeoutMillis: Long = DEFAULT_RELAY_CHECK_TIMEOUT_MS,
        onComplete: (Result<List<RelayServerCheck>>) -> Unit,
    ) {
        networkWorker.execute {
            val result = runCatching {
                val encoded = Transfer.checkRelayServers(timeoutMillis)
                val json = JSONArray(encoded)
                (0 until json.length()).map { index ->
                    val item = json.getJSONObject(index)
                    RelayServerCheck(
                        address = item.getString("address"),
                        available = item.getBoolean("available"),
                        rttMillis = if (item.has("rttMillis")) item.getLong("rttMillis") else null,
                        error = item.optString("error").takeIf(String::isNotBlank),
                    )
                }
            }
            mainHandler.post { onComplete(result) }
        }
    }

    fun send(
        items: List<TransferItem>,
        password: String,
        relayAddress: String = "",
        onStatus: (String) -> Unit,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        worker.execute {
            val result = try {
                require(items.isNotEmpty()) { "Select at least one file." }
                val pathsJson = JSONArray().apply {
                    items.forEach { put(it.file.absolutePath) }
                }.toString()
                val session = Transfer.newTransferSession("send", password, pathsJson, "", relayAddress, "")
                runSession(session, onStatus) { session.run() }
                items.map { it.stagingDirectory }.distinct().forEach(::deleteStagingDirectory)
                Result.success(Unit)
            } catch (error: Exception) {
                Result.failure(error)
            }
            mainHandler.post { onComplete(result) }
        }
    }

    fun receive(
        password: String,
        relayAddress: String = "",
        unpackZipArchives: Boolean = false,
        onStatus: (String) -> Unit,
        onComplete: (Result<ReceiveBatch>) -> Unit,
    ) {
        worker.execute {
            val stagingDirectory = try {
                newStagingDirectory("receive")
            } catch (error: Exception) {
                mainHandler.post { onComplete(Result.failure(error)) }
                return@execute
            }

            val result = try {
                val session = Transfer.newTransferSession(
                    "receive",
                    password,
                    "[]",
                    stagingDirectory.absolutePath,
                    relayAddress,
                    "",
                )
                runSession(session, onStatus) { session.run() }
                val items = stagingDirectory.walkTopDown()
                    .filter { it.isFile }
                    .map { file ->
                        val relative = file.relativeTo(stagingDirectory).path.replace(File.separatorChar, '/')
                        TransferItem(file, file.name, relative, stagingDirectory)
                    }
                    .toList()
                if (items.isEmpty()) throw IOException("The transfer completed without a file.")
                val receivedItems = items.toMutableList()
                val warnings = mutableListOf<String>()
                if (unpackZipArchives) {
                    items.forEach { archive ->
                        if (!archive.file.name.endsWith(".zip", ignoreCase = true)) return@forEach
                        runCatching { extractZipArchive(archive.file, stagingDirectory) }
                            .onSuccess { extractedFiles ->
                                val archiveBaseName = archive.file.name.dropLast(4).ifBlank { "archive" }
                                val extractionRoot = File(stagingDirectory, "${windowsCompatibleFileName(archiveBaseName)}-unpacked")
                                extractedFiles.forEach { file ->
                                    val relative = file.relativeTo(extractionRoot).path.replace(File.separatorChar, '/')
                                    receivedItems += TransferItem(file, file.name, relative, extractionRoot)
                                }
                            }
                            .onFailure { error -> warnings += "${archive.displayName}: ${error.message.orEmpty()}" }
                    }
                }
                Result.success(ReceiveBatch(receivedItems, warnings))
            } catch (error: Exception) {
                deleteStagingDirectory(stagingDirectory)
                Result.failure(error)
            }
            mainHandler.post { onComplete(result) }
        }
    }

    fun saveReceivedItems(
        items: List<TransferItem>,
        treeUri: Uri?,
        directoryPath: String,
        onComplete: (ReceiveSaveResult) -> Unit,
    ) {
        worker.execute {
            val remaining = mutableListOf<TransferItem>()
            var savedCount = 0
            var firstError: Throwable? = null
            items.forEach { item ->
                runCatching {
                    when {
                        treeUri != null -> saveItemToDocumentTree(item, treeUri)
                        directoryPath.isNotBlank() -> saveItemToDirectory(item, directoryPath)
                        else -> throw IOException("Choose a SAF folder or enter an absolute receive folder path first.")
                    }
                }.onSuccess {
                    savedCount++
                }.onFailure { error ->
                    remaining += item
                    if (firstError == null) firstError = error
                }
            }
            val result = ReceiveSaveResult(remaining, savedCount, firstError)
            mainHandler.post { onComplete(result) }
        }
    }

    private fun saveItemToDirectory(item: TransferItem, directoryPath: String) {
        val requestedRoot = File(directoryPath.trim())
        if (!requestedRoot.isAbsolute) throw IOException("Enter an absolute receive folder path.")
        val root = requestedRoot.canonicalFile
        if (!root.exists() && !root.mkdirs()) throw IOException("Could not create the receive folder: $root")
        if (!root.isDirectory || !root.canWrite()) throw IOException("The receive folder is not writable: $root")
        val components = safeRelativeComponents(item.relativePath.ifBlank { item.displayName })
        var parent = root
        components.dropLast(1).forEach { component ->
            parent = File(parent, windowsCompatibleFileName(component))
            if (!parent.exists() && !parent.mkdir()) throw IOException("Could not create receive subfolder: $parent")
            val parentPath = parent.canonicalPath
            val rootPrefix = if (root.path.endsWith(File.separator)) root.path else root.path + File.separator
            if (!parent.isDirectory || !parentPath.startsWith(rootPrefix)) {
                throw IOException("The receive subfolder is outside the selected folder.")
            }
        }
        val safeName = windowsCompatibleFileName(components.lastOrNull() ?: item.displayName)
        val destination = uniqueFilesystemChild(parent, safeName)
        copyReceivedFile(item, destination)
    }

    private fun saveItemToDocumentTree(item: TransferItem, treeUri: Uri) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        var parentId = rootId
        var parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        val components = safeRelativeComponents(item.relativePath.ifBlank { item.displayName })
        components.dropLast(1).forEach { component ->
            val safeComponent = windowsCompatibleFileName(component)
            val existing = findTreeChild(treeUri, parentId, safeComponent)
            if (existing != null && existing.second == DocumentsContract.Document.MIME_TYPE_DIR) {
                parentId = existing.first
                parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentId)
            } else {
                val folderName = uniqueTreeChildName(treeUri, parentId, safeComponent)
                val folderUri = DocumentsContract.createDocument(
                    appContext.contentResolver,
                    parentUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    folderName,
                ) ?: throw IOException("The selected file manager could not create folder $folderName.")
                parentId = DocumentsContract.getDocumentId(folderUri)
                parentUri = folderUri
            }
        }
        val safeName = windowsCompatibleFileName(components.lastOrNull() ?: item.displayName)
        val outputName = uniqueTreeChildName(treeUri, parentId, safeName)
        val extension = outputName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
        val destination = DocumentsContract.createDocument(appContext.contentResolver, parentUri, mimeType, outputName)
            ?: throw IOException("The selected file manager could not create $outputName.")
        try {
            val output = appContext.contentResolver.openOutputStream(destination)
                ?: throw IOException("Android could not write $outputName.")
            item.file.inputStream().use { input -> output.use { target -> input.copyTo(target, DEFAULT_BUFFER_SIZE) } }
            item.file.delete()
            deleteIfEmpty(item.stagingDirectory)
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(appContext.contentResolver, destination) }
            throw error
        }
    }

    private fun copyReceivedFile(item: TransferItem, destination: File) {
        if (!destination.createNewFile()) throw IOException("Could not create destination without overwriting: $destination")
        try {
            item.file.inputStream().use { input ->
                FileOutputStream(destination).use { output -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
            item.file.delete()
            deleteIfEmpty(item.stagingDirectory)
        } catch (error: Exception) {
            destination.delete()
            throw error
        }
    }

    private fun safeRelativeComponents(path: String): List<String> {
        val normalized = path.replace('\\', '/')
        if (normalized.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            throw IOException("Received file path must be relative to its destination folder.")
        }
        val rawComponents = normalized.split('/').filter { it.isNotBlank() && it != "." }
        if (rawComponents.any { it == ".." }) throw IOException("Received file path traverses its destination folder.")
        val components = rawComponents
        if (components.isEmpty()) throw IOException("Received file has no usable filename.")
        return components
    }

    private fun uniqueFilesystemChild(parent: File, safeName: String): File {
        var candidate = File(parent, safeName)
        if (!candidate.exists()) return candidate
        val extensionStart = safeName.lastIndexOf('.').takeIf { it > 0 } ?: safeName.length
        val base = safeName.substring(0, extensionStart)
        val extension = safeName.substring(extensionStart)
        var serial = 2
        while (true) {
            candidate = File(parent, "$base ($serial)$extension")
            if (!candidate.exists()) return candidate
            serial++
        }
    }

    private fun findTreeChild(treeUri: Uri, parentId: String, name: String): Pair<String, String>? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
        return appContext.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null,
            null,
            null,
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameColumn)?.equals(name, ignoreCase = true) == true) {
                    return@use cursor.getString(idColumn) to cursor.getString(mimeColumn)
                }
            }
            null
        }
    }

    private fun uniqueTreeChildName(treeUri: Uri, parentId: String, safeName: String): String {
        if (findTreeChild(treeUri, parentId, safeName) == null) return safeName
        val extensionStart = safeName.lastIndexOf('.').takeIf { it > 0 } ?: safeName.length
        val base = safeName.substring(0, extensionStart)
        val extension = safeName.substring(extensionStart)
        var serial = 2
        while (true) {
            val candidate = "$base ($serial)$extension"
            if (findTreeChild(treeUri, parentId, candidate) == null) return candidate
            serial++
        }
    }

    fun exportToDocument(item: TransferItem, destination: Uri, onComplete: (Result<Unit>) -> Unit) {
        worker.execute {
            val result = try {
                item.file.inputStream().use { input ->
                    val output = appContext.contentResolver.openOutputStream(destination)
                        ?: throw IOException("Android could not open the selected destination.")
                    output.use { target -> input.copyTo(target, DEFAULT_BUFFER_SIZE) }
                }
                item.file.delete()
                deleteIfEmpty(item.stagingDirectory)
                Result.success(Unit)
            } catch (error: Exception) {
                Result.failure(error)
            }
            mainHandler.post { onComplete(result) }
        }
    }

    fun discard(items: List<TransferItem>) {
        worker.execute {
            items.map { it.stagingDirectory }.distinct().forEach(::deleteStagingDirectory)
        }
    }

    private fun copyDocumentsToPrivateStaging(uris: List<Uri>): List<TransferItem> {
        if (uris.isEmpty()) throw IOException("No files were selected.")
        val sources = uris.map { uri ->
            val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: "crocaun-file"
            DocumentSource(uri, name, name)
        }
        return copyDocumentSourcesToPrivateStaging(sources)
    }

    private fun copyDocumentSourcesToPrivateStaging(sources: List<DocumentSource>): List<TransferItem> {
        if (sources.isEmpty()) throw IOException("The selected folder contains no files.")
        val directory = newStagingDirectory("send")
        try {
            val safeNames = uniqueWindowsCompatibleFileNames(sources.map { it.name })
            return sources.mapIndexed { index, source ->
                val safeName = safeNames[index]
                val destination = File(directory, safeName)
                val input = appContext.contentResolver.openInputStream(source.uri)
                    ?: throw IOException("Android could not read $safeName.")
                input.use { sourceStream ->
                    FileOutputStream(destination).use { target -> sourceStream.copyTo(target, DEFAULT_BUFFER_SIZE) }
                }
                TransferItem(destination, safeName, source.relativePath, directory)
            }
        } catch (error: Exception) {
            deleteStagingDirectory(directory)
            throw error
        }
    }

    private fun copyManualPathsToPrivateStaging(input: String): List<TransferItem> {
        val sources = collectManualPathSources(parseManualPathInput(input))
        val directory = newStagingDirectory("send")
        try {
            val safeNames = uniqueWindowsCompatibleFileNames(sources.map { it.file.name })
            return sources.mapIndexed { index, source ->
                val safeName = safeNames[index]
                val destination = File(directory, safeName)
                FileInputStream(source.file).use { inputStream ->
                    FileOutputStream(destination).use { output -> inputStream.copyTo(output, DEFAULT_BUFFER_SIZE) }
                }
                TransferItem(destination, safeName, source.relativePath, directory)
            }
        } catch (error: Exception) {
            deleteStagingDirectory(directory)
            throw error
        }
    }

    private fun copyDocumentTreeToPrivateStaging(treeUri: Uri): List<TransferItem> {
        val sources = collectDocumentTreeSources(treeUri)
        return copyDocumentSourcesToPrivateStaging(sources)
    }

    private fun collectDocumentTreeSources(treeUri: Uri): List<DocumentSource> {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }
            .getOrElse { throw IOException("Android could not open the selected folder.", it) }
        val visited = mutableSetOf<String>()
        val sources = mutableListOf<DocumentSource>()

        fun visit(parentId: String, prefix: String) {
            if (!visited.add(parentId)) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            appContext.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idColumn) ?: continue
                    val name = cursor.getString(nameColumn)?.takeIf(String::isNotBlank) ?: id.substringAfterLast('/')
                    val relative = if (prefix.isEmpty()) name else "$prefix/$name"
                    if (cursor.getString(mimeColumn) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        visit(id, relative)
                    } else {
                        sources += DocumentSource(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name, relative)
                        if (sources.size > MAX_FOLDER_FILES) {
                            throw IOException("The selected folder exceeds the $MAX_FOLDER_FILES-file limit.")
                        }
                    }
                }
            } ?: throw IOException("Android could not list the selected folder.")
        }

        visit(rootId, "")
        return sources
    }

    private data class DocumentSource(val uri: Uri, val name: String, val relativePath: String)

    private fun queryDisplayName(uri: Uri): String? {
        return appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                } else null
            }
    }

    private fun newStagingDirectory(kind: String): File {
        val root = File(appContext.filesDir, "croc-transfer/$kind")
        val directory = File(root, UUID.randomUUID().toString())
        if (!directory.mkdirs()) throw IOException("Could not create a private transfer staging folder.")
        return directory
    }

    private fun runSession(session: TransferSession, onStatus: (String) -> Unit, transfer: () -> Unit) {
        val poll: ScheduledFuture<*> = statusPoller.scheduleAtFixedRate(
            {
                val status = runCatching { session.status() }.getOrNull()
                if (status != null) mainHandler.post { onStatus(status) }
            },
            0,
            1,
            TimeUnit.SECONDS,
        )
        try {
            transfer()
        } finally {
            poll.cancel(true)
        }
    }

    private fun deleteStagingDirectory(directory: File) {
        if (directory.exists()) directory.deleteRecursively()
        deleteIfEmpty(directory.parentFile)
    }

    private fun deleteIfEmpty(directory: File?) {
        if (directory != null && directory.isDirectory && directory.list()?.isEmpty() == true) {
            directory.delete()
            deleteIfEmpty(directory.parentFile)
        }
    }

    override fun close() {
        activeDiscoveries.forEach(LanDiscoveryOperation::cancel)
        worker.shutdown()
        networkWorker.shutdownNow()
        statusPoller.shutdownNow()
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
        const val DEFAULT_RELAY_CHECK_TIMEOUT_MS = 4_000L
        const val MAX_FOLDER_FILES = 10_000
    }
}

internal fun windowsCompatibleFileName(name: String): String {
    val replaced = buildString(name.length) {
        name.forEach { character ->
            if (character.code < 32 || character.code == 127 || character in "<>:\"/\\|?*") {
                append('_')
            } else {
                append(character)
            }
        }
    }.trim().trimEnd('.', ' ').take(MAX_WINDOWS_FILE_NAME_LENGTH)

    val candidate = replaced.ifBlank { "crocaun-file" }
    val baseName = candidate.substringBefore('.').uppercase(Locale.ROOT)
    val reserved = baseName in setOf("CON", "PRN", "AUX", "NUL") ||
        baseName.matches(Regex("(?:COM|LPT)[1-9]"))
    return if (reserved) "_$candidate" else candidate
}

internal fun uniqueWindowsCompatibleFileNames(names: List<String>): List<String> {
    val used = mutableSetOf<String>()
    return names.map { original ->
        val safe = windowsCompatibleFileName(original)
        if (used.add(safe.lowercase(Locale.ROOT))) return@map safe

        val extensionStart = safe.lastIndexOf('.').takeIf { it > 0 } ?: safe.length
        val base = safe.substring(0, extensionStart)
        val extension = safe.substring(extensionStart)
        var serial = 2
        while (true) {
            val suffix = " ($serial)"
            val maxBaseLength = (MAX_WINDOWS_FILE_NAME_LENGTH - suffix.length - extension.length).coerceAtLeast(1)
            val candidate = base.take(maxBaseLength) + suffix + extension
            if (used.add(candidate.lowercase(Locale.ROOT))) return@map candidate
            serial++
        }
        @Suppress("UNREACHABLE_CODE")
        safe
    }
}

private const val MAX_WINDOWS_FILE_NAME_LENGTH = 180
