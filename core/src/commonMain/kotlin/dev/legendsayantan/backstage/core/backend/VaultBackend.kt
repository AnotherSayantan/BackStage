package dev.legendsayantan.backstage.core.backend

import kotlinx.io.RawSink
import kotlinx.io.RawSource

/**
 * Uniform storage backend abstraction for cold-storage workspace vaults.
 */
interface VaultBackend {
    val displayName: String

    suspend fun exists(fileName: String): Boolean

    suspend fun sizeBytes(fileName: String): Long

    /**
     * Read up to [count] bytes starting at [offset].
     * Used for fast 182-byte TinyHeader reads during KEY_CHECK without downloading the entire vault.
     */
    suspend fun readBytes(fileName: String, offset: Long, count: Int): ByteArray

    suspend fun readAllBytes(fileName: String): ByteArray

    suspend fun writeAllBytes(fileName: String, bytes: ByteArray)

    suspend fun openReadStream(fileName: String): RawSource

    suspend fun openWriteStream(fileName: String): RawSink

    /**
     * Overwrite a specific byte range at [offset] in-place.
     * Used by PasswordChanger.changePassword() to update ARGON2_SALT, WRAPPED_KEY, and HEADER_MAC
     * in the first 182 bytes without touching the encrypted chunk payload.
     */
    suspend fun patchBytes(fileName: String, offset: Long, bytes: ByteArray)

    suspend fun delete(fileName: String)

    suspend fun availableSpaceBytes(): Long
}
