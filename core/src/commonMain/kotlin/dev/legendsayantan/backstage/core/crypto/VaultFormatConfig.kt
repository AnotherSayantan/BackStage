package dev.legendsayantan.backstage.core.crypto

import kotlinx.serialization.Serializable

/**
 * Global Argon2id parameters (OWASP Interactive Auth compliant).
 * Single hardcoded preset to eliminate user configuration complexity.
 */
object Argon2Config {
    const val M_COST: Int = 65536        // 64 MB RAM
    const val T_COST: Int = 3            // 3 iterations
    const val PARALLELISM: Int = 4       // 4 lanes
    const val SALT_LENGTH: Int = 16      // 16 bytes per-vault random salt
    const val OUTPUT_LENGTH: Int = 32    // 32-byte (256-bit) derived wrap_key
}

@Serializable
enum class ResolvedCipher(val idByte: Byte) {
    AES_GCM(0),
    CHACHA20_POLY1305(1);

    companion object {
        fun fromIdByte(id: Byte): ResolvedCipher = when (id.toInt()) {
            0 -> AES_GCM
            1 -> CHACHA20_POLY1305
            else -> throw IllegalArgumentException("Unknown cipher ID byte: $id")
        }
    }
}

/**
 * Three-tier vault format configuration per workspace.
 * Note: Device credential authentication (Layer 1) always fires regardless of vault format.
 */
@Serializable
sealed interface VaultFormatConfig {
    val fileExtension: String
    val isEncrypted: Boolean

    /**
     * Streaming AES-256-GCM or ChaCha20-Poly1305 + Zstd compression + BLAKE3 chunk manifest.
     * Extension: .bstage
     */
    @Serializable
    data object Native : VaultFormatConfig {
        override val fileExtension: String = "bstage"
        override val isEncrypted: Boolean = true
    }

    /**
     * Standard 7-Zip AES-256 archive with Argon2id pre-derived hex password and .bsmeta sidecar.
     * Openable without BackStage using 7-Zip + documented Argon2id recovery steps.
     * Extension: .7z
     */
    @Serializable
    data object Portable : VaultFormatConfig {
        override val fileExtension: String = "7z"
        override val isEncrypted: Boolean = true
    }

    /**
     * Unencrypted Zstd-compressed archive with BLAKE3 integrity manifest.
     * Extension: .bvault
     */
    @Serializable
    data object PlainCompressed : VaultFormatConfig {
        override val fileExtension: String = "bvault"
        override val isEncrypted: Boolean = false
    }
}
