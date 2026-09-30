package dev.legendsayantan.backstage.core.crypto

import dev.legendsayantan.backstage.core.error.BackStageError
import dev.legendsayantan.backstage.core.error.BackStageException
import dev.legendsayantan.backstage.core.platform.Platform
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.Serializable

@Serializable
enum class ArchiveFileType(val code: Byte) {
    REGULAR(0),
    DIRECTORY(1),
    SYMLINK(2);

    companion object {
        fun fromCode(code: Byte): ArchiveFileType = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("Unknown ArchiveFileType code: $code")
    }
}

@Serializable
data class FileAttrEntry(
    val relativePath: String,
    val fileType: ArchiveFileType,
    val symlinkTarget: String = "",
    val unixMode: Int = 0,
    val isExecutable: Boolean = false,
    val modifiedAtEpochMillis: Long,
    val uncompressedSize: Long = 0L,
    val blake3Hash: ByteArray,
) {
    init {
        require(blake3Hash.size == Blake3.DIGEST_LENGTH) {
            "FileAttrEntry blake3Hash must be 32 bytes"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is FileAttrEntry &&
            relativePath == other.relativePath &&
            fileType == other.fileType &&
            symlinkTarget == other.symlinkTarget &&
            unixMode == other.unixMode &&
            isExecutable == other.isExecutable &&
            modifiedAtEpochMillis == other.modifiedAtEpochMillis &&
            uncompressedSize == other.uncompressedSize &&
            blake3Hash.contentEquals(other.blake3Hash)

    override fun hashCode(): Int {
        var result = relativePath.hashCode()
        result = 31 * result + fileType.hashCode()
        result = 31 * result + symlinkTarget.hashCode()
        result = 31 * result + unixMode
        result = 31 * result + isExecutable.hashCode()
        result = 31 * result + modifiedAtEpochMillis.hashCode()
        result = 31 * result + uncompressedSize.hashCode()
        result = 31 * result + blake3Hash.contentHashCode()
        return result
    }
}

@Serializable
data class ChunkManifestEntry(
    val chunkIndex: Int,
    val blake3Hash: ByteArray,
    val payloadOffset: Long,
    val compressedEncryptedLength: Int,
    val uncompressedLength: Int,
) {
    init {
        require(blake3Hash.size == Blake3.DIGEST_LENGTH) {
            "ChunkManifestEntry blake3Hash must be 32 bytes"
        }
    }

    override fun equals(other: Any?): Boolean =
        other is ChunkManifestEntry &&
            chunkIndex == other.chunkIndex &&
            blake3Hash.contentEquals(other.blake3Hash) &&
            payloadOffset == other.payloadOffset &&
            compressedEncryptedLength == other.compressedEncryptedLength &&
            uncompressedLength == other.uncompressedLength

    override fun hashCode(): Int {
        var result = chunkIndex
        result = 31 * result + blake3Hash.contentHashCode()
        result = 31 * result + payloadOffset.hashCode()
        result = 31 * result + compressedEncryptedLength
        result = 31 * result + uncompressedLength
        return result
    }
}

/**
 * First 182 bytes of a .bstage or .bvault file:
 *   [0..149]   = 150-byte FixedHeader
 *   [150..181] = 32-byte HEADER_MAC = Blake3.keyedHash(bytes[0..149], vaultKey)
 *
 * Byte offsets within FixedHeader:
 *   [0..3]     MAGIC ("BSTG" for .bstage or "BVLT" for .bvault)
 *   [4..5]     FORMAT_VERSION (u16 LE = 1)
 *   [6]        CIPHER_ID (0 = AES_GCM, 1 = CHACHA20_POLY1305)
 *   [7..10]    ARGON2_M_COST (u32 LE)
 *   [11..14]   ARGON2_T_COST (u32 LE)
 *   [15..18]   ARGON2_PAR (u32 LE)
 *   [19..34]   ARGON2_SALT (16 bytes)
 *   [35..94]   WRAPPED_KEY (60 bytes = 12B nonce + 32B ct + 16B GCM tag)
 *   [95..106]  DATA_NONCE (12 bytes)
 *   [107..110] CHUNK_SIZE (u32 LE, default 4_194_304)
 *   [111..114] CHUNK_COUNT (u32 LE)
 *   [115..140] WORKSPACE_ID (26 bytes UTF-8, padded)
 *   [141]      PLATFORM_ORIGIN (0=WINDOWS, 1=MACOS, 2=LINUX, 3=ANDROID)
 *   [142..149] CREATED_AT (i64 LE epoch seconds)
 */
data class TinyHeader(
    val isEncrypted: Boolean,
    val formatVersion: Short,
    val cipherSuite: ResolvedCipher,
    val argon2MCost: Int,
    val argon2TCost: Int,
    val argon2Parallelism: Int,
    val argon2Salt: ByteArray,
    val wrappedKey: ByteArray,
    val dataNonce: ByteArray,
    val chunkSize: Int,
    val chunkCount: Int,
    val workspaceId: String,
    val platformOrigin: Platform,
    val createdAtEpochSeconds: Long,
    val headerMac: ByteArray,
    val macCoverBytes: ByteArray,
) {
    companion object {
        const val FIXED_HEADER_BYTES = 150
        const val HEADER_MAC_BYTES = 32
        const val TINY_HEADER_BYTES = FIXED_HEADER_BYTES + HEADER_MAC_BYTES // 182 bytes

        const val OFFSET_ARGON2_SALT = 19
        const val OFFSET_WRAPPED_KEY = 35
        const val OFFSET_DATA_NONCE = 95
        const val OFFSET_HEADER_MAC = 150

        val MAGIC_BSTAGE = byteArrayOf(0x42, 0x53, 0x54, 0x47) // "BSTG"
        val MAGIC_BVAULT = byteArrayOf(0x42, 0x56, 0x4C, 0x54) // "BVLT"
        const val CURRENT_VERSION: Short = 1
        const val DEFAULT_CHUNK_SIZE: Int = 4 * 1024 * 1024 // 4 MiB

        fun encodeFixedHeader(
            isEncrypted: Boolean,
            cipherSuite: ResolvedCipher,
            argon2Salt: ByteArray,
            wrappedKey: ByteArray,
            dataNonce: ByteArray,
            chunkSize: Int,
            chunkCount: Int,
            workspaceId: String,
            platformOrigin: Platform,
            createdAtEpochSeconds: Long,
        ): ByteArray {
            require(argon2Salt.size == Argon2Config.SALT_LENGTH) { "argon2Salt must be 16 bytes" }
            require(wrappedKey.size == AeadCipher.WRAPPED_KEY_BYTES) { "wrappedKey must be 60 bytes" }
            require(dataNonce.size == AeadCipher.NONCE_BYTES) { "dataNonce must be 12 bytes" }

            val buf = ByteBuffer.allocate(FIXED_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buf.put(if (isEncrypted) MAGIC_BSTAGE else MAGIC_BVAULT)
            buf.putShort(CURRENT_VERSION)
            buf.put(cipherSuite.idByte)
            buf.putInt(Argon2Config.M_COST)
            buf.putInt(Argon2Config.T_COST)
            buf.putInt(Argon2Config.PARALLELISM)
            buf.put(argon2Salt)
            buf.put(wrappedKey)
            buf.put(dataNonce)
            buf.putInt(chunkSize)
            buf.putInt(chunkCount)

            val wsBytes = ByteArray(26)
            val rawWs = workspaceId.encodeToByteArray()
            rawWs.copyInto(wsBytes, 0, 0, minOf(26, rawWs.size))
            buf.put(wsBytes)

            val platformByte: Byte = when (platformOrigin) {
                Platform.WINDOWS -> 0
                Platform.MACOS -> 1
                Platform.LINUX -> 2
                Platform.ANDROID -> 3
            }
            buf.put(platformByte)
            buf.putLong(createdAtEpochSeconds)
            return buf.array()
        }

        fun encodeTinyHeader(
            isEncrypted: Boolean,
            cipherSuite: ResolvedCipher,
            argon2Salt: ByteArray,
            wrappedKey: ByteArray,
            dataNonce: ByteArray,
            chunkSize: Int,
            chunkCount: Int,
            workspaceId: String,
            platformOrigin: Platform,
            createdAtEpochSeconds: Long,
            vaultKey: ByteArray,
        ): ByteArray {
            val fixed = encodeFixedHeader(
                isEncrypted = isEncrypted,
                cipherSuite = cipherSuite,
                argon2Salt = argon2Salt,
                wrappedKey = wrappedKey,
                dataNonce = dataNonce,
                chunkSize = chunkSize,
                chunkCount = chunkCount,
                workspaceId = workspaceId,
                platformOrigin = platformOrigin,
                createdAtEpochSeconds = createdAtEpochSeconds,
            )
            val mac = if (isEncrypted) {
                Blake3.keyedHash(fixed, vaultKey)
            } else {
                Blake3.hash(fixed)
            }
            return fixed + mac
        }

        fun parse(bytes182: ByteArray): TinyHeader {
            if (bytes182.size < TINY_HEADER_BYTES) {
                throw BackStageException(
                    BackStageError.InvalidVaultHeader("Header truncated: expected $TINY_HEADER_BYTES bytes, got ${bytes182.size}")
                )
            }
            val magic = bytes182.copyOfRange(0, 4)
            val isEncrypted = when {
                magic.contentEquals(MAGIC_BSTAGE) -> true
                magic.contentEquals(MAGIC_BVAULT) -> false
                else -> throw BackStageException(
                    BackStageError.InvalidVaultHeader("Unrecognized vault magic bytes")
                )
            }

            val buf = ByteBuffer.wrap(bytes182, 4, FIXED_HEADER_BYTES - 4).order(ByteOrder.LITTLE_ENDIAN)
            val version = buf.short
            if (version != CURRENT_VERSION) {
                throw BackStageException(
                    BackStageError.InvalidVaultHeader("Unsupported vault format version: $version")
                )
            }
            val cipherSuite = ResolvedCipher.fromIdByte(buf.get())
            val mCost = buf.int
            val tCost = buf.int
            val parallelism = buf.int

            val salt = ByteArray(Argon2Config.SALT_LENGTH)
            buf.get(salt)

            val wrappedKey = ByteArray(AeadCipher.WRAPPED_KEY_BYTES)
            buf.get(wrappedKey)

            val dataNonce = ByteArray(AeadCipher.NONCE_BYTES)
            buf.get(dataNonce)

            val chunkSize = buf.int
            val chunkCount = buf.int

            val wsBytes = ByteArray(26)
            buf.get(wsBytes)
            val workspaceId = wsBytes.decodeToString().trimEnd('\u0000', ' ')

            val platformOrigin = when (val code = buf.get().toInt()) {
                0 -> Platform.WINDOWS
                1 -> Platform.MACOS
                2 -> Platform.LINUX
                3 -> Platform.ANDROID
                else -> throw BackStageException(BackStageError.InvalidVaultHeader("Unknown platform code: $code"))
            }
            val createdAt = buf.long

            val macCover = bytes182.copyOfRange(0, FIXED_HEADER_BYTES)
            val headerMac = bytes182.copyOfRange(FIXED_HEADER_BYTES, TINY_HEADER_BYTES)

            return TinyHeader(
                isEncrypted = isEncrypted,
                formatVersion = version,
                cipherSuite = cipherSuite,
                argon2MCost = mCost,
                argon2TCost = tCost,
                argon2Parallelism = parallelism,
                argon2Salt = salt,
                wrappedKey = wrappedKey,
                dataNonce = dataNonce,
                chunkSize = chunkSize,
                chunkCount = chunkCount,
                workspaceId = workspaceId,
                platformOrigin = platformOrigin,
                createdAtEpochSeconds = createdAt,
                headerMac = headerMac,
                macCoverBytes = macCover,
            )
        }
    }
}

/**
 * Full .bstage / .bvault header including variable-length FILE_ATTR_TABLE and CHUNK_MANIFEST.
 */
data class FullVaultHeader(
    val tinyHeader: TinyHeader,
    val fileAttributes: List<FileAttrEntry>,
    val chunkManifest: List<ChunkManifestEntry>,
    val payloadStartOffset: Long,
) {
    companion object {
        fun serializeFileAttrTable(fileAttributes: List<FileAttrEntry>): ByteArray =
            serializeVariableTables(fileAttributes, emptyList()).let { bytes ->
                // Drop trailing 4-byte zero manifest count
                bytes.copyOfRange(0, bytes.size - 4)
            }

        fun serializeVariableTables(
            fileAttributes: List<FileAttrEntry>,
            chunkManifest: List<ChunkManifestEntry>,
        ): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val intBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)

            fun writeInt(v: Int) {
                intBuf.clear()
                intBuf.putInt(v)
                out.write(intBuf.array(), 0, 4)
            }

            fun writeLong(v: Long) {
                intBuf.clear()
                intBuf.putLong(v)
                out.write(intBuf.array(), 0, 8)
            }

            fun writeShort(v: Int) {
                intBuf.clear()
                intBuf.putShort(v.toShort())
                out.write(intBuf.array(), 0, 2)
            }

            // 1. FILE_ATTR_TABLE
            writeInt(fileAttributes.size)
            for (attr in fileAttributes) {
                val pathBytes = attr.relativePath.encodeToByteArray()
                writeShort(pathBytes.size)
                out.write(pathBytes)
                out.write(attr.fileType.code.toInt())
                val symBytes = attr.symlinkTarget.encodeToByteArray()
                writeShort(symBytes.size)
                if (symBytes.isNotEmpty()) {
                    out.write(symBytes)
                }
                writeShort(attr.unixMode)
                out.write(if (attr.isExecutable) 1 else 0)
                writeLong(attr.modifiedAtEpochMillis)
                writeLong(attr.uncompressedSize)
                out.write(attr.blake3Hash)
            }

            // 2. CHUNK_MANIFEST (48 bytes per chunk: blake3[32] + offset[8] + compLen[4] + uncompLen[4])
            writeInt(chunkManifest.size)
            for (chunk in chunkManifest) {
                out.write(chunk.blake3Hash)
                writeLong(chunk.payloadOffset)
                writeInt(chunk.compressedEncryptedLength)
                writeInt(chunk.uncompressedLength)
            }

            return out.toByteArray()
        }

        fun parseFromBytes(allHeaderBytes: ByteArray): FullVaultHeader {
            val tiny = TinyHeader.parse(allHeaderBytes)
            val buf = ByteBuffer.wrap(
                allHeaderBytes,
                TinyHeader.TINY_HEADER_BYTES,
                allHeaderBytes.size - TinyHeader.TINY_HEADER_BYTES
            ).order(ByteOrder.LITTLE_ENDIAN)

            val fileCount = buf.int
            val fileAttrs = ArrayList<FileAttrEntry>(fileCount)
            for (i in 0 until fileCount) {
                val pathLen = buf.short.toInt() and 0xFFFF
                val pathBytes = ByteArray(pathLen)
                buf.get(pathBytes)
                val fileType = ArchiveFileType.fromCode(buf.get())
                val symLen = buf.short.toInt() and 0xFFFF
                val symBytes = ByteArray(symLen)
                if (symLen > 0) buf.get(symBytes)
                val unixMode = buf.short.toInt() and 0xFFFF
                val isExec = buf.get().toInt() != 0
                val modifiedAt = buf.long
                val uncompressedSize = buf.long
                val blake3 = ByteArray(Blake3.DIGEST_LENGTH)
                buf.get(blake3)

                fileAttrs.add(
                    FileAttrEntry(
                        relativePath = pathBytes.decodeToString(),
                        fileType = fileType,
                        symlinkTarget = symBytes.decodeToString(),
                        unixMode = unixMode,
                        isExecutable = isExec,
                        modifiedAtEpochMillis = modifiedAt,
                        uncompressedSize = uncompressedSize,
                        blake3Hash = blake3,
                    )
                )
            }

            val manifestCount = buf.int
            val chunks = ArrayList<ChunkManifestEntry>(manifestCount)
            for (i in 0 until manifestCount) {
                val blake3 = ByteArray(Blake3.DIGEST_LENGTH)
                buf.get(blake3)
                val offset = buf.long
                val compLen = buf.int
                val uncompLen = buf.int
                chunks.add(
                    ChunkManifestEntry(
                        chunkIndex = i,
                        blake3Hash = blake3,
                        payloadOffset = offset,
                        compressedEncryptedLength = compLen,
                        uncompressedLength = uncompLen,
                    )
                )
            }

            val payloadStart = buf.position().toLong()
            return FullVaultHeader(
                tinyHeader = tiny,
                fileAttributes = fileAttrs,
                chunkManifest = chunks,
                payloadStartOffset = payloadStart,
            )
        }

        /**
         * Reads and parses ONLY the header and variable tables (FILE_ATTR_TABLE + CHUNK_MANIFEST)
         * from [backend] in 64 KiB windows without loading the encrypted chunk payload into RAM.
         */
        suspend fun readFromBackend(
            backend: dev.legendsayantan.backstage.core.backend.VaultBackend,
            fileName: String,
        ): FullVaultHeader {
            val fileSize = backend.sizeBytes(fileName)
            if (fileSize < TinyHeader.TINY_HEADER_BYTES) {
                throw BackStageException(
                    BackStageError.InvalidVaultHeader("Vault file too small ($fileSize bytes)")
                )
            }

            var buffer = backend.readBytes(
                fileName = fileName,
                offset = 0L,
                count = minOf(fileSize, 65_536L).toInt(),
            )
            var cursor = 0

            suspend fun ensureAvailable(bytesNeeded: Int) {
                while (cursor + bytesNeeded > buffer.size) {
                    val remainingInFile = (fileSize - buffer.size).coerceAtLeast(0L)
                    if (remainingInFile == 0L) {
                        throw BackStageException(
                            BackStageError.InvalidVaultHeader("Unexpected EOF while reading vault header tables")
                        )
                    }
                    val fetchSize = maxOf(bytesNeeded - (buffer.size - cursor), 65_536)
                        .toLong()
                        .coerceAtMost(remainingInFile)
                        .toInt()
                    val more = backend.readBytes(
                        fileName = fileName,
                        offset = buffer.size.toLong(),
                        count = fetchSize,
                    )
                    if (more.isEmpty()) {
                        throw BackStageException(
                            BackStageError.InvalidVaultHeader("Unexpected EOF from backend while reading vault header")
                        )
                    }
                    buffer += more
                }
            }

            fun readByte(): Byte {
                val b = buffer[cursor]
                cursor += 1
                return b
            }

            fun readBytesSlice(len: Int): ByteArray {
                val slice = buffer.copyOfRange(cursor, cursor + len)
                cursor += len
                return slice
            }

            fun readShortLE(): Int {
                val v = ByteBuffer.wrap(buffer, cursor, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                cursor += 2
                return v
            }

            fun readIntLE(): Int {
                val v = ByteBuffer.wrap(buffer, cursor, 4).order(ByteOrder.LITTLE_ENDIAN).int
                cursor += 4
                return v
            }

            fun readLongLE(): Long {
                val v = ByteBuffer.wrap(buffer, cursor, 8).order(ByteOrder.LITTLE_ENDIAN).long
                cursor += 8
                return v
            }

            ensureAvailable(TinyHeader.TINY_HEADER_BYTES + 4)
            val tiny = TinyHeader.parse(readBytesSlice(TinyHeader.TINY_HEADER_BYTES))

            val fileCount = readIntLE()
            val fileAttrs = ArrayList<FileAttrEntry>(fileCount)
            for (i in 0 until fileCount) {
                ensureAvailable(2)
                val pathLen = readShortLE()
                ensureAvailable(pathLen + 1 + 2)
                val pathBytes = readBytesSlice(pathLen)
                val fileType = ArchiveFileType.fromCode(readByte())
                val symLen = readShortLE()
                ensureAvailable(symLen + 2 + 1 + 8 + 8 + Blake3.DIGEST_LENGTH)
                val symBytes = if (symLen > 0) readBytesSlice(symLen) else ByteArray(0)
                val unixMode = readShortLE()
                val isExec = readByte().toInt() != 0
                val modifiedAt = readLongLE()
                val uncompressedSize = readLongLE()
                val blake3 = readBytesSlice(Blake3.DIGEST_LENGTH)

                fileAttrs.add(
                    FileAttrEntry(
                        relativePath = pathBytes.decodeToString(),
                        fileType = fileType,
                        symlinkTarget = symBytes.decodeToString(),
                        unixMode = unixMode,
                        isExecutable = isExec,
                        modifiedAtEpochMillis = modifiedAt,
                        uncompressedSize = uncompressedSize,
                        blake3Hash = blake3,
                    )
                )
            }

            ensureAvailable(4)
            val manifestCount = readIntLE()
            val chunks = ArrayList<ChunkManifestEntry>(manifestCount)
            ensureAvailable(manifestCount * (Blake3.DIGEST_LENGTH + 8 + 4 + 4))
            for (i in 0 until manifestCount) {
                val blake3 = readBytesSlice(Blake3.DIGEST_LENGTH)
                val offset = readLongLE()
                val compLen = readIntLE()
                val uncompLen = readIntLE()
                chunks.add(
                    ChunkManifestEntry(
                        chunkIndex = i,
                        blake3Hash = blake3,
                        payloadOffset = offset,
                        compressedEncryptedLength = compLen,
                        uncompressedLength = uncompLen,
                    )
                )
            }

            return FullVaultHeader(
                tinyHeader = tiny,
                fileAttributes = fileAttrs,
                chunkManifest = chunks,
                payloadStartOffset = cursor.toLong(),
            )
        }
    }
}
