package dev.legendsayantan.backstage.core.crypto

import org.bouncycastle.crypto.digests.Blake3Digest
import org.bouncycastle.crypto.params.Blake3Parameters

/**
 * BLAKE3 cryptographic hash and 256-bit keyed MAC engine.
 * Used for:
 *  1. Unkeyed 32-byte chunk integrity digests in CHUNK_MANIFEST and FILE_ATTR_TABLE
 *  2. Keyed 32-byte HEADER_MAC over the fixed 150-byte .bstage header (key = vault_key)
 */
object Blake3 {
    const val DIGEST_LENGTH = 32

    fun hash(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
        val digest = Blake3Digest(DIGEST_LENGTH * 8)
        digest.update(data, offset, length)
        val out = ByteArray(DIGEST_LENGTH)
        digest.doFinal(out, 0)
        return out
    }

    fun keyedHash(data: ByteArray, key32Bytes: ByteArray): ByteArray {
        require(key32Bytes.size == 32) {
            "BLAKE3 keyedHash requires a 32-byte key (got ${key32Bytes.size} bytes)"
        }
        val digest = Blake3Digest(DIGEST_LENGTH * 8)
        digest.init(Blake3Parameters.key(key32Bytes))
        digest.update(data, 0, data.size)
        val out = ByteArray(DIGEST_LENGTH)
        digest.doFinal(out, 0)
        return out
    }

    fun hashFile(file: java.io.File): ByteArray = hashFileWithProgress(file)

    fun hashFileWithProgress(
        file: java.io.File,
        bufferSizeBytes: Int = 1024 * 1024,
        onProgress: ((Float, Long, Long) -> Unit)? = null,
    ): ByteArray {
        val totalBytes = file.length().coerceAtLeast(1L)
        val hasher = StreamingHasher()
        java.io.FileInputStream(file).buffered(bufferSizeBytes).use { fis ->
            val buf = ByteArray(bufferSizeBytes)
            var bytesReadSoFar = 0L
            while (true) {
                val read = fis.read(buf)
                if (read <= 0) break
                hasher.update(buf, 0, read)
                bytesReadSoFar += read
                val frac = (bytesReadSoFar.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                onProgress?.invoke(frac, bytesReadSoFar, totalBytes)
            }
        }
        return hasher.finish()
    }


    class StreamingHasher {
        private val digest = Blake3Digest(DIGEST_LENGTH * 8)

        fun update(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
            if (length > 0) {
                digest.update(bytes, offset, length)
            }
        }

        fun finish(): ByteArray {
            val out = ByteArray(DIGEST_LENGTH)
            digest.doFinal(out, 0)
            return out
        }
    }
}
