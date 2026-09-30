package dev.legendsayantan.backstage.core.error

import dev.legendsayantan.backstage.core.workspace.VaultBackendConfig
import dev.legendsayantan.backstage.core.workspace.WorkspaceId
import kotlinx.serialization.Serializable

@Serializable
data class OpenHandle(
    val pid: Int,
    val processName: String,
    val filePath: String,
)

sealed interface BackStageError {
    // Layer 1 Device Credential Auth Errors
    data class AuthFailed(val reason: String) : BackStageError
    data object AuthUnavailable : BackStageError

    // Layer 2 Workspace Password & Key Errors
    data object WrongPassword : BackStageError
    data class PasswordRequired(val reason: String) : BackStageError
    data object VaultKeyStale : BackStageError
    data class KeyNotInKeystore(val alias: String) : BackStageError
    data object KeyRotationInProgress : BackStageError

    // I/O & Arbitration Errors
    data class DirectoryLocked(val openHandles: List<OpenHandle>) : BackStageError
    data class InsufficientSpace(
        val requiredBytes: Long,
        val availableBytes: Long,
        val candidatesForEviction: List<WorkspaceId>,
    ) : BackStageError
    data class VaultNotFound(
        val backend: VaultBackendConfig,
        val fileName: String,
    ) : BackStageError
    data class ChunkCorrupted(
        val chunkIndex: Int,
        val refetchable: Boolean,
    ) : BackStageError
    data object HeaderMacInvalid : BackStageError
    data class InvalidVaultHeader(val detail: String) : BackStageError

    // Backend Errors
    data class MountedDriveNotFound(
        val driveName: String,
        val expectedRoot: String,
    ) : BackStageError
    data class BackendIOError(val cause: String) : BackStageError

    // Platform Tool Errors
    data class SevenZipNotInstalled(val installHint: String) : BackStageError

    // Cross-Platform File Errors
    data class CaseCollision(val paths: List<String>) : BackStageError
}

class BackStageException(
    val error: BackStageError,
    override val message: String = error.toString(),
    override val cause: Throwable? = null,
) : Exception(message, cause)
