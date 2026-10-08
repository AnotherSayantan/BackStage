package dev.legendsayantan.backstage.core.crypto

import kotlinx.serialization.Serializable

/**
 * Defines how an encrypted workspace's vault_key is managed on the local device.
 * Null on WorkspaceConfig when vaultFormat == PlainCompressed.
 */
@Serializable
sealed interface WorkspaceKeyMode {

    /**
     * User sets a custom password once. The unwrapped 32-byte vault_key is stored
     * in this device's OS keystore under [keystoreAlias] so it is never re-typed
     * on the same device. On a new device (or after key rotation), the user is
     * prompted for the password, which is verified via AES-GCM key unwrapping.
     */
    @Serializable
    data class PasswordBacked(
        val keystoreAlias: String,
        val passwordHint: String? = null,
    ) : WorkspaceKeyMode

    /**
     * Never stores vault_key in the OS keystore; prompts for the workspace password
     * on every load/evict operation (air-gapped / untrusted host mode).
     */
    @Serializable
    data object ManualOnEveryUse : WorkspaceKeyMode
}

enum class PasswordReason {
    /** Initial encryption password required when creating a new vault for a workspace. */
    INITIAL_CREATION,
    /** Workspace is known or being imported on a device that does not yet have its vault_key in OS keystore. */
    NEW_DEVICE,
    /** Stored vault_key failed BLAKE3 HEADER_MAC check because the vault key was rotated on another device. */
    KEY_ROTATED,
    /** Workspace is configured with ManualOnEveryUse mode. */
    ALWAYS_REQUIRED,
}

sealed interface VaultKeyState {
    /**
     * Either vault_key was retrieved from the OS keystore and verified against HEADER_MAC,
     * or the workspace uses PlainCompressed format (empty key).
     */
    data class Ready(val vaultKey: ByteArray) : VaultKeyState {
        override fun equals(other: Any?): Boolean =
            other is Ready && vaultKey.contentEquals(other.vaultKey)

        override fun hashCode(): Int = vaultKey.contentHashCode()
    }

    /**
     * User must input the custom workspace password to unwrap vault_key from [wrappedKey].
     */
    data class NeedsPassword(
        val reason: PasswordReason,
        val hint: String?,
        val argon2Salt: ByteArray,
        val wrappedKey: ByteArray,
        val argon2MCost: Int = Argon2Config.M_COST,
        val argon2TCost: Int = Argon2Config.T_COST,
        val argon2Parallelism: Int = Argon2Config.PARALLELISM,
    ) : VaultKeyState {
        override fun equals(other: Any?): Boolean =
            other is NeedsPassword &&
                reason == other.reason &&
                hint == other.hint &&
                argon2Salt.contentEquals(other.argon2Salt) &&
                wrappedKey.contentEquals(other.wrappedKey) &&
                argon2MCost == other.argon2MCost &&
                argon2TCost == other.argon2TCost &&
                argon2Parallelism == other.argon2Parallelism

        override fun hashCode(): Int {
            var result = reason.hashCode()
            result = 31 * result + (hint?.hashCode() ?: 0)
            result = 31 * result + argon2Salt.contentHashCode()
            result = 31 * result + wrappedKey.contentHashCode()
            result = 31 * result + argon2MCost
            result = 31 * result + argon2TCost
            result = 31 * result + argon2Parallelism
            return result
        }
    }
}
