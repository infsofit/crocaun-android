package com.infsofit.crocaun.transferui

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import com.infsofit.crocaun.transfer.Transfer
import com.infsofit.crocaun.transfer.TransferSession
import org.json.JSONArray
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

internal data class TransferItem(
    val file: File,
    val displayName: String,
    val relativePath: String,
    val stagingDirectory: File,
)

internal class CrocTransferController(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "crocaun-croc-transfer").apply { isDaemon = true }
    }
    private val statusPoller = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "crocaun-croc-status").apply { isDaemon = true }
    }

    fun stageSelectedDocument(uri: Uri, onComplete: (Result<TransferItem>) -> Unit) {
        worker.execute {
            val result = runCatching { copyDocumentToPrivateStaging(uri) }
            mainHandler.post { onComplete(result) }
        }
    }

    fun send(item: TransferItem, password: String, onStatus: (String) -> Unit, onComplete: (Result<Unit>) -> Unit) {
        worker.execute {
            val result = try {
                val pathsJson = JSONArray().put(item.file.absolutePath).toString()
                val session = Transfer.newTransferSession("send", password, pathsJson, "", "", "")
                runSession(session, onStatus) { session.run() }
                deleteStagingDirectory(item.stagingDirectory)
                Result.success(Unit)
            } catch (error: Exception) {
                Result.failure(error)
            }
            mainHandler.post { onComplete(result) }
        }
    }

    fun receive(password: String, onStatus: (String) -> Unit, onComplete: (Result<List<TransferItem>>) -> Unit) {
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
                    "",
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
                Result.success(items)
            } catch (error: Exception) {
                deleteStagingDirectory(stagingDirectory)
                Result.failure(error)
            }
            mainHandler.post { onComplete(result) }
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

    fun discard(item: TransferItem) {
        worker.execute { deleteStagingDirectory(item.stagingDirectory) }
    }

    private fun copyDocumentToPrivateStaging(uri: Uri): TransferItem {
        val originalName = queryDisplayName(uri) ?: uri.lastPathSegment ?: "crocaun-file"
        val safeName = windowsCompatibleFileName(originalName)
        val directory = newStagingDirectory("send")
        val destination = File(directory, safeName)
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IOException("Android could not read the selected document.")
            input.use { source ->
                FileOutputStream(destination).use { target -> source.copyTo(target, DEFAULT_BUFFER_SIZE) }
            }
        } catch (error: Exception) {
            deleteStagingDirectory(directory)
            throw error
        }
        return TransferItem(destination, safeName, safeName, directory)
    }

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
        worker.shutdown()
        statusPoller.shutdownNow()
    }

    private companion object {
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
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
    }.trim().trimEnd('.', ' ').take(180)

    val candidate = replaced.ifBlank { "crocaun-file" }
    val baseName = candidate.substringBefore('.').uppercase(Locale.ROOT)
    val reserved = baseName in setOf("CON", "PRN", "AUX", "NUL") ||
        baseName.matches(Regex("(?:COM|LPT)[1-9]"))
    return if (reserved) "_$candidate" else candidate
}
