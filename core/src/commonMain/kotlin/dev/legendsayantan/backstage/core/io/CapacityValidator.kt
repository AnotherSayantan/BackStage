package dev.legendsayantan.backstage.core.io

import dev.legendsayantan.backstage.core.backend.VaultBackend
import dev.legendsayantan.backstage.core.crypto.TinyHeader
import dev.legendsayantan.backstage.core.crypto.VaultFormatConfig
import dev.legendsayantan.backstage.core.workspace.WorkspaceConfig
import dev.legendsayantan.backstage.core.workspace.WorkspaceId
import java.io.File
import java.nio.file.Files

data class CapacityCheckResult(
    val hasEnoughSpace: Boolean,
    val requiredBytes: Long,
    val availableBytes: Long,
    val evictionCandidates: List<WorkspaceConfig> = emptyList(),
)

object CapacityValidator {
    private const val SAFETY_HEADROOM_BYTES = 16L * 1024L * 1024L // 16 MiB buffer

    suspend fun estimateRequiredBytes(
        workspace: WorkspaceConfig,
        backend: VaultBackend,
    ): Long {
        workspace.uncompressedSizeBytes?.takeIf { it > 0L }?.let { return it }

        val base = workspace.vaultFileName.substringBeforeLast('.')
        val candidates = listOf(workspace.vaultFileName, "$base.7z", "$base.bvault", "$base.bstage", "$base.7z.001")
        val actualFileName = candidates.firstOrNull { backend.exists(it) } ?: workspace.vaultFileName

        if (!backend.exists(actualFileName)) return 0L

        val effectiveFormat = when {
            actualFileName.endsWith(".bvault", ignoreCase = true) -> VaultFormatConfig.PlainCompressed
            actualFileName.endsWith(".bstage", ignoreCase = true) -> VaultFormatConfig.Native
            actualFileName.endsWith(".7z", ignoreCase = true) || actualFileName.contains(".7z.") -> {
                if (workspace.vaultFormat.isEncrypted) VaultFormatConfig.Portable else VaultFormatConfig.PlainSevenZip
            }
            else -> workspace.vaultFormat
        }

        return when (effectiveFormat) {
            VaultFormatConfig.Native, VaultFormatConfig.PlainCompressed -> {
                runCatching {
                    val tinyBytes = backend.readBytes(
                        fileName = actualFileName,
                        offset = 0L,
                        count = TinyHeader.TINY_HEADER_BYTES,
                    )
                    val tiny = TinyHeader.parse(tinyBytes)
                    tiny.chunkCount.toLong() * tiny.chunkSize.toLong()
                }.getOrElse {
                    backend.sizeBytes(actualFileName) * 2L
                }
            }
            VaultFormatConfig.Portable, VaultFormatConfig.PlainSevenZip -> {
                // Estimate 2.5x compressed .7z size if cached uncompressed size is unknown
                (backend.sizeBytes(actualFileName) * 5L) / 2L
            }
        }
    }

    suspend fun validateLocalCapacity(
        targetWorkspace: WorkspaceConfig,
        backend: VaultBackend,
        allWorkspacesOrderedByLru: List<WorkspaceConfig>,
        availableBytesOverride: Long? = null,
    ): CapacityCheckResult {
        val targetDir = File(targetWorkspace.resolvedLocalPath())
        val probeDir = generateSequence(targetDir) { it.parentFile }.firstOrNull { it.exists() }
            ?: File(".")
        val availableBytes = availableBytesOverride ?: probeDir.usableSpace
        val targetFileStore = runCatching { Files.getFileStore(probeDir.toPath()) }.getOrNull()
        val requiredBytes = estimateRequiredBytes(targetWorkspace, backend) + SAFETY_HEADROOM_BYTES

        if (availableBytes >= requiredBytes) {
            return CapacityCheckResult(
                hasEnoughSpace = true,
                requiredBytes = requiredBytes,
                availableBytes = availableBytes,
            )
        }

        val deficit = requiredBytes - availableBytes
        var accumulated = 0L
        val candidates = mutableListOf<WorkspaceConfig>()

        for (candidate in allWorkspacesOrderedByLru) {
            if (candidate.id == targetWorkspace.id) continue
            val candidateDir = File(candidate.resolvedLocalPath())
            val lock = FolderLock.readLock(candidateDir)
            if (lock != null && lock.workspaceId == candidate.id.value) {
                // B-14: Only consider candidates on the same filesystem volume
                val candidateProbeDir = generateSequence(candidateDir) { it.parentFile }.firstOrNull { it.exists() }
                    ?: candidateDir
                val candidateFileStore = runCatching { Files.getFileStore(candidateProbeDir.toPath()) }.getOrNull()
                if (targetFileStore != null && candidateFileStore != null && targetFileStore != candidateFileStore) {
                    continue // Different volume — evicting would not free space on target volume
                }
                candidates.add(candidate)
                val freedEstimate = candidate.uncompressedSizeBytes
                    ?: directorySizeBytes(candidateDir)
                accumulated += freedEstimate
                if (accumulated >= deficit) break
            }
        }

        // B-14: Only suggest eviction if accumulated freed space would actually cover the deficit
        val evictable = if (accumulated >= deficit) candidates else emptyList()
        return CapacityCheckResult(
            hasEnoughSpace = false,
            requiredBytes = requiredBytes,
            availableBytes = availableBytes,
            evictionCandidates = evictable,
        )
    }

    fun directorySizeBytes(dir: File): Long {
        if (!dir.exists()) return 0L
        // B-18: Skip symlinks to avoid following them outside workspace
        return dir.walkTopDown().filter { file ->
            file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath())
        }.sumOf { it.length() }
    }
}
