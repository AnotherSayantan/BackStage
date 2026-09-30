package dev.legendsayantan.backstage.core.workspace

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class WorkspaceId(val value: String) {
    init {
        require(value.isNotBlank()) { "WorkspaceId must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        /** Maximum UTF-8 byte length that fits in the TinyHeader 26-byte WORKSPACE_ID field. */
        const val MAX_BYTE_LENGTH = 26

        fun generate(randomBytes: ByteArray): WorkspaceId {
            val chars = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
            val suffix = buildString(20) {
                for (i in 0 until minOf(20, randomBytes.size)) {
                    append(chars[(randomBytes[i].toInt() and 0xFF) % chars.length])
                }
            }
            return WorkspaceId("ws_$suffix")
        }

        /**
         * Normalises an arbitrary string (e.g. from an imported .bsmeta or vault filename)
         * into a safe WorkspaceId that fits the TinyHeader 26-byte WORKSPACE_ID field.
         * Strips non-ASCII characters and truncates so the UTF-8 encoding is ≤ 26 bytes.
         */
        fun fromArbitraryString(raw: String): WorkspaceId {
            // Keep only printable ASCII, replacing others with '_'
            val ascii = raw.map { c -> if (c.code in 32..126) c else '_' }.joinToString("")
            // Truncate to MAX_BYTE_LENGTH bytes using UTF-8 encoding
            val bytes = ascii.encodeToByteArray()
            val truncated = if (bytes.size <= MAX_BYTE_LENGTH) {
                ascii
            } else {
                bytes.decodeToString(0, MAX_BYTE_LENGTH, throwOnInvalidSequence = false).trimEnd('\u0000')
            }
            return WorkspaceId(truncated.ifBlank { generate(raw.encodeToByteArray().copyOf(20)).value })
        }
    }
}

@Serializable
@JvmInline
value class GroupId(val value: String) {
    init {
        require(value.isNotBlank()) { "GroupId must not be blank" }
    }

    override fun toString(): String = value

    companion object {
        fun generate(randomBytes: ByteArray): GroupId {
            val chars = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
            val suffix = buildString(16) {
                for (i in 0 until minOf(16, randomBytes.size)) {
                    append(chars[(randomBytes[i].toInt() and 0xFF) % chars.length])
                }
            }
            return GroupId("grp_$suffix")
        }
    }
}
