package dev.legendsayantan.backstage.core.backend

import dev.legendsayantan.backstage.core.error.BackStageError
import dev.legendsayantan.backstage.core.error.BackStageException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.asSink
import kotlinx.io.asSource
import java.nio.file.StandardCopyOption

class LocalPathBackend(
    val rootDirectory: File,
    override val displayName: String = "Local (${rootDirectory.path})",
) : VaultBackend {

    constructor(directoryPath: String) : this(File(directoryPath))

    fun resolveFile(fileName: String): File {
        rootDirectory.mkdirs()
        return File(rootDirectory, fileName)
    }

    /**
     * Resolves the primary archive file for extraction or validation.
     * If [fileName] is a multi-volume 7z archive, returns `<fileName>.001` if present.
     */
    fun resolveArchiveFile(fileName: String): File {
        val f = resolveFile(fileName)
        if (f.isFile) return f
        if (fileName.endsWith(".7z", ignoreCase = true)) {
            val part1 = File(rootDirectory, "$fileName.001")
            if (part1.isFile) return part1
        }
        return f
    }

    override suspend fun exists(fileName: String): Boolean = withContext(Dispatchers.IO) {
        val f = File(rootDirectory, fileName)
        if (f.isFile) return@withContext true
        if (fileName.endsWith(".7z", ignoreCase = true)) {
            File(rootDirectory, "$fileName.001").isFile
        } else {
            false
        }
    }

    override suspend fun sizeBytes(fileName: String): Long = withContext(Dispatchers.IO) {
        val f = File(rootDirectory, fileName)
        if (f.isFile) return@withContext f.length()
        if (fileName.endsWith(".7z", ignoreCase = true)) {
            val prefix = "$fileName."
            val parts = rootDirectory.listFiles { _, name ->
                name.startsWith(prefix) && name.removePrefix(prefix).toIntOrNull() != null
            }
            if (!parts.isNullOrEmpty()) {
                return@withContext parts.sumOf { it.length() }
            }
        }
        0L
    }

    override suspend fun readBytes(fileName: String, offset: Long, count: Int): ByteArray =
        withContext(Dispatchers.IO) {
            val f = File(rootDirectory, fileName)
            if (!f.isFile) {
                throw BackStageException(
                    BackStageError.BackendIOError("Vault file not found: ${f.absolutePath}")
                )
            }
            RandomAccessFile(f, "r").use { raf ->
                raf.seek(offset)
                val remaining = (raf.length() - offset).coerceAtLeast(0L)
                val toRead = minOf(count.toLong(), remaining).toInt()
                val buf = ByteArray(toRead)
                raf.readFully(buf)
                buf
            }
        }

    override suspend fun readAllBytes(fileName: String): ByteArray = withContext(Dispatchers.IO) {
        val f = File(rootDirectory, fileName)
        if (!f.isFile) {
            throw BackStageException(
                BackStageError.BackendIOError("Vault file not found: ${f.absolutePath}")
            )
        }
        f.readBytes()
    }

    companion object {
        fun atomicMoveWithFallback(tmp: File, target: File) {
            try {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }
    }

    override suspend fun writeAllBytes(fileName: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        val target = resolveFile(fileName)
        target.parentFile?.mkdirs()
        val tmp = java.io.File(target.parent, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        atomicMoveWithFallback(tmp, target)
    }

    override suspend fun openReadStream(fileName: String): RawSource = withContext(Dispatchers.IO) {
        val f = File(rootDirectory, fileName)
        FileInputStream(f).asSource()
    }

    override suspend fun openWriteStream(fileName: String): RawSink = withContext(Dispatchers.IO) {
        val target = resolveFile(fileName)
        target.parentFile?.mkdirs()
        val tmp = java.io.File(target.parent, "${target.name}.tmp")
        val fos = java.io.BufferedOutputStream(FileOutputStream(tmp), 2 * 1024 * 1024)
        val delegate = fos.asSink()
        object : RawSink by delegate {
            private var closed = false
            override fun close() {
                if (closed) return
                closed = true
                delegate.close()
                atomicMoveWithFallback(tmp, target)
            }
        }
    }

    override suspend fun patchBytes(fileName: String, offset: Long, bytes: ByteArray) =
        withContext(Dispatchers.IO) {
            val f = File(rootDirectory, fileName)
            if (!f.isFile) {
                throw BackStageException(
                    BackStageError.BackendIOError("Cannot patch missing vault file: ${f.absolutePath}")
                )
            }
            RandomAccessFile(f, "rw").use { raf ->
                raf.seek(offset)
                raf.write(bytes)
            }
        }

    override suspend fun delete(fileName: String) = withContext(Dispatchers.IO) {
        File(rootDirectory, fileName).delete()
        if (fileName.endsWith(".7z", ignoreCase = true)) {
            val prefix = "$fileName."
            rootDirectory.listFiles { _, name ->
                name.startsWith(prefix) && name.removePrefix(prefix).toIntOrNull() != null
            }?.forEach { it.delete() }
        }
        Unit
    }

    override suspend fun availableSpaceBytes(): Long = withContext(Dispatchers.IO) {
        rootDirectory.mkdirs()
        rootDirectory.usableSpace
    }
}
