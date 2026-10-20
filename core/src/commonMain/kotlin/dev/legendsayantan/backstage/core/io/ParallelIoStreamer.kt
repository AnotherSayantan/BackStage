package dev.legendsayantan.backstage.core.io

import dev.legendsayantan.backstage.core.backend.LocalPathBackend
import dev.legendsayantan.backstage.core.backend.VaultBackend
import dev.legendsayantan.backstage.core.crypto.AeadCipher
import dev.legendsayantan.backstage.core.crypto.ArchiveFileType
import dev.legendsayantan.backstage.core.crypto.Argon2Config
import dev.legendsayantan.backstage.core.crypto.Blake3
import dev.legendsayantan.backstage.core.crypto.BsmetaKdfInfo
import dev.legendsayantan.backstage.core.crypto.BsmetaManualRecovery
import dev.legendsayantan.backstage.core.crypto.BsmetaSidecar
import dev.legendsayantan.backstage.core.crypto.ChunkManifestEntry
import dev.legendsayantan.backstage.core.crypto.CipherSuiteDetector
import dev.legendsayantan.backstage.core.crypto.FileAttrEntry
import dev.legendsayantan.backstage.core.crypto.FullVaultHeader
import dev.legendsayantan.backstage.core.crypto.GcmAuthFailedException
import dev.legendsayantan.backstage.core.crypto.SevenZipDetector
import dev.legendsayantan.backstage.core.crypto.TinyHeader
import dev.legendsayantan.backstage.core.crypto.VaultFormatConfig
import dev.legendsayantan.backstage.core.crypto.WorkspaceKeyMode
import dev.legendsayantan.backstage.core.crypto.toHexString
import dev.legendsayantan.backstage.core.error.BackStageError
import dev.legendsayantan.backstage.core.error.BackStageException
import dev.legendsayantan.backstage.core.platform.Platform
import dev.legendsayantan.backstage.core.platform.SecureRandomProvider
import dev.legendsayantan.backstage.core.platform.currentPlatform
import dev.legendsayantan.backstage.core.workspace.WorkspaceConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdUtils

class ParallelIoStreamer {

    private fun monotonicProgressWrapper(
        delegate: (Float, Long, Long) -> Unit,
    ): (Float, Long, Long) -> Unit {
        val lock = Any()
        var maxFrac = 0f
        var maxDone = 0L
        return { frac, done, total ->
            synchronized(lock) {
                val clampedFrac = frac.coerceIn(maxFrac, 1f)
                val clampedDone = maxOf(maxDone, done)
                if (clampedFrac > maxFrac || clampedFrac == 0f || clampedFrac >= 1f) {
                    maxFrac = clampedFrac
                    maxDone = clampedDone
                    delegate(clampedFrac, clampedDone, total)
                }
            }
        }
    }

    /**
     * Packs a local workspace directory into its configured vault format (.bstage, .7z, or .bvault)
     * and writes it to [backend]. Returns the total uncompressed byte size.
     */
    suspend fun packWorkspace(
        workspace: WorkspaceConfig,
        sourceDirectory: File,
        backend: VaultBackend,
        vaultKey: ByteArray,
        argon2Salt: ByteArray = SecureRandomProvider.nextBytes(Argon2Config.SALT_LENGTH),
        wrappedKey: ByteArray = ByteArray(AeadCipher.WRAPPED_KEY_BYTES),
        chunkSize: Int = TinyHeader.DEFAULT_CHUNK_SIZE,
        onProgress: (Float, Long, Long) -> Unit = { _, _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        val safeProgress = monotonicProgressWrapper(onProgress)
        when (workspace.vaultFormat) {
            VaultFormatConfig.Native -> packNativeOrPlain(
                workspace = workspace,
                sourceDirectory = sourceDirectory,
                backend = backend,
                isEncrypted = true,
                vaultKey = vaultKey,
                argon2Salt = argon2Salt,
                wrappedKey = wrappedKey,
                chunkSize = chunkSize,
                onProgress = safeProgress,
            )

            VaultFormatConfig.PlainCompressed -> packNativeOrPlain(
                workspace = workspace,
                sourceDirectory = sourceDirectory,
                backend = backend,
                isEncrypted = false,
                vaultKey = ByteArray(AeadCipher.KEY_BYTES),
                argon2Salt = ByteArray(Argon2Config.SALT_LENGTH),
                wrappedKey = ByteArray(AeadCipher.WRAPPED_KEY_BYTES),
                chunkSize = chunkSize,
                onProgress = safeProgress,
            )

            VaultFormatConfig.Portable -> packPortableSevenZip(
                workspace = workspace,
                sourceDirectory = sourceDirectory,
                backend = backend,
                vaultKey = vaultKey,
                argon2Salt = argon2Salt,
                wrappedKey = wrappedKey,
                onProgress = safeProgress,
            )

            VaultFormatConfig.PlainSevenZip -> packPortableSevenZip(
                workspace = workspace,
                sourceDirectory = sourceDirectory,
                backend = backend,
                vaultKey = ByteArray(AeadCipher.KEY_BYTES),
                argon2Salt = ByteArray(Argon2Config.SALT_LENGTH),
                wrappedKey = ByteArray(AeadCipher.WRAPPED_KEY_BYTES),
                onProgress = safeProgress,
            )
        }
    }

    /**
     * Unpacks a workspace vault from [backend] into [targetDirectory], verifying BLAKE3 chunk
     * integrity BEFORE decrypting, and applying platform-specific file attributes.
     */
    suspend fun unpackWorkspace(
        workspace: WorkspaceConfig,
        targetDirectory: File,
        backend: VaultBackend,
        vaultKey: ByteArray,
        onProgress: (Float, Long, Long) -> Unit = { _, _, _ -> },
    ): Long = withContext(Dispatchers.IO) {
        val safeProgress = monotonicProgressWrapper(onProgress)
        when (workspace.vaultFormat) {
            VaultFormatConfig.Native, VaultFormatConfig.PlainCompressed -> unpackNativeOrPlain(
                workspace = workspace,
                targetDirectory = targetDirectory,
                backend = backend,
                vaultKey = vaultKey,
                onProgress = safeProgress,
            )

            VaultFormatConfig.Portable, VaultFormatConfig.PlainSevenZip -> unpackPortableSevenZip(
                workspace = workspace,
                targetDirectory = targetDirectory,
                backend = backend,
                vaultKey = vaultKey,
                onProgress = safeProgress,
            )
        }
    }

    private suspend fun packNativeOrPlain(
        workspace: WorkspaceConfig,
        sourceDirectory: File,
        backend: VaultBackend,
        isEncrypted: Boolean,
        vaultKey: ByteArray,
        argon2Salt: ByteArray,
        wrappedKey: ByteArray,
        chunkSize: Int,
        onProgress: (Float, Long, Long) -> Unit,
    ): Long = coroutineScope {
        val fileAttrs = mutableListOf<FileAttrEntry>()
        var totalUncompressedBytes = 0L

        val cipherSuite = CipherSuiteDetector.detect()
        val dataNonce = SecureRandomProvider.nextBytes(AeadCipher.NONCE_BYTES)
        val targetVault = (backend as? dev.legendsayantan.backstage.core.backend.LocalPathBackend)?.resolveFile(workspace.vaultFileName)
        val tempChunksFile = if (targetVault != null) {
            targetVault.parentFile?.mkdirs()
            File.createTempFile("backstage_pack_", ".chunks", targetVault.parentFile)
        } else {
            File.createTempFile("backstage_pack_", ".chunks")
        }

        try {
            val manifest = ArrayList<ChunkManifestEntry>()
            var runningOffset = 0L
            var processedUncompressed = 0L
            var nextChunkIndex = 0
            val pendingRawChunks = mutableListOf<Pair<Int, ByteArray>>()
            val maxParallelBatch = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            val bytesReadAtomic = java.util.concurrent.atomic.AtomicLong(0L)
            val bytesCompressedEncrypted = java.util.concurrent.atomic.AtomicLong(0L)

            fun emitPackPhase1Progress(estimatedTotal: Long) {
                val denom = estimatedTotal.coerceAtLeast(bytesReadAtomic.get()).coerceAtLeast(1L)
                val readRatio = (bytesReadAtomic.get().toDouble() / denom.toDouble()).coerceIn(0.0, 1.0)
                val cryptoRatio = (bytesCompressedEncrypted.get().toDouble() / denom.toDouble()).coerceIn(0.0, 1.0)
                val combined = (readRatio * 0.30 + cryptoRatio * 0.70).coerceIn(0.0, 1.0)
                val fraction = (combined * 0.80).toFloat().coerceIn(0f, 0.80f)
                val reportedBytes = (combined * denom).toLong().coerceIn(0L, denom)
                onProgress(fraction, reportedBytes, denom)
            }

            java.io.FileOutputStream(tempChunksFile).buffered().use { tempOut ->
                suspend fun flushPendingBatch(estimatedTotal: Long) {
                    if (pendingRawChunks.isEmpty()) return
                    val batch = pendingRawChunks.toList()
                    pendingRawChunks.clear()

                    val processedBatch = batch.map { (index, rawChunk) ->
                        async(Dispatchers.Default) {
                            val rawLen = rawChunk.size.toLong()
                            val halfLen = rawLen / 2L
                            val compressed = compressChunk(rawChunk)
                            bytesCompressedEncrypted.addAndGet(halfLen)
                            emitPackPhase1Progress(estimatedTotal)

                            val encryptedOrPlain = try {
                                if (isEncrypted) {
                                    AeadCipher.encryptChunk(
                                        cipherSuite = cipherSuite,
                                        plaintext = compressed,
                                        vaultKey = vaultKey,
                                        baseNonce12 = dataNonce,
                                        chunkIndex = index,
                                    )
                                } else {
                                    compressed
                                }
                            } finally {
                                if (isEncrypted) compressed.fill(0)
                            }
                            val chunkHash = Blake3.hash(encryptedOrPlain)
                            bytesCompressedEncrypted.addAndGet(rawLen - halfLen)
                            emitPackPhase1Progress(estimatedTotal)
                            Triple(index, encryptedOrPlain, chunkHash)
                        }
                    }.awaitAll().sortedBy { it.first }

                    for ((i, triple) in processedBatch.withIndex()) {
                        val (index, chunkBytes, chunkHash) = triple
                        val rawChunk = batch[i].second
                        val uncompLen = rawChunk.size
                        rawChunk.fill(0)

                        tempOut.write(chunkBytes)
                        manifest.add(
                            ChunkManifestEntry(
                                chunkIndex = index,
                                blake3Hash = chunkHash,
                                payloadOffset = runningOffset,
                                compressedEncryptedLength = chunkBytes.size,
                                uncompressedLength = uncompLen,
                            )
                        )
                        runningOffset += chunkBytes.size
                        processedUncompressed += uncompLen
                    }
                    emitPackPhase1Progress(estimatedTotal)
                }

                var currentChunkBuf = ByteArray(chunkSize)
                var currentChunkPos = 0
                var estimatedPayloadTotal = 1L

                suspend fun appendToStream(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
                    var srcPos = offset
                    var remaining = length
                    while (remaining > 0) {
                        val space = chunkSize - currentChunkPos
                        val toCopy = minOf(space, remaining)
                        bytes.copyInto(
                            destination = currentChunkBuf,
                            destinationOffset = currentChunkPos,
                            startIndex = srcPos,
                            endIndex = srcPos + toCopy,
                        )
                        currentChunkPos += toCopy
                        srcPos += toCopy
                        remaining -= toCopy

                        if (currentChunkPos == chunkSize) {
                            pendingRawChunks.add(nextChunkIndex++ to currentChunkBuf)
                            currentChunkBuf = ByteArray(chunkSize)
                            currentChunkPos = 0
                            if (pendingRawChunks.size >= maxParallelBatch) {
                                flushPendingBatch(estimatedPayloadTotal)
                            }
                        }
                    }
                }

                if (sourceDirectory.isDirectory) {
                    val allPaths = Files.walk(sourceDirectory.toPath()).use { stream ->
                        stream.filter { it != sourceDirectory.toPath() }
                            .sorted()
                            .toList()
                    }

                    estimatedPayloadTotal = allPaths.sumOf { p ->
                        runCatching {
                            if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) Files.size(p) + 8L else 0L
                        }.getOrDefault(0L)
                    }.coerceAtLeast(1L)

                    val readBuf = ByteArray(65_536)
                    val longPrefixBuf = ByteArray(8)

                    for (path in allPaths) {
                        val relPath = sourceDirectory.toPath().relativize(path).toString().replace('\\', '/')
                        val fileName = path.fileName.toString()
                        if (fileName in FolderLock.IGNORED_FILES) continue

                        val isSymlink = Files.isSymbolicLink(path)
                        val isDir = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                        val unixMode = readPosixModeOrZero(path)
                        val isExec = runCatching { Files.isExecutable(path) }.getOrDefault(false)
                        val modifiedMillis = runCatching {
                            Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis()
                        }.getOrDefault(System.currentTimeMillis())

                        when {
                            isSymlink -> {
                                val target = Files.readSymbolicLink(path).toString().replace('\\', '/')
                                val targetBytes = target.encodeToByteArray()
                                val hash = Blake3.hash(targetBytes)
                                fileAttrs.add(
                                    FileAttrEntry(
                                        relativePath = relPath,
                                        fileType = ArchiveFileType.SYMLINK,
                                        symlinkTarget = target,
                                        unixMode = unixMode,
                                        isExecutable = isExec,
                                        modifiedAtEpochMillis = modifiedMillis,
                                        uncompressedSize = 0L,
                                        blake3Hash = hash,
                                    )
                                )
                            }

                            isDir -> {
                                fileAttrs.add(
                                    FileAttrEntry(
                                        relativePath = relPath,
                                        fileType = ArchiveFileType.DIRECTORY,
                                        unixMode = unixMode,
                                        isExecutable = true,
                                        modifiedAtEpochMillis = modifiedMillis,
                                        uncompressedSize = 0L,
                                        blake3Hash = ByteArray(Blake3.DIGEST_LENGTH),
                                    )
                                )
                            }

                            else -> {
                                val fileObj = path.toFile()
                                val fileSize = fileObj.length()
                                totalUncompressedBytes += fileSize

                                // Write 8-byte big-endian Long length prefix
                                longPrefixBuf[0] = ((fileSize ushr 56) and 0xFF).toByte()
                                longPrefixBuf[1] = ((fileSize ushr 48) and 0xFF).toByte()
                                longPrefixBuf[2] = ((fileSize ushr 40) and 0xFF).toByte()
                                longPrefixBuf[3] = ((fileSize ushr 32) and 0xFF).toByte()
                                longPrefixBuf[4] = ((fileSize ushr 24) and 0xFF).toByte()
                                longPrefixBuf[5] = ((fileSize ushr 16) and 0xFF).toByte()
                                longPrefixBuf[6] = ((fileSize ushr 8) and 0xFF).toByte()
                                longPrefixBuf[7] = (fileSize and 0xFF).toByte()
                                appendToStream(longPrefixBuf, 0, 8)
                                bytesReadAtomic.addAndGet(8L)

                                val hasher = Blake3.StreamingHasher()
                                java.io.FileInputStream(fileObj).use { fis ->
                                    while (true) {
                                        val read = fis.read(readBuf)
                                        if (read <= 0) break
                                        hasher.update(readBuf, 0, read)
                                        appendToStream(readBuf, 0, read)
                                        bytesReadAtomic.addAndGet(read.toLong())
                                        emitPackPhase1Progress(estimatedPayloadTotal)
                                    }
                                }
                                val hash = hasher.finish()

                                fileAttrs.add(
                                    FileAttrEntry(
                                        relativePath = relPath,
                                        fileType = ArchiveFileType.REGULAR,
                                        unixMode = unixMode,
                                        isExecutable = isExec,
                                        modifiedAtEpochMillis = modifiedMillis,
                                        uncompressedSize = fileSize,
                                        blake3Hash = hash,
                                    )
                                )
                            }
                        }
                    }
                    readBuf.fill(0)
                }

                // B-2: Append 32-byte BLAKE3 digest of FILE_ATTR_TABLE into the encrypted payload stream
                // so any tampering with unencrypted file paths, permissions, or symlinks is caught before extraction.
                val attrTableHash = Blake3.hash(FullVaultHeader.serializeFileAttrTable(fileAttrs))
                appendToStream(attrTableHash, 0, Blake3.DIGEST_LENGTH)
                bytesReadAtomic.addAndGet(Blake3.DIGEST_LENGTH.toLong())

                if (currentChunkPos > 0 || (nextChunkIndex == 0 && pendingRawChunks.isEmpty())) {
                    val tailChunk = currentChunkBuf.copyOfRange(0, currentChunkPos)
                    currentChunkBuf.fill(0)
                    pendingRawChunks.add(nextChunkIndex++ to tailChunk)
                }
                flushPendingBatch(estimatedPayloadTotal)
                tempOut.flush()
            }

            val nowSec = System.currentTimeMillis() / 1000L
            val variableHeaderBytes = FullVaultHeader.serializeVariableTables(
                fileAttributes = fileAttrs,
                chunkManifest = manifest,
            )
            val tinyHeaderBytes = TinyHeader.encodeTinyHeader(
                isEncrypted = isEncrypted,
                cipherSuite = cipherSuite,
                argon2Salt = argon2Salt,
                wrappedKey = wrappedKey,
                dataNonce = dataNonce,
                chunkSize = chunkSize,
                chunkCount = manifest.size,
                workspaceId = workspace.id.value,
                platformOrigin = workspace.platformOrigin,
                createdAtEpochSeconds = workspace.createdAtEpochSeconds.takeIf { it > 0 } ?: nowSec,
                vaultKey = vaultKey,
            )

            // Phase 2 (0.80 -> 0.99): Stream header + encrypted chunks directly to backend sink
            val totalTempBytes = tempChunksFile.length().coerceAtLeast(1L)
            var writtenToBackend = 0L
            val finalDenom = totalUncompressedBytes.coerceAtLeast(1L)
            backend.openWriteStream(workspace.vaultFileName).use { outSink ->
                val ioBuf = kotlinx.io.Buffer()
                ioBuf.write(tinyHeaderBytes)
                ioBuf.write(variableHeaderBytes)
                outSink.write(ioBuf, ioBuf.size)
                java.io.FileInputStream(tempChunksFile).use { fis ->
                    val copyBuf = ByteArray(4 * 1024 * 1024)
                    while (true) {
                        val read = fis.read(copyBuf)
                        if (read <= 0) break
                        ioBuf.write(copyBuf, 0, read)
                        outSink.write(ioBuf, ioBuf.size)
                        writtenToBackend += read
                        val writeRatio = (writtenToBackend.toFloat() / totalTempBytes.toFloat()).coerceIn(0f, 1f)
                        val overallFrac = (0.80f + writeRatio * 0.19f).coerceIn(0.80f, 0.99f)
                        val reportedBytes = ((0.80f + writeRatio * 0.20f) * finalDenom).toLong().coerceIn(0L, finalDenom)
                        onProgress(overallFrac, reportedBytes, finalDenom)
                    }
                }
                outSink.flush()
            }

            // Always write .bsmeta sidecar alongside encrypted vaults
            if (isEncrypted) {
                val headerMac = tinyHeaderBytes.copyOfRange(
                    TinyHeader.FIXED_HEADER_BYTES,
                    TinyHeader.TINY_HEADER_BYTES
                )
                val sidecar = BsmetaSidecar(
                    workspaceId = workspace.id.value,
                    displayName = workspace.displayName,
                    platformOrigin = workspace.platformOrigin,
                    vaultFormat = workspace.vaultFormat.fileExtension,
                    archiveFilename = workspace.vaultFileName,
                    passwordHint = (workspace.keyMode as? WorkspaceKeyMode.PasswordBacked)?.passwordHint,
                    headerMacHex = headerMac.toHexString(),
                    kdf = BsmetaKdfInfo(
                        saltHex = argon2Salt.toHexString(),
                        wrappedKeyHex = wrappedKey.toHexString(),
                    ),
                )
                backend.writeAllBytes(
                    BsmetaSidecar.sidecarFileName(workspace.vaultFileName),
                    BsmetaSidecar.encode(sidecar).encodeToByteArray(),
                )
            }

            onProgress(1.0f, totalUncompressedBytes, totalUncompressedBytes.coerceAtLeast(1L))
            totalUncompressedBytes
        } finally {
            tempChunksFile.delete()
        }
    }

    private suspend fun unpackNativeOrPlain(
        workspace: WorkspaceConfig,
        targetDirectory: File,
        backend: VaultBackend,
        vaultKey: ByteArray,
        onProgress: (Float, Long, Long) -> Unit,
    ): Long = coroutineScope {
        val vaultFileSize = backend.sizeBytes(workspace.vaultFileName)
        val header = FullVaultHeader.readFromBackend(backend, workspace.vaultFileName)
        val tiny = header.tinyHeader

        // 1. Authenticate 182-byte TinyHeader HEADER_MAC
        val expectedHeaderMac = if (tiny.isEncrypted) {
            Blake3.keyedHash(tiny.macCoverBytes, vaultKey)
        } else {
            Blake3.hash(tiny.macCoverBytes)
        }
        if (!expectedHeaderMac.contentEquals(tiny.headerMac)) {
            throw BackStageException(BackStageError.HeaderMacInvalid)
        }

        val tempPayloadFile = File.createTempFile("backstage_unpack_", ".payload")
        try {
            // 2. Phase 1 (0.00 -> 0.55): Stream, verify BLAKE3 chunk integrity BEFORE decrypting, & decompress in parallel
            val totalUncompressedEstimate = header.chunkManifest.sumOf { it.uncompressedLength.toLong() }
            val estimateDenom = totalUncompressedEstimate.coerceAtLeast(1L)
            val maxParallelBatch = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
            val chunkWorkDoneBytes = java.util.concurrent.atomic.AtomicLong(0L)

            java.io.FileOutputStream(tempPayloadFile).buffered().use { payloadOut ->
                for (batch in header.chunkManifest.chunked(maxParallelBatch)) {
                    val decompressedBatch = batch.map { entry ->
                        async(Dispatchers.Default) {
                            val start = header.payloadStartOffset + entry.payloadOffset
                            val end = start + entry.compressedEncryptedLength
                            if (start < 0L || end > vaultFileSize) {
                                throw BackStageException(BackStageError.ChunkCorrupted(entry.chunkIndex, refetchable = false))
                            }
                            val chunkCiphertext = backend.readBytes(
                                fileName = workspace.vaultFileName,
                                offset = start,
                                count = entry.compressedEncryptedLength,
                            )

                            // Verify BLAKE3 hash BEFORE decrypting
                            val actualHash = Blake3.hash(chunkCiphertext)
                            if (!actualHash.contentEquals(entry.blake3Hash)) {
                                throw BackStageException(BackStageError.ChunkCorrupted(entry.chunkIndex, refetchable = true))
                            }

                            val chunkUncompLen = entry.uncompressedLength.toLong().coerceAtLeast(1L)
                            val halfChunk = chunkUncompLen / 2L
                            val doneAfterRead = chunkWorkDoneBytes.addAndGet(halfChunk)
                            val fracAfterRead = ((doneAfterRead.toFloat() / estimateDenom.toFloat()) * 0.55f).coerceIn(0f, 0.55f)
                            onProgress(fracAfterRead, (doneAfterRead / 2L).coerceIn(0L, estimateDenom), estimateDenom)

                            val compressed = if (tiny.isEncrypted) {
                                try {
                                    AeadCipher.decryptChunk(
                                        cipherSuite = tiny.cipherSuite,
                                        ciphertextWithTag = chunkCiphertext,
                                        vaultKey = vaultKey,
                                        baseNonce12 = tiny.dataNonce,
                                        chunkIndex = entry.chunkIndex,
                                    )
                                } catch (e: GcmAuthFailedException) {
                                    throw BackStageException(BackStageError.WrongPassword, e.message ?: "Decryption failed", e)
                                }
                            } else {
                                chunkCiphertext
                            }

                            val rawChunk = try {
                                decompressChunk(compressed, entry.uncompressedLength)
                            } finally {
                                if (tiny.isEncrypted) compressed.fill(0)
                            }

                            val doneAfterDecomp = chunkWorkDoneBytes.addAndGet(chunkUncompLen - halfChunk)
                            val fracAfterDecomp = ((doneAfterDecomp.toFloat() / estimateDenom.toFloat()) * 0.55f).coerceIn(0f, 0.55f)
                            onProgress(fracAfterDecomp, (doneAfterDecomp / 2L).coerceIn(0L, estimateDenom), estimateDenom)

                            Pair(entry.chunkIndex, rawChunk)
                        }
                    }.awaitAll().sortedBy { it.first }

                    for ((_, rawChunk) in decompressedBatch) {
                        payloadOut.write(rawChunk)
                        rawChunk.fill(0)
                    }
                }
                payloadOut.flush()
            }

            // 3. B-2: Verify AEAD-authenticated 32-byte FILE_ATTR_TABLE digest BEFORE touching the filesystem
            if (tempPayloadFile.length() < Blake3.DIGEST_LENGTH) {
                throw BackStageException(BackStageError.HeaderMacInvalid, "Missing authenticated FILE_ATTR_TABLE trailer")
            }
            val expectedAttrHash = ByteArray(Blake3.DIGEST_LENGTH)
            java.io.RandomAccessFile(tempPayloadFile, "r").use { raf ->
                raf.seek(tempPayloadFile.length() - Blake3.DIGEST_LENGTH)
                raf.readFully(expectedAttrHash)
            }
            val actualAttrHash = Blake3.hash(FullVaultHeader.serializeFileAttrTable(header.fileAttributes))
            if (!actualAttrHash.contentEquals(expectedAttrHash)) {
                throw BackStageException(
                    BackStageError.HeaderMacInvalid,
                    "FILE_ATTR_TABLE tampering detected: header attributes do not match authenticated payload digest"
                )
            }

            // 4. Phase 2 (0.55 -> 0.99): Reconstruct files, directories, symlinks from streamed payload file
            targetDirectory.mkdirs()
            val platform = currentPlatform()
            val targetRoot = targetDirectory.toPath().toAbsolutePath().normalize()
            val caseInsensitiveFs = isDirectoryCaseInsensitive(targetDirectory)
            val seenLowerPaths = mutableSetOf<String>()
            var extractedTotalBytes = 0L
            var streamExtractedBytes = 0L
            val copyBuf = ByteArray(65_536)
            val totalAttrCount = header.fileAttributes.size.coerceAtLeast(1)

            DataInputStream(java.io.BufferedInputStream(java.io.FileInputStream(tempPayloadFile))).use { dataIn ->
                for ((attrIdx, attr) in header.fileAttributes.withIndex()) {
                    if (caseInsensitiveFs) {
                        val lower = attr.relativePath.lowercase()
                        if (!seenLowerPaths.add(lower)) {
                            // Skip duplicate case-colliding file on case-insensitive FS after consuming its stream bytes
                            if (attr.fileType == ArchiveFileType.REGULAR) {
                                var rem = dataIn.readLong()
                                while (rem > 0L) {
                                    val skipped = dataIn.read(copyBuf, 0, minOf(copyBuf.size.toLong(), rem).toInt())
                                    if (skipped <= 0) break
                                    rem -= skipped
                                    streamExtractedBytes += skipped
                                }
                            }
                            continue
                        }
                    }

                    val destFile = safeResolve(targetRoot, attr.relativePath)

                    when (attr.fileType) {
                        ArchiveFileType.DIRECTORY -> {
                            destFile.mkdirs()
                            applyPlatformAttributes(attr, destFile.toPath(), platform)
                        }

                        ArchiveFileType.SYMLINK -> {
                            destFile.parentFile?.mkdirs()
                            val symlinkResolved = destFile.toPath().parent
                                .resolve(attr.symlinkTarget)
                                .normalize()
                            if (!symlinkResolved.startsWith(targetRoot)) {
                                throw BackStageException(
                                    BackStageError.BackendIOError("Symlink escape attempt blocked: ${attr.symlinkTarget} escapes workspace root")
                                )
                            }
                            if (platform.isPosix) {
                                Files.deleteIfExists(destFile.toPath())
                                Files.createSymbolicLink(destFile.toPath(), Path.of(attr.symlinkTarget))
                            } else {
                                // Windows non-elevated symlink fallback per spec §2.6: write target path as file content
                                destFile.writeText(attr.symlinkTarget)
                            }
                        }

                        ArchiveFileType.REGULAR -> {
                            val len = dataIn.readLong()
                            destFile.parentFile?.mkdirs()
                            // Clear read-only attribute before overwriting (Windows read-only files)
                            if (destFile.exists() && !destFile.canWrite()) destFile.setWritable(true)
                            val hasher = Blake3.StreamingHasher()
                            java.io.FileOutputStream(destFile).buffered().use { fos ->
                                var remaining = len
                                while (remaining > 0L) {
                                    val toRead = minOf(copyBuf.size.toLong(), remaining).toInt()
                                    dataIn.readFully(copyBuf, 0, toRead)
                                    hasher.update(copyBuf, 0, toRead)
                                    fos.write(copyBuf, 0, toRead)
                                    remaining -= toRead
                                    streamExtractedBytes += toRead
                                    val fileFrac = (streamExtractedBytes.toFloat() / estimateDenom.toFloat()).coerceIn(0f, 1f)
                                    val overallFrac = (0.55f + fileFrac * 0.44f).coerceIn(0.55f, 0.99f)
                                    val reportedBytes = ((estimateDenom / 2L) + (streamExtractedBytes / 2L)).coerceIn(0L, estimateDenom)
                                    onProgress(overallFrac, reportedBytes, estimateDenom)
                                }
                            }

                            val actualContentHash = hasher.finish()
                            if (!actualContentHash.contentEquals(attr.blake3Hash)) {
                                destFile.delete()
                                throw BackStageException(
                                    BackStageError.ChunkCorrupted(chunkIndex = 0, refetchable = false),
                                    "File content BLAKE3 mismatch for ${attr.relativePath}"
                                )
                            }

                            extractedTotalBytes += len
                            applyPlatformAttributes(attr, destFile.toPath(), platform)
                        }
                    }
                    if (totalUncompressedEstimate <= 32L) {
                        val countFrac = ((attrIdx + 1).toFloat() / totalAttrCount.toFloat()).coerceIn(0f, 1f)
                        onProgress(0.55f + countFrac * 0.44f, extractedTotalBytes, estimateDenom)
                    }
                }
            }
            copyBuf.fill(0)
            onProgress(1.0f, extractedTotalBytes, extractedTotalBytes.coerceAtLeast(1L))
            extractedTotalBytes
        } finally {
            tempPayloadFile.delete()
        }
    }

    private suspend fun packPortableSevenZip(
        workspace: WorkspaceConfig,
        sourceDirectory: File,
        backend: VaultBackend,
        vaultKey: ByteArray,
        argon2Salt: ByteArray,
        wrappedKey: ByteArray,
        onProgress: (Float, Long, Long) -> Unit,
    ): Long {
        val sevenZipExe = SevenZipDetector.ensureAvailable()
        val hexPassword = vaultKey.toHexString()
        val localVaultFile = (backend as? dev.legendsayantan.backstage.core.backend.LocalPathBackend)?.resolveFile(workspace.vaultFileName)
        val isDirectLocal = localVaultFile != null
        val tempArchive = if (localVaultFile != null) {
            localVaultFile.parentFile?.mkdirs()
            File(localVaultFile.parentFile, "${localVaultFile.name}.tmp")
        } else {
            File.createTempFile("backstage_portable_", ".7z")
        }
        tempArchive.delete() // 7z creates a new archive at this path
        val totalSize = CapacityValidator.directorySizeBytes(sourceDirectory).coerceAtLeast(1L)

        try {
            val isEncrypted = workspace.vaultFormat.isEncrypted
            val cmd = mutableListOf(
                sevenZipExe.absolutePath,
                "a",
                "-t7z",
                "-y",
                "-bso0",
                "-bsp1",
                "-mx=5",
            ).apply {
                if (isEncrypted) {
                    add("-mhe=on")
                    add("-p$hexPassword")
                }
                add("-xr!${FolderLock.LOCK_FILE_NAME}")
                add("-xr!backstage.bat")
                add("-xr!backstage.sh")
                add(tempArchive.absolutePath)
                add(".")
            }
            val proc = ProcessBuilder(cmd)
                .directory(sourceDirectory)
                .redirectErrorStream(true)
                .start()
            runCatching { proc.outputStream.close() }

            // Drain process output concurrently and parse 7z percentage progress (-bsp1)
            val outputText = StringBuilder()
            val pctRegex = Regex("""(\d{1,3})%""")
            val readerThread = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().use { reader ->
                        val tokenBuf = StringBuilder()
                        while (true) {
                            val ch = reader.read()
                            if (ch < 0) break
                            val c = ch.toChar()
                            if (outputText.length < 16_384) outputText.append(c)
                            if (c == '\r' || c == '\n' || c == '\b' || c == '%') {
                                if (c == '%') tokenBuf.append(c)
                                val match = pctRegex.find(tokenBuf)
                                if (match != null) {
                                    val pct = match.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: 0
                                    val frac = if (isDirectLocal) (pct / 100f) else (pct / 100f) * 0.82f
                                    val doneBytes = if (isDirectLocal) ((totalSize * pct) / 100L) else ((totalSize * pct * 82L) / 10000L)
                                    onProgress(frac, doneBytes.coerceIn(0L, totalSize), totalSize)
                                }
                                tokenBuf.clear()
                            } else {
                                tokenBuf.append(c)
                            }
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            val finished = proc.waitFor(10, TimeUnit.MINUTES)
            if (!finished) {
                proc.destroyForcibly()
                throw BackStageException(BackStageError.BackendIOError("7z pack timed out"))
            }
            readerThread.join(2_000)
            if (proc.exitValue() != 0) {
                throw BackStageException(BackStageError.BackendIOError("7z pack failed: $outputText"))
            }

            if (localVaultFile != null) {
                if (tempArchive.isFile) {
                    dev.legendsayantan.backstage.core.backend.LocalPathBackend.atomicMoveWithFallback(tempArchive, localVaultFile)
                } else {
                    localVaultFile.writeBytes(ByteArray(0))
                }
            } else {
                if (tempArchive.isFile) {
                    val archiveLen = tempArchive.length().coerceAtLeast(1L)
                    var copied = 0L
                    backend.openWriteStream(workspace.vaultFileName).use { outSink ->
                        val ioBuf = kotlinx.io.Buffer()
                        java.io.FileInputStream(tempArchive).use { fis ->
                            val buf = ByteArray(4 * 1024 * 1024)
                            while (true) {
                                val read = fis.read(buf)
                                if (read <= 0) break
                                ioBuf.write(buf, 0, read)
                                outSink.write(ioBuf, ioBuf.size)
                                copied += read
                                val writeFrac = (copied.toFloat() / archiveLen.toFloat()).coerceIn(0f, 1f)
                                val currentDone = ((totalSize * 0.82f) + (totalSize * 0.18f * writeFrac)).toLong().coerceIn(0L, totalSize)
                                onProgress(0.82f + writeFrac * 0.17f, currentDone, totalSize)
                            }
                        }
                        outSink.flush()
                    }
                } else {
                    backend.writeAllBytes(workspace.vaultFileName, ByteArray(0))
                }
            }

            if (isEncrypted) {
                val macCover = argon2Salt + wrappedKey
                val headerMac = Blake3.keyedHash(macCover, vaultKey)
                val sidecar = BsmetaSidecar(
                    workspaceId = workspace.id.value,
                    displayName = workspace.displayName,
                    platformOrigin = workspace.platformOrigin,
                    vaultFormat = VaultFormatConfig.Portable.fileExtension,
                    archiveFilename = workspace.vaultFileName,
                    passwordHint = (workspace.keyMode as? WorkspaceKeyMode.PasswordBacked)?.passwordHint,
                    headerMacHex = headerMac.toHexString(),
                    kdf = BsmetaKdfInfo(
                        saltHex = argon2Salt.toHexString(),
                        wrappedKeyHex = wrappedKey.toHexString(),
                    ),
                )
                backend.writeAllBytes(
                    BsmetaSidecar.sidecarFileName(workspace.vaultFileName),
                    BsmetaSidecar.encode(sidecar).encodeToByteArray(),
                )
            } else {
                val sidecar = BsmetaSidecar(
                    workspaceId = workspace.id.value,
                    displayName = workspace.displayName,
                    platformOrigin = workspace.platformOrigin,
                    vaultFormat = "plain7z",
                    archiveFilename = workspace.vaultFileName,
                    passwordHint = null,
                    headerMacHex = null,
                    kdf = null,
                    manualRecovery = BsmetaManualRecovery(
                        steps = listOf(
                            "This is a standard unencrypted 7-Zip archive.",
                            "Extract directly using 7-Zip, Peazip, or any standard decompression tool: 7z x ${workspace.vaultFileName}"
                        )
                    ),
                )
                backend.writeAllBytes(
                    BsmetaSidecar.sidecarFileName(workspace.vaultFileName),
                    BsmetaSidecar.encode(sidecar).encodeToByteArray(),
                )
            }

            val actualTotalSize = CapacityValidator.directorySizeBytes(sourceDirectory)
            onProgress(1.0f, actualTotalSize, actualTotalSize.coerceAtLeast(1L))
            return actualTotalSize
        } finally {
            if (!isDirectLocal) {
                tempArchive.delete()
            } else if (tempArchive.exists()) {
                tempArchive.delete()
            }
        }
    }

    private suspend fun unpackPortableSevenZip(
        workspace: WorkspaceConfig,
        targetDirectory: File,
        backend: VaultBackend,
        vaultKey: ByteArray,
        onProgress: (Float, Long, Long) -> Unit,
    ): Long {
        val sevenZipExe = SevenZipDetector.ensureAvailable()
        val hexPassword = vaultKey.toHexString()
        val localVaultFile = (backend as? dev.legendsayantan.backstage.core.backend.LocalPathBackend)?.resolveFile(workspace.vaultFileName)
        val archiveToExtract = if (localVaultFile != null && localVaultFile.isFile) {
            localVaultFile
        } else {
            File.createTempFile("backstage_portable_extract_", ".7z")
        }
        val isDirectLocal = archiveToExtract == localVaultFile
        val vaultSize = runCatching { backend.sizeBytes(workspace.vaultFileName) }.getOrDefault(0L).coerceAtLeast(1L)
        val estimatedTotal = (workspace.uncompressedSizeBytes ?: vaultSize).coerceAtLeast(1L)

        try {
            if (!isDirectLocal) {
                var readSoFar = 0L
                backend.openReadStream(workspace.vaultFileName).use { source ->
                    java.io.FileOutputStream(archiveToExtract).buffered().use { fos ->
                        val ioBuf = kotlinx.io.Buffer()
                        val buf = ByteArray(4 * 1024 * 1024)
                        while (true) {
                            val readLong = source.readAtMostTo(ioBuf, 4 * 1024 * 1024L)
                            if (readLong <= 0L) break
                            while (ioBuf.size > 0L) {
                                val chunkLen = minOf(buf.size.toLong(), ioBuf.size).toInt()
                                val n = ioBuf.readAtMostTo(buf, 0, chunkLen)
                                if (n <= 0) break
                                fos.write(buf, 0, n)
                                readSoFar += n
                                val readFrac = (readSoFar.toFloat() / vaultSize.toFloat()).coerceIn(0f, 1f)
                                onProgress(readFrac * 0.25f, ((estimatedTotal * readFrac) * 0.25f).toLong(), estimatedTotal)
                            }
                        }
                    }
                }
            }
            targetDirectory.mkdirs()
            if (archiveToExtract.length() == 0L) {
                onProgress(1.0f, 0L, 0L)
                return 0L
            }

            val isEncrypted = workspace.vaultFormat.isEncrypted
            val cmd = mutableListOf(
                sevenZipExe.absolutePath,
                "x",
                "-y",
                "-bso0",
                "-bsp1",
            ).apply {
                if (isEncrypted) {
                    add("-p$hexPassword")
                }
                add("-o${targetDirectory.absolutePath}")
                add(archiveToExtract.absolutePath)
            }
            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .start()
            runCatching { proc.outputStream.close() }

            val outputText = StringBuilder()
            val pctRegex = Regex("""(\d{1,3})%""")
            val readerThread = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().use { reader ->
                        val tokenBuf = StringBuilder()
                        while (true) {
                            val ch = reader.read()
                            if (ch < 0) break
                            val c = ch.toChar()
                            if (outputText.length < 16_384) outputText.append(c)
                            if (c == '\r' || c == '\n' || c == '\b' || c == '%') {
                                if (c == '%') tokenBuf.append(c)
                                val match = pctRegex.find(tokenBuf)
                                if (match != null) {
                                    val pct = match.groupValues[1].toIntOrNull()?.coerceIn(0, 100) ?: 0
                                    val frac = if (isDirectLocal) (pct / 100f) else (0.25f + (pct / 100f) * 0.74f)
                                    val doneBytes = if (isDirectLocal) ((estimatedTotal * pct) / 100L) else ((estimatedTotal * frac)).toLong()
                                    onProgress(frac, doneBytes.coerceIn(0L, estimatedTotal), estimatedTotal)
                                }
                                tokenBuf.clear()
                            } else {
                                tokenBuf.append(c)
                            }
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            val finished = proc.waitFor(10, TimeUnit.MINUTES)
            if (!finished) {
                proc.destroyForcibly()
                throw BackStageException(BackStageError.BackendIOError("7z extraction timed out"))
            }
            readerThread.join(2_000)
            if (proc.exitValue() != 0) {
                if (isEncrypted) {
                    throw BackStageException(BackStageError.WrongPassword, "7z extraction failed: $outputText")
                } else {
                    throw BackStageException(BackStageError.BackendIOError("7z extraction failed: $outputText"))
                }
            }

            val totalSize = CapacityValidator.directorySizeBytes(targetDirectory)
            onProgress(1.0f, totalSize, totalSize.coerceAtLeast(1L))
            return totalSize
        } finally {
            if (!isDirectLocal) {
                archiveToExtract.delete()
            }
        }
    }

    /**
     * Resolves [relPath] inside [targetRoot], throwing [BackStageException] if the resolved
     * path escapes [targetRoot] or traverses through an intermediate symlink (Zip-Slip / symlink escape protection).
     */
    private fun safeResolve(targetRoot: java.nio.file.Path, relPath: String): java.io.File {
        val normalized = targetRoot.resolve(relPath.replace('/', java.io.File.separatorChar)).normalize()
        if (!normalized.startsWith(targetRoot) || normalized == targetRoot) {
            throw BackStageException(
                BackStageError.BackendIOError("Path traversal attempt blocked: $relPath escapes workspace root")
            )
        }
        // Ensure no ancestor between targetRoot and normalized is a symbolic link
        var parent: java.nio.file.Path? = normalized.parent
        while (parent != null && parent != targetRoot && parent.startsWith(targetRoot)) {
            if (Files.isSymbolicLink(parent)) {
                throw BackStageException(
                    BackStageError.BackendIOError("Symlink traversal blocked: $relPath traverses through symlink $parent")
                )
            }
            parent = parent.parent
        }
        return normalized.toFile()
    }

    /**
     * Returns true if the given directory resides on a case-insensitive filesystem
     * (Windows NTFS, macOS APFS default, Android shared storage, or mounted FAT/exFAT/NTFS on Linux).
     */
    private fun isDirectoryCaseInsensitive(dir: java.io.File): Boolean {
        if (currentPlatform() == Platform.WINDOWS) return true
        dir.mkdirs()
        val probe = java.io.File(dir, ".bstage_case_probe_${System.nanoTime()}a")
        return try {
            if (probe.createNewFile()) {
                java.io.File(dir, probe.name.uppercase()).exists()
            } else {
                currentPlatform() == Platform.MACOS
            }
        } catch (_: Throwable) {
            currentPlatform() == Platform.MACOS
        } finally {
            probe.delete()
        }
    }

    private fun compressChunk(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val zstdAvailable = runCatching { ZstdUtils.isZstdCompressionAvailable() }.getOrDefault(false)
        if (zstdAvailable) {
            out.write(1) // 1 = Zstd
            ZstdCompressorOutputStream(out).use { it.write(raw) }
        } else {
            out.write(0) // 0 = Deflate fallback
            DeflaterOutputStream(out).use { it.write(raw) }
        }
        return out.toByteArray()
    }

    private fun decompressChunk(compressed: ByteArray, expectedLength: Int): ByteArray {
        if (compressed.isEmpty()) return ByteArray(0)
        val codecFlag = compressed[0].toInt()
        val payloadStream = ByteArrayInputStream(compressed, 1, compressed.size - 1)
        return if (codecFlag == 1) {
            ZstdCompressorInputStream(payloadStream).use { it.readBytes() }
        } else {
            InflaterInputStream(payloadStream).use { it.readBytes() }
        }
    }

    private fun readPosixModeOrZero(path: Path): Int = runCatching {
        val perms = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
        var mode = 0
        if (PosixFilePermission.OWNER_READ in perms) mode = mode or 0b100_000_000
        if (PosixFilePermission.OWNER_WRITE in perms) mode = mode or 0b010_000_000
        if (PosixFilePermission.OWNER_EXECUTE in perms) mode = mode or 0b001_000_000
        if (PosixFilePermission.GROUP_READ in perms) mode = mode or 0b000_100_000
        if (PosixFilePermission.GROUP_WRITE in perms) mode = mode or 0b000_010_000
        if (PosixFilePermission.GROUP_EXECUTE in perms) mode = mode or 0b000_001_000
        if (PosixFilePermission.OTHERS_READ in perms) mode = mode or 0b000_000_100
        if (PosixFilePermission.OTHERS_WRITE in perms) mode = mode or 0b000_000_010
        if (PosixFilePermission.OTHERS_EXECUTE in perms) mode = mode or 0b000_000_001
        mode
    }.getOrDefault(0)

    private fun applyPlatformAttributes(attr: FileAttrEntry, path: Path, platform: Platform) {
        runCatching {
            path.toFile().setLastModified(attr.modifiedAtEpochMillis)
        }
        if (platform == Platform.LINUX || platform == Platform.MACOS) {
            runCatching {
                if (attr.unixMode != 0) {
                    val perms = mutableSetOf<PosixFilePermission>()
                    if ((attr.unixMode and 0b100_000_000) != 0) perms.add(PosixFilePermission.OWNER_READ)
                    if ((attr.unixMode and 0b010_000_000) != 0) perms.add(PosixFilePermission.OWNER_WRITE)
                    if ((attr.unixMode and 0b001_000_000) != 0) perms.add(PosixFilePermission.OWNER_EXECUTE)
                    if ((attr.unixMode and 0b000_100_000) != 0) perms.add(PosixFilePermission.GROUP_READ)
                    if ((attr.unixMode and 0b000_010_000) != 0) perms.add(PosixFilePermission.GROUP_WRITE)
                    if ((attr.unixMode and 0b000_001_000) != 0) perms.add(PosixFilePermission.GROUP_EXECUTE)
                    if ((attr.unixMode and 0b000_000_100) != 0) perms.add(PosixFilePermission.OTHERS_READ)
                    if ((attr.unixMode and 0b000_000_010) != 0) perms.add(PosixFilePermission.OTHERS_WRITE)
                    if ((attr.unixMode and 0b000_000_001) != 0) perms.add(PosixFilePermission.OTHERS_EXECUTE)
                    Files.setPosixFilePermissions(path, perms)
                } else if (attr.isExecutable) {
                    path.toFile().setExecutable(true, false)
                }
            }
        }
    }
}
