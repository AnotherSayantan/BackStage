package dev.legendsayantan.backstage.core.crypto

import dev.legendsayantan.backstage.core.backend.VaultBackend
import dev.legendsayantan.backstage.core.error.BackStageError
import dev.legendsayantan.backstage.core.error.BackStageException
import dev.legendsayantan.backstage.core.platform.SecureRandomProvider
import dev.legendsayantan.backstage.core.workspace.WorkspaceConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles both:
 *  1. Fast Password Change (`changePassword`) — re-wraps the existing 32-byte `vault_key` with a
 *     new Argon2id-derived `wrap_key` and patches only the 182-byte TinyHeader (and .bsmeta sidecar).
 *     Takes ~1 second regardless of workspace size.
 *  2. Full Vault Key Rotation (`rotateVaultKey`) — generates a brand-new 32-byte `vault_key`,
 *     re-encrypts every chunk in the `.bstage` archive, updates `HEADER_MAC`, and updates this
 *     device's OS keystore. All other devices' cached `vault_key`s become stale and will require
 *     the new password on next access.
 */
class PasswordChanger(
    private val keyProvider: KeyProvider,
    private val stagingDir: java.io.File? = null,
) {
    /**
     * Purges any staged prefetch copy (and its MAC file) for [workspace] since
     * changing or rotating the password invalidates the staged vault header.
     */
    private fun purgeStaged(workspace: dev.legendsayantan.backstage.core.workspace.WorkspaceConfig) {
        val dir = stagingDir ?: return
        val staged = java.io.File(dir, workspace.vaultFileName)
        val stagedMac = java.io.File(dir, "${workspace.vaultFileName}.bsstage_mac")
        val stagedSidecar = java.io.File(dir, BsmetaSidecar.sidecarFileName(workspace.vaultFileName))
        staged.delete()
        stagedMac.delete()
        stagedSidecar.delete()
    }
    suspend fun changePassword(
        workspace: WorkspaceConfig,
        newPassword: String,
        newPasswordHint: String? = null,
        backend: VaultBackend,
        currentVaultKeyOverride: ByteArray? = null,
    ) = withContext(Dispatchers.Default) {
        require(workspace.vaultFormat.isEncrypted) {
            "Cannot change password on an unencrypted workspace (${workspace.vaultFormat})"
        }

        val vaultKey = currentVaultKeyOverride ?: run {
            val pb = workspace.keyMode as? WorkspaceKeyMode.PasswordBacked
                ?: throw BackStageException(BackStageError.KeyNotInKeystore("ManualOnEveryUse requires currentVaultKeyOverride"))
            withContext(Dispatchers.IO) {
                keyProvider.retrieveOrNull(pb.keystoreAlias)
                    ?: throw BackStageException(BackStageError.KeyNotInKeystore(pb.keystoreAlias))
            }
        }

        try {
            val newSalt = SecureRandomProvider.nextBytes(Argon2Config.SALT_LENGTH)
            val newWrapNonce = SecureRandomProvider.nextBytes(AeadCipher.NONCE_BYTES)
            val newWrapKey = Argon2Kdf.derive(password = newPassword, salt = newSalt)
            val newWrappedKey = try {
                AeadCipher.wrapVaultKey(vaultKey = vaultKey, wrapKey = newWrapKey, wrapNonce = newWrapNonce)
            } finally {
                newWrapKey.fill(0)
            }

            withContext(Dispatchers.IO) {
                when (workspace.vaultFormat) {
                    VaultFormatConfig.Native -> {
                        val currentTinyBytes = backend.readBytes(
                            fileName = workspace.vaultFileName,
                            offset = 0L,
                            count = TinyHeader.TINY_HEADER_BYTES,
                        )
                        val fixedHeader = currentTinyBytes.copyOfRange(0, TinyHeader.FIXED_HEADER_BYTES)

                        // Patch ARGON2_SALT [19..34] and WRAPPED_KEY [35..94]
                        newSalt.copyInto(fixedHeader, destinationOffset = TinyHeader.OFFSET_ARGON2_SALT)
                        newWrappedKey.copyInto(fixedHeader, destinationOffset = TinyHeader.OFFSET_WRAPPED_KEY)

                        val newMac = Blake3.keyedHash(fixedHeader, vaultKey)
                        val updatedTiny = fixedHeader + newMac

                        backend.patchBytes(
                            fileName = workspace.vaultFileName,
                            offset = 0L,
                            bytes = updatedTiny,
                        )
                        updateSidecarIfPresent(
                            workspace = workspace,
                            backend = backend,
                            newSalt = newSalt,
                            newWrappedKey = newWrappedKey,
                            newHeaderMac = newMac,
                            newHint = newPasswordHint,
                        )
                    }

                    VaultFormatConfig.Portable -> {
                        val macCover = newSalt + newWrappedKey
                        val newMac = Blake3.keyedHash(macCover, vaultKey)
                        updateSidecarIfPresent(
                            workspace = workspace,
                            backend = backend,
                            newSalt = newSalt,
                            newWrappedKey = newWrappedKey,
                            newHeaderMac = newMac,
                            newHint = newPasswordHint,
                        )
                    }

                    VaultFormatConfig.PlainCompressed, VaultFormatConfig.PlainSevenZip -> Unit
                }
            }
        } finally {
            purgeStaged(workspace)
            if (currentVaultKeyOverride == null) {
                vaultKey.fill(0)
            }
        }
    }

    /**
     * Full vault key rotation: generates a new random 32-byte `vault_key`, streams and re-encrypts all
     * data chunks in a Native `.bstage` vault without loading the entire archive into RAM, updates
     * the header & manifest, and updates this device's OS keystore. Other devices' stored `vault_key`s
     * will fail `HEADER_MAC` on next load and prompt for [newPassword].
     */
    suspend fun rotateVaultKey(
        workspace: WorkspaceConfig,
        newPassword: String,
        newPasswordHint: String? = null,
        backend: VaultBackend,
        currentVaultKeyOverride: ByteArray? = null,
        onProgress: (Float) -> Unit = {},
    ): ByteArray = withContext(Dispatchers.Default) {
        require(workspace.vaultFormat == VaultFormatConfig.Native) {
            "In-place chunk key rotation is supported for Native (.bstage) vaults"
        }

        val oldVaultKey = currentVaultKeyOverride ?: run {
            val pb = workspace.keyMode as? WorkspaceKeyMode.PasswordBacked
                ?: throw BackStageException(BackStageError.KeyNotInKeystore("ManualOnEveryUse requires currentVaultKeyOverride"))
            withContext(Dispatchers.IO) {
                keyProvider.retrieveOrNull(pb.keystoreAlias)
                    ?: throw BackStageException(BackStageError.KeyNotInKeystore(pb.keystoreAlias))
            }
        }

        val tempPayloadFile = withContext(Dispatchers.IO) {
            java.io.File.createTempFile("backstage_rotate_", ".chunks")
        }

        try {
            val vaultFileSize = withContext(Dispatchers.IO) {
                backend.sizeBytes(workspace.vaultFileName)
            }
            val parsedHeader = withContext(Dispatchers.IO) {
                FullVaultHeader.readFromBackend(backend, workspace.vaultFileName)
            }
            val oldTiny = parsedHeader.tinyHeader

            // Verify old key against HEADER_MAC first
            val expectedOldMac = Blake3.keyedHash(oldTiny.macCoverBytes, oldVaultKey)
            if (!expectedOldMac.contentEquals(oldTiny.headerMac)) {
                throw BackStageException(BackStageError.VaultKeyStale)
            }

            val newVaultKey = SecureRandomProvider.nextBytes(AeadCipher.KEY_BYTES)
            val newSalt = SecureRandomProvider.nextBytes(Argon2Config.SALT_LENGTH)
            val newWrapNonce = SecureRandomProvider.nextBytes(AeadCipher.NONCE_BYTES)
            val newDataNonce = SecureRandomProvider.nextBytes(AeadCipher.NONCE_BYTES)
            val newWrapKey = Argon2Kdf.derive(password = newPassword, salt = newSalt)
            val newWrappedKey = try {
                AeadCipher.wrapVaultKey(vaultKey = newVaultKey, wrapKey = newWrapKey, wrapNonce = newWrapNonce)
            } finally {
                newWrapKey.fill(0)
            }

            val updatedManifest = ArrayList<ChunkManifestEntry>(parsedHeader.chunkManifest.size)
            var runningPayloadOffset = 0L
            val totalChunks = parsedHeader.chunkManifest.size.coerceAtLeast(1)

            withContext(Dispatchers.IO) {
                java.io.FileOutputStream(tempPayloadFile).buffered().use { tempOut ->
                    for ((index, entry) in parsedHeader.chunkManifest.withIndex()) {
                        val start = parsedHeader.payloadStartOffset + entry.payloadOffset
                        val end = start + entry.compressedEncryptedLength
                        if (start < 0L || end > vaultFileSize) {
                            throw BackStageException(BackStageError.ChunkCorrupted(index, refetchable = false))
                        }
                        val oldChunkBytes = backend.readBytes(
                            fileName = workspace.vaultFileName,
                            offset = start,
                            count = entry.compressedEncryptedLength,
                        )

                        // Verify BLAKE3 chunk integrity before decrypting
                        val actualChunkHash = Blake3.hash(oldChunkBytes)
                        if (!actualChunkHash.contentEquals(entry.blake3Hash)) {
                            throw BackStageException(BackStageError.ChunkCorrupted(index, refetchable = false))
                        }

                        val compressedPlaintext = AeadCipher.decryptChunk(
                            cipherSuite = oldTiny.cipherSuite,
                            ciphertextWithTag = oldChunkBytes,
                            vaultKey = oldVaultKey,
                            baseNonce12 = oldTiny.dataNonce,
                            chunkIndex = index,
                        )

                        val newChunkCiphertext = try {
                            AeadCipher.encryptChunk(
                                cipherSuite = oldTiny.cipherSuite,
                                plaintext = compressedPlaintext,
                                vaultKey = newVaultKey,
                                baseNonce12 = newDataNonce,
                                chunkIndex = index,
                            )
                        } finally {
                            compressedPlaintext.fill(0)
                            oldChunkBytes.fill(0)
                        }

                        val newChunkHash = Blake3.hash(newChunkCiphertext)
                        tempOut.write(newChunkCiphertext)
                        updatedManifest.add(
                            ChunkManifestEntry(
                                chunkIndex = index,
                                blake3Hash = newChunkHash,
                                payloadOffset = runningPayloadOffset,
                                compressedEncryptedLength = newChunkCiphertext.size,
                                uncompressedLength = entry.uncompressedLength,
                            )
                        )
                        runningPayloadOffset += newChunkCiphertext.size
                        onProgress((index + 1).toFloat() / totalChunks.toFloat())
                    }
                }
            }

            val variableTablesBytes = FullVaultHeader.serializeVariableTables(
                fileAttributes = parsedHeader.fileAttributes,
                chunkManifest = updatedManifest,
            )
            val newTinyBytes = TinyHeader.encodeTinyHeader(
                isEncrypted = true,
                cipherSuite = oldTiny.cipherSuite,
                argon2Salt = newSalt,
                wrappedKey = newWrappedKey,
                dataNonce = newDataNonce,
                chunkSize = oldTiny.chunkSize,
                chunkCount = updatedManifest.size,
                workspaceId = oldTiny.workspaceId,
                platformOrigin = oldTiny.platformOrigin,
                createdAtEpochSeconds = oldTiny.createdAtEpochSeconds,
                vaultKey = newVaultKey,
            )

            withContext(Dispatchers.IO) {
                backend.openWriteStream(workspace.vaultFileName).use { outSink ->
                    val sink = kotlinx.io.Buffer()
                    sink.write(newTinyBytes)
                    sink.write(variableTablesBytes)
                    outSink.write(sink, sink.size)
                    java.io.FileInputStream(tempPayloadFile).use { fis ->
                        val copyBuf = ByteArray(65_536)
                        while (true) {
                            val read = fis.read(copyBuf)
                            if (read <= 0) break
                            sink.write(copyBuf, 0, read)
                            outSink.write(sink, sink.size)
                        }
                    }
                    outSink.flush()
                }

                val newMac = newTinyBytes.copyOfRange(TinyHeader.FIXED_HEADER_BYTES, TinyHeader.TINY_HEADER_BYTES)
                updateSidecarIfPresent(
                    workspace = workspace,
                    backend = backend,
                    newSalt = newSalt,
                    newWrappedKey = newWrappedKey,
                    newHeaderMac = newMac,
                    newHint = newPasswordHint,
                )

                if (workspace.keyMode is WorkspaceKeyMode.PasswordBacked) {
                    keyProvider.store(workspace.keyMode.keystoreAlias, newVaultKey)
                }
            }

            newVaultKey
        } finally {
            purgeStaged(workspace)
            oldVaultKey.fill(0)
            tempPayloadFile.delete()
        }
    }

    private suspend fun updateSidecarIfPresent(
        workspace: WorkspaceConfig,
        backend: VaultBackend,
        newSalt: ByteArray,
        newWrappedKey: ByteArray,
        newHeaderMac: ByteArray,
        newHint: String?,
    ) {
        val sidecarName = BsmetaSidecar.sidecarFileName(workspace.vaultFileName)
        val existing = if (backend.exists(sidecarName)) {
            runCatching {
                BsmetaSidecar.decode(backend.readAllBytes(sidecarName).decodeToString())
            }.getOrNull()
        } else {
            null
        }

        val updated = (existing ?: BsmetaSidecar(
            workspaceId = workspace.id.value,
            displayName = workspace.displayName,
            platformOrigin = workspace.platformOrigin,
            vaultFormat = workspace.vaultFormat.fileExtension,
            archiveFilename = workspace.vaultFileName,
        )).copy(
            passwordHint = newHint ?: existing?.passwordHint ?: (workspace.keyMode as? WorkspaceKeyMode.PasswordBacked)?.passwordHint,
            headerMacHex = newHeaderMac.toHexString(),
            kdf = BsmetaKdfInfo(
                saltHex = newSalt.toHexString(),
                wrappedKeyHex = newWrappedKey.toHexString(),
            ),
        )
        backend.writeAllBytes(sidecarName, BsmetaSidecar.encode(updated).encodeToByteArray())
    }
}
