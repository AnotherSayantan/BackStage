package dev.legendsayantan.backstage.core.io

import dev.legendsayantan.backstage.core.crypto.Blake3
import dev.legendsayantan.backstage.core.crypto.toHexString
import dev.legendsayantan.backstage.core.workspace.WorkspaceId
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions

@Serializable
data class FileSnapshotEntry(
    val relativePath: String,
    val sizeBytes: Long,
    val modifiedAtEpochMillis: Long,
    val blake3Hex: String,
    val isDirectory: Boolean = false,
    val symlinkTarget: String = "",
    val unixModeString: String = "",
    val isExecutable: Boolean = false,
)

@Serializable
data class LockToken(
    val workspaceId: String,
    val pid: Long,
    val loadedAtEpochSeconds: Long,
    val snapshot: List<FileSnapshotEntry> = emptyList(),
)

data class SnapshotDiff(
    val added: List<FileSnapshotEntry>,
    val modified: List<FileSnapshotEntry>,
    val deleted: List<String>,
) {
    val hasChanges: Boolean
        get() = added.isNotEmpty() || modified.isNotEmpty() || deleted.isNotEmpty()
}

object FolderLock {
    const val LOCK_FILE_NAME = ".bstage_lock"
    val IGNORED_FILES = setOf(LOCK_FILE_NAME, "backstage.bat", "backstage.sh")

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun readLock(directory: File): LockToken? {
        val lockFile = File(directory, LOCK_FILE_NAME)
        if (!lockFile.isFile) return null
        return runCatching {
            json.decodeFromString<LockToken>(lockFile.readText())
        }.getOrNull()
    }

    fun writeLock(
        directory: File,
        workspaceId: WorkspaceId,
        pid: Long = currentPid(),
        loadedAtEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        onProgress: (Float, Long, Long) -> Unit = { _, _, _ -> },
        snapshot: List<FileSnapshotEntry> = captureSnapshot(directory, onProgress),
    ): LockToken {
        directory.mkdirs()
        val token = LockToken(
            workspaceId = workspaceId.value,
            pid = pid,
            loadedAtEpochSeconds = loadedAtEpochSeconds,
            snapshot = snapshot,
        )
        File(directory, LOCK_FILE_NAME).writeText(json.encodeToString(token))
        return token
    }

    fun removeLock(directory: File) {
        File(directory, LOCK_FILE_NAME).delete()
    }

    fun captureSnapshot(
        directory: File,
        onProgress: (Float, Long, Long) -> Unit = { _, _, _ -> },
    ): List<FileSnapshotEntry> {
        if (!directory.isDirectory) return emptyList()
        val allPaths = Files.walk(directory.toPath()).use { stream ->
            stream.filter { it != directory.toPath() }
                .sorted()
                .toList()
        }
        val totalBytes = allPaths.sumOf { p ->
            runCatching {
                if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) Files.size(p) else 0L
            }.getOrDefault(0L)
        }.coerceAtLeast(1L)

        var bytesDone = 0L
        val readBuf = ByteArray(65_536)
        val entries = mutableListOf<FileSnapshotEntry>()

        for ((idx, path) in allPaths.withIndex()) {
            val relPath = directory.toPath().relativize(path).toString().replace('\\', '/')
            val fileName = path.fileName.toString()
            if (fileName in IGNORED_FILES) continue

            val isSymlink = Files.isSymbolicLink(path)
            val isDir = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
            val modifiedMillis = runCatching {
                Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
            }.getOrDefault(0L)
            val isExec = runCatching { Files.isExecutable(path) }.getOrDefault(false)
            val modeStr = runCatching {
                Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
                    .let { PosixFilePermissions.toString(it) }
            }.getOrDefault("")

            when {
                isSymlink -> {
                    val target = runCatching {
                        Files.readSymbolicLink(path).toString().replace('\\', '/')
                    }.getOrDefault("")
                    entries.add(
                        FileSnapshotEntry(
                            relativePath = relPath,
                            sizeBytes = 0L,
                            modifiedAtEpochMillis = modifiedMillis,
                            blake3Hex = Blake3.hash(target.encodeToByteArray()).toHexString(),
                            symlinkTarget = target,
                            isExecutable = isExec,
                            unixModeString = modeStr,
                        )
                    )
                }
                isDir -> {
                    entries.add(
                        FileSnapshotEntry(
                            relativePath = relPath,
                            sizeBytes = 0L,
                            modifiedAtEpochMillis = modifiedMillis,
                            blake3Hex = "",
                            isDirectory = true,
                            isExecutable = isExec,
                            unixModeString = modeStr,
                        )
                    )
                }
                else -> {
                    val file = path.toFile()
                    val fileLen = file.length()
                    val hashHex = runCatching {
                        val hasher = Blake3.StreamingHasher()
                        java.io.FileInputStream(file).use { fis ->
                            while (true) {
                                val read = fis.read(readBuf)
                                if (read <= 0) break
                                hasher.update(readBuf, 0, read)
                                bytesDone += read
                                val frac = (bytesDone.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                                onProgress(frac, bytesDone, totalBytes)
                            }
                        }
                        hasher.finish().toHexString()
                    }.getOrDefault("")
                    entries.add(
                        FileSnapshotEntry(
                            relativePath = relPath,
                            sizeBytes = fileLen,
                            modifiedAtEpochMillis = modifiedMillis,
                            blake3Hex = hashHex,
                            isExecutable = isExec,
                            unixModeString = modeStr,
                        )
                    )
                }
            }
            if (totalBytes <= 1L && allPaths.isNotEmpty()) {
                val frac = ((idx + 1).toFloat() / allPaths.size.toFloat()).coerceIn(0f, 1f)
                onProgress(frac, (idx + 1).toLong(), allPaths.size.toLong())
            }
        }
        onProgress(1f, totalBytes, totalBytes)
        return entries.sortedBy { it.relativePath }
    }

    fun computeDiff(
        previousSnapshot: List<FileSnapshotEntry>,
        currentDirectory: File,
        onProgress: (Float, Long, Long) -> Unit = { _, _, _ -> },
    ): SnapshotDiff {
        val currentEntries = captureSnapshot(currentDirectory, onProgress)
        val prevByPath = previousSnapshot.associateBy { it.relativePath }
        val currByPath = currentEntries.associateBy { it.relativePath }

        val added = mutableListOf<FileSnapshotEntry>()
        val modified = mutableListOf<FileSnapshotEntry>()
        val deleted = mutableListOf<String>()

        for ((path, curr) in currByPath) {
            val prev = prevByPath[path]
            if (prev == null) {
                added.add(curr)
            } else if (
                prev.blake3Hex != curr.blake3Hex ||
                prev.sizeBytes != curr.sizeBytes ||
                prev.symlinkTarget != curr.symlinkTarget ||
                prev.isDirectory != curr.isDirectory ||
                prev.isExecutable != curr.isExecutable ||
                prev.unixModeString != curr.unixModeString
            ) {
                modified.add(curr)
            }
        }

        for (path in prevByPath.keys) {
            if (path !in currByPath) {
                deleted.add(path)
            }
        }

        return SnapshotDiff(added = added, modified = modified, deleted = deleted)
    }

    private fun currentPid(): Long = runCatching {
        ProcessHandle.current().pid()
    }.getOrDefault(0L)
}
