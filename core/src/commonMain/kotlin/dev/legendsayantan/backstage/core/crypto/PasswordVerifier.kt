package dev.legendsayantan.backstage.core.crypto

import dev.legendsayantan.backstage.core.backend.VaultBackend
import dev.legendsayantan.backstage.core.platform.SecureRandomProvider
import dev.legendsayantan.backstage.core.workspace.WorkspaceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Evaluates whether a workspace's 32-byte vault_key is already available in this device's
 * OS keystore (and still valid against the vault's BLAKE3 HEADER_MAC), or whether the user
 * must enter their custom password to unwrap vault_key.
 */
class PasswordVerifier(
    private val keyProvider: KeyProvider,
) {
    data class KeyEnvelope(
        val argon2Salt: ByteArray,
        val wrappedKey: ByteArray,
        val headerMac: ByteArray,
        val macCoverBytes: ByteArray,
        val hintFromSidecar: String? = null,
        val argon2MCost: Int = Argon2Config.M_COST,
        val argon2TCost: Int = Argon2Config.T_COST,
        val argon2Parallelism: Int = Argon2Config.PARALLELISM,
    )

    /**
     * Creates a brand-new 32-byte vault_key and wraps it using the user's custom password.
     * If [workspace.keyMode] is [WorkspaceKeyMode.PasswordBacked], stores the vault_key in the OS keystore.
     */
    suspend fun initializeWorkspaceKey(
        workspace: WorkspaceConfig,
        password: String,
    ): InitializedKeyMaterial = withContext(Dispatchers.Default) {
        val vaultKey = SecureRandomProvider.nextBytes(AeadCipher.KEY_BYTES)
        val salt = SecureRandomProvider.nextBytes(Argon2Config.SALT_LENGTH)
        val wrapNonce = SecureRandomProvider.nextBytes(AeadCipher.NONCE_BYTES)
        val wrapKey = Argon2Kdf.derive(password = password, salt = salt)
        val wrappedKey = try {
            AeadCipher.wrapVaultKey(vaultKey = vaultKey, wrapKey = wrapKey, wrapNonce = wrapNonce)
        } finally {
            wrapKey.fill(0)
        }

        val keyMode = workspace.keyMode
        if (keyMode is WorkspaceKeyMode.PasswordBacked) {
            withContext(Dispatchers.IO) {
                keyProvider.store(keyMode.keystoreAlias, vaultKey)
            }
        }

        InitializedKeyMaterial(
            vaultKey = vaultKey,
            argon2Salt = salt,
            wrappedKey = wrappedKey,
        )
    }

    /**
     * Reads only the 182-byte TinyHeader (or .bsmeta sidecar for Portable .7z vaults)
     * and checks if this device already holds a valid vault_key in its OS keystore.
     */
    suspend fun assess(
        workspace: WorkspaceConfig,
        backend: VaultBackend,
    ): VaultKeyState = withContext(Dispatchers.IO) {
        if (workspace.vaultFormat == VaultFormatConfig.PlainCompressed) {
            return@withContext VaultKeyState.Ready(ByteArray(0))
        }

        val keyMode = workspace.keyMode
            ?: error("Encrypted workspace '${workspace.displayName}' must have a non-null keyMode")

        val envelope = readKeyEnvelope(workspace, backend)
        val configuredHint = (keyMode as? WorkspaceKeyMode.PasswordBacked)?.passwordHint
            ?: envelope.hintFromSidecar

        if (keyMode is WorkspaceKeyMode.ManualOnEveryUse) {
            return@withContext VaultKeyState.NeedsPassword(
                reason = PasswordReason.ALWAYS_REQUIRED,
                hint = configuredHint,
                argon2Salt = envelope.argon2Salt,
                wrappedKey = envelope.wrappedKey,
                argon2MCost = envelope.argon2MCost,
                argon2TCost = envelope.argon2TCost,
                argon2Parallelism = envelope.argon2Parallelism,
            )
        }

        keyMode as WorkspaceKeyMode.PasswordBacked
        val storedVaultKey = keyProvider.retrieveOrNull(keyMode.keystoreAlias)
            ?: return@withContext VaultKeyState.NeedsPassword(
                reason = PasswordReason.NEW_DEVICE,
                hint = configuredHint,
                argon2Salt = envelope.argon2Salt,
                wrappedKey = envelope.wrappedKey,
                argon2MCost = envelope.argon2MCost,
                argon2TCost = envelope.argon2TCost,
                argon2Parallelism = envelope.argon2Parallelism,
            )

        // Verify BLAKE3 keyed HEADER_MAC to detect if another device rotated the vault key
        val expectedMac = Blake3.keyedHash(envelope.macCoverBytes, storedVaultKey)
        if (!expectedMac.contentEquals(envelope.headerMac)) {
            storedVaultKey.fill(0)
            keyProvider.delete(keyMode.keystoreAlias)
            return@withContext VaultKeyState.NeedsPassword(
                reason = PasswordReason.KEY_ROTATED,
                hint = configuredHint,
                argon2Salt = envelope.argon2Salt,
                wrappedKey = envelope.wrappedKey,
                argon2MCost = envelope.argon2MCost,
                argon2TCost = envelope.argon2TCost,
                argon2Parallelism = envelope.argon2Parallelism,
            )
        }

        VaultKeyState.Ready(storedVaultKey)
    }

    /**
     * Verifies a user-supplied password against [needsPassword] using the vault's stored Argon2id parameters.
     * Returns the unwrapped 32-byte vault_key if the password is right, or null if wrong.
     */
    suspend fun verify(
        password: String,
        needsPassword: VaultKeyState.NeedsPassword,
    ): ByteArray? = withContext(Dispatchers.Default) {
        val wrapKey = Argon2Kdf.derive(
            password = password,
            salt = needsPassword.argon2Salt,
            mCost = needsPassword.argon2MCost,
            tCost = needsPassword.argon2TCost,
            parallelism = needsPassword.argon2Parallelism,
        )
        try {
            AeadCipher.unwrapVaultKey(
                wrappedKey60Bytes = needsPassword.wrappedKey,
                wrapKey = wrapKey,
            )
        } catch (_: GcmAuthFailedException) {
            null
        } finally {
            wrapKey.fill(0)
        }
    }

    /**
     * Persists a verified [vaultKey] into the OS keystore for this device so the user
     * is never prompted again on this device.
     */
    suspend fun saveToKeystore(keystoreAlias: String, vaultKey: ByteArray) =
        withContext(Dispatchers.IO) {
            keyProvider.store(keystoreAlias, vaultKey)
        }

    internal suspend fun readKeyEnvelope(
        workspace: WorkspaceConfig,
        backend: VaultBackend,
    ): KeyEnvelope {
        val sidecarName = BsmetaSidecar.sidecarFileName(workspace.vaultFileName)
        val sidecar = if (backend.exists(sidecarName)) {
            runCatching {
                BsmetaSidecar.decode(backend.readAllBytes(sidecarName).decodeToString())
            }.getOrNull()
        } else {
            null
        }

        return when (workspace.vaultFormat) {
            VaultFormatConfig.Native -> {
                if (!backend.exists(workspace.vaultFileName) && sidecar?.kdf?.wrappedKeyHex != null) {
                    val kdf = sidecar.kdf
                    val salt = kdf.saltHex.hexToByteArray()
                    val wrapped = kdf.wrappedKeyHex.hexToByteArray()
                    val macCover = salt + wrapped
                    val headerMac = sidecar.headerMacHex?.hexToByteArray() ?: ByteArray(32)
                    KeyEnvelope(
                        argon2Salt = salt,
                        wrappedKey = wrapped,
                        headerMac = headerMac,
                        macCoverBytes = macCover,
                        hintFromSidecar = sidecar.passwordHint,
                        argon2MCost = kdf.mCost,
                        argon2TCost = kdf.tCost,
                        argon2Parallelism = kdf.parallelism,
                    )
                } else {
                    val tinyBytes = backend.readBytes(
                        fileName = workspace.vaultFileName,
                        offset = 0L,
                        count = TinyHeader.TINY_HEADER_BYTES,
                    )
                    val tiny = TinyHeader.parse(tinyBytes)
                    KeyEnvelope(
                        argon2Salt = tiny.argon2Salt,
                        wrappedKey = tiny.wrappedKey,
                        headerMac = tiny.headerMac,
                        macCoverBytes = tiny.macCoverBytes,
                        hintFromSidecar = sidecar?.passwordHint,
                        argon2MCost = tiny.argon2MCost,
                        argon2TCost = tiny.argon2TCost,
                        argon2Parallelism = tiny.argon2Parallelism,
                    )
                }
            }

            VaultFormatConfig.Portable -> {
                val kdf = sidecar?.kdf
                    ?: error("Portable .7z vault '${workspace.vaultFileName}' is missing KDF metadata in $sidecarName")
                val salt = kdf.saltHex.hexToByteArray()
                val wrapped = kdf.wrappedKeyHex?.hexToByteArray()
                    ?: error("Portable .7z vault sidecar '$sidecarName' is missing wrapped_key_hex")
                val macCover = salt + wrapped
                val headerMac = sidecar.headerMacHex?.hexToByteArray()
                    ?: error("Portable .7z vault sidecar '$sidecarName' is missing header_mac_hex")
                KeyEnvelope(
                    argon2Salt = salt,
                    wrappedKey = wrapped,
                    headerMac = headerMac,
                    macCoverBytes = macCover,
                    hintFromSidecar = sidecar.passwordHint,
                    argon2MCost = kdf.mCost,
                    argon2TCost = kdf.tCost,
                    argon2Parallelism = kdf.parallelism,
                )
            }

            VaultFormatConfig.PlainCompressed -> error("PlainCompressed vaults do not have a KeyEnvelope")
        }
    }
}

data class InitializedKeyMaterial(
    val vaultKey: ByteArray,
    val argon2Salt: ByteArray,
    val wrappedKey: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is InitializedKeyMaterial &&
            vaultKey.contentEquals(other.vaultKey) &&
            argon2Salt.contentEquals(other.argon2Salt) &&
            wrappedKey.contentEquals(other.wrappedKey)

    override fun hashCode(): Int {
        var result = vaultKey.contentHashCode()
        result = 31 * result + argon2Salt.contentHashCode()
        result = 31 * result + wrappedKey.contentHashCode()
        return result
    }
}
