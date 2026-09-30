package dev.legendsayantan.backstage.core.crypto

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.engines.AESEngine
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.modes.GCMBlockCipher
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter

class GcmAuthFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * AEAD encryption/decryption primitives for AES-256-GCM and ChaCha20-Poly1305.
 *
 * Also implements the 60-byte WRAPPED_KEY envelope specification:
 *   bytes [0..11]  = 12-byte WRAP_NONCE
 *   bytes [12..43] = 32-byte encrypted vault_key
 *   bytes [44..59] = 16-byte GCM authentication tag
 */
object AeadCipher {
    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BYTES = 16
    const val WRAPPED_KEY_BYTES = NONCE_BYTES + KEY_BYTES + TAG_BYTES // 60 bytes

    fun encryptAesGcm(
        plaintext: ByteArray,
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray? = null,
    ): ByteArray {
        require(key.size == KEY_BYTES) { "AES-256-GCM key must be 32 bytes" }
        require(nonce.size == NONCE_BYTES) { "AES-256-GCM nonce must be 12 bytes" }

        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        val params = AEADParameters(KeyParameter(key), TAG_BYTES * 8, nonce, associatedData)
        cipher.init(true, params)

        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    fun decryptAesGcm(
        ciphertextWithTag: ByteArray,
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray? = null,
    ): ByteArray {
        require(key.size == KEY_BYTES) { "AES-256-GCM key must be 32 bytes" }
        require(nonce.size == NONCE_BYTES) { "AES-256-GCM nonce must be 12 bytes" }
        if (ciphertextWithTag.size < TAG_BYTES) {
            throw GcmAuthFailedException("Ciphertext shorter than GCM tag")
        }

        val cipher = GCMBlockCipher.newInstance(AESEngine.newInstance())
        val params = AEADParameters(KeyParameter(key), TAG_BYTES * 8, nonce, associatedData)
        cipher.init(false, params)

        val out = ByteArray(cipher.getOutputSize(ciphertextWithTag.size))
        return try {
            val len = cipher.processBytes(ciphertextWithTag, 0, ciphertextWithTag.size, out, 0)
            cipher.doFinal(out, len)
            out
        } catch (e: InvalidCipherTextException) {
            throw GcmAuthFailedException("AES-GCM authentication tag mismatch (wrong password or corrupted data)", e)
        }
    }

    fun encryptChaCha20Poly1305(
        plaintext: ByteArray,
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray? = null,
    ): ByteArray {
        require(key.size == KEY_BYTES) { "ChaCha20-Poly1305 key must be 32 bytes" }
        require(nonce.size == NONCE_BYTES) { "ChaCha20-Poly1305 nonce must be 12 bytes" }

        val cipher = ChaCha20Poly1305()
        val params = AEADParameters(KeyParameter(key), TAG_BYTES * 8, nonce, associatedData)
        cipher.init(true, params)

        val out = ByteArray(cipher.getOutputSize(plaintext.size))
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        cipher.doFinal(out, len)
        return out
    }

    fun decryptChaCha20Poly1305(
        ciphertextWithTag: ByteArray,
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray? = null,
    ): ByteArray {
        require(key.size == KEY_BYTES) { "ChaCha20-Poly1305 key must be 32 bytes" }
        require(nonce.size == NONCE_BYTES) { "ChaCha20-Poly1305 nonce must be 12 bytes" }
        if (ciphertextWithTag.size < TAG_BYTES) {
            throw GcmAuthFailedException("Ciphertext shorter than Poly1305 tag")
        }

        val cipher = ChaCha20Poly1305()
        val params = AEADParameters(KeyParameter(key), TAG_BYTES * 8, nonce, associatedData)
        cipher.init(false, params)

        val out = ByteArray(cipher.getOutputSize(ciphertextWithTag.size))
        return try {
            val len = cipher.processBytes(ciphertextWithTag, 0, ciphertextWithTag.size, out, 0)
            cipher.doFinal(out, len)
            out
        } catch (e: InvalidCipherTextException) {
            throw GcmAuthFailedException("ChaCha20-Poly1305 authentication tag mismatch", e)
        }
    }

    fun encryptChunk(
        cipherSuite: ResolvedCipher,
        plaintext: ByteArray,
        vaultKey: ByteArray,
        baseNonce12: ByteArray,
        chunkIndex: Int,
    ): ByteArray {
        val chunkNonce = deriveChunkNonce(baseNonce12, chunkIndex)
        return when (cipherSuite) {
            ResolvedCipher.AES_GCM -> encryptAesGcm(plaintext, vaultKey, chunkNonce)
            ResolvedCipher.CHACHA20_POLY1305 -> encryptChaCha20Poly1305(plaintext, vaultKey, chunkNonce)
        }
    }

    fun decryptChunk(
        cipherSuite: ResolvedCipher,
        ciphertextWithTag: ByteArray,
        vaultKey: ByteArray,
        baseNonce12: ByteArray,
        chunkIndex: Int,
    ): ByteArray {
        val chunkNonce = deriveChunkNonce(baseNonce12, chunkIndex)
        return when (cipherSuite) {
            ResolvedCipher.AES_GCM -> decryptAesGcm(ciphertextWithTag, vaultKey, chunkNonce)
            ResolvedCipher.CHACHA20_POLY1305 -> decryptChaCha20Poly1305(ciphertextWithTag, vaultKey, chunkNonce)
        }
    }

    /**
     * Wraps a 32-byte vault_key with a 32-byte wrap_key and a 12-byte wrapNonce into a 60-byte WRAPPED_KEY block.
     */
    fun wrapVaultKey(vaultKey: ByteArray, wrapKey: ByteArray, wrapNonce: ByteArray): ByteArray {
        require(vaultKey.size == KEY_BYTES) { "vaultKey must be 32 bytes" }
        require(wrapKey.size == KEY_BYTES) { "wrapKey must be 32 bytes" }
        require(wrapNonce.size == NONCE_BYTES) { "wrapNonce must be 12 bytes" }
        val ctAndTag = encryptAesGcm(vaultKey, wrapKey, wrapNonce)
        val wrapped = ByteArray(WRAPPED_KEY_BYTES)
        wrapNonce.copyInto(wrapped, destinationOffset = 0)
        ctAndTag.copyInto(wrapped, destinationOffset = NONCE_BYTES)
        return wrapped
    }

    /**
     * Unwraps a 60-byte WRAPPED_KEY block using a 32-byte wrap_key.
     * Throws [GcmAuthFailedException] if the password (wrap_key) is wrong.
     */
    fun unwrapVaultKey(wrappedKey60Bytes: ByteArray, wrapKey: ByteArray): ByteArray {
        require(wrappedKey60Bytes.size == WRAPPED_KEY_BYTES) {
            "wrappedKey must be $WRAPPED_KEY_BYTES bytes (got ${wrappedKey60Bytes.size})"
        }
        val wrapNonce = wrappedKey60Bytes.copyOfRange(0, NONCE_BYTES)
        val ctAndTag = wrappedKey60Bytes.copyOfRange(NONCE_BYTES, WRAPPED_KEY_BYTES)
        return decryptAesGcm(ctAndTag, wrapKey, wrapNonce)
    }

    /**
     * XORs the 32-bit chunkIndex into the last 4 bytes of the 12-byte DATA_NONCE (TLS 1.3 record nonce construction).
     * Maximum safe chunks per vault is Int.MAX_VALUE (~2.14 billion chunks, or ~8.5 PB at 4 MiB chunk size).
     */
    fun deriveChunkNonce(baseNonce12: ByteArray, chunkIndex: Int): ByteArray {
        require(baseNonce12.size == NONCE_BYTES) { "baseNonce must be 12 bytes" }
        require(chunkIndex >= 0) { "chunkIndex overflow: must be >= 0 (got $chunkIndex)" }
        val derived = baseNonce12.copyOf()
        derived[8] = (derived[8].toInt() xor ((chunkIndex ushr 24) and 0xFF)).toByte()
        derived[9] = (derived[9].toInt() xor ((chunkIndex ushr 16) and 0xFF)).toByte()
        derived[10] = (derived[10].toInt() xor ((chunkIndex ushr 8) and 0xFF)).toByte()
        derived[11] = (derived[11].toInt() xor (chunkIndex and 0xFF)).toByte()
        return derived
    }
}
