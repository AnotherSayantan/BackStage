package dev.legendsayantan.backstage.core.workspace

import dev.legendsayantan.backstage.core.crypto.VaultFormatConfig
import dev.legendsayantan.backstage.core.crypto.WorkspaceKeyMode
import dev.legendsayantan.backstage.core.error.BackStageError
import dev.legendsayantan.backstage.core.platform.Platform
import dev.legendsayantan.backstage.core.platform.currentPlatform
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
enum class MountedDriveType {
    GOOGLE_DRIVE,
    ONEDRIVE,
    ICLOUD_DRIVE,
    NAS_CIFS,
    NAS_NFS,
    CUSTOM
}

@Serializable
sealed interface VaultBackendConfig {
    @Serializable
    data class MountedDrive(
        val driveName: String,
        val driveType: MountedDriveType = MountedDriveType.GOOGLE_DRIVE,
        val relativePath: String,
        val platformRoots: Map<Platform, String> = emptyMap(),
    ) : VaultBackendConfig

    @Serializable
    data class LocalPath(
        val directoryPath: String,
    ) : VaultBackendConfig

    @Serializable
    data class ExternalDrive(
        val driveLabel: String,
        val volumeSerial: String,
        val relativePath: String,
    ) : VaultBackendConfig

    @Serializable
    data class S3Compatible(
        val endpointUrl: String,
        val bucket: String,
        val prefix: String,
        val credentialAlias: String,
    ) : VaultBackendConfig

    @Serializable
    data class WebDav(
        val endpointUrl: String,
        val credentialAlias: String,
    ) : VaultBackendConfig
}

@Serializable
sealed interface RetentionPolicy {
    @Serializable
    data class InactivityExpiry(val afterDays: Int) : RetentionPolicy

    @Serializable
    data class DeadlineExpiry(val deadlineEpochSeconds: Long) : RetentionPolicy

    @Serializable
    data object Never : RetentionPolicy
}

@Serializable
data class PrefetchSchedule(
    val cronExpression: String,
    val onIdleNetworkOnly: Boolean = true,
    val cancelIfInsufficientSpace: Boolean = false,
)

@Serializable
data class WorkspaceConfig(
    val id: WorkspaceId,
    val displayName: String,
    val description: String = "",
    val iconEmoji: String? = null,
    val iconImagePath: String? = null,
    val tags: List<String> = emptyList(),
    val vaultBackend: VaultBackendConfig,
    val vaultFileName: String,
    val localTargetPath: String,
    val platformPathOverrides: Map<Platform, String> = emptyMap(),
    val vaultFormat: VaultFormatConfig = VaultFormatConfig.Native,
    val keyMode: WorkspaceKeyMode? = WorkspaceKeyMode.PasswordBacked(
        keystoreAlias = defaultKeystoreAlias(id)
    ),
    val groupId: GroupId? = null,
    val retentionPolicy: RetentionPolicy? = null,
    val prefetchSchedule: PrefetchSchedule? = null,
    val platformOrigin: Platform = currentPlatform(),
    val createdAtEpochSeconds: Long,
    val lastActiveAtEpochSeconds: Long? = null,
    val lastEvictedAtEpochSeconds: Long? = null,
    val uncompressedSizeBytes: Long? = null,
) {
    init {
        val isUnencrypted = !vaultFormat.isEncrypted
        require(isUnencrypted == (keyMode == null)) {
            if (isUnencrypted) {
                "Unencrypted workspace '$displayName' ($vaultFormat) must have keyMode = null"
            } else {
                "Encrypted workspace '$displayName' ($vaultFormat) must have a non-null keyMode"
            }
        }
    }

    val encryptionEnabled: Boolean
        get() = vaultFormat.isEncrypted

    fun resolvedLocalPath(platform: Platform = currentPlatform()): String =
        platformPathOverrides[platform] ?: localTargetPath

    companion object {
        fun defaultKeystoreAlias(id: WorkspaceId): String =
            "dev.legendsayantan.backstage/ws/${id.value}"
    }
}

@Serializable
enum class LoadStrategy {
    PARALLEL,
    SEQUENTIAL,
    SPACE_AWARE
}

@Serializable
data class WorkspaceGroup(
    val id: GroupId,
    val displayName: String,
    val colorArgb: Long = 0xFF6366F1,
    val loadStrategy: LoadStrategy = LoadStrategy.SPACE_AWARE,
    val workspaceIds: List<WorkspaceId> = emptyList(),
    val createdAtEpochSeconds: Long,
)

enum class WorkspaceOperation {
    KEY_CHECK,
    PASSWORD_PROMPT,
    KEY_DERIVATION,
    VALIDATING,
    ARBITRATING,
    LOCKING,
    EVICTING,
    PURGING,
    EXTRACTING,
    ROTATING_KEY
}

sealed interface WorkspaceState {
    data object Vaulted : WorkspaceState
    data object Active : WorkspaceState
    data object StagedEncrypted : WorkspaceState
    data object Missing : WorkspaceState

    data class Busy(
        val operation: WorkspaceOperation,
        val progressFraction: Float,
        val bytesProcessed: Long = 0L,
        val totalBytes: Long = 0L,
        val statusMessage: String = "",
        val etaSeconds: Int? = null,
    ) : WorkspaceState

    data class Error(
        val error: BackStageError,
        val message: String,
        val recoverable: Boolean = true,
    ) : WorkspaceState
}
