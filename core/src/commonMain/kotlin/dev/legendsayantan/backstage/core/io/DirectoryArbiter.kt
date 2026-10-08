package dev.legendsayantan.backstage.core.io

import dev.legendsayantan.backstage.core.auth.DeviceCredentialSession
import dev.legendsayantan.backstage.core.backend.LocalPathBackend
import dev.legendsayantan.backstage.core.backend.VaultBackend
import dev.legendsayantan.backstage.core.backend.VaultBackendFactory
import dev.legendsayantan.backstage.core.crypto.BsmetaSidecar
import dev.legendsayantan.backstage.core.crypto.KeyProvider
import dev.legendsayantan.backstage.core.crypto.PasswordVerifier
import dev.legendsayantan.backstage.core.crypto.VaultFormatConfig
import dev.legendsayantan.backstage.core.crypto.VaultKeyState
import dev.legendsayantan.backstage.core.crypto.WorkspaceKeyMode
import dev.legendsayantan.backstage.core.error.BackStageError
import dev.legendsayantan.backstage.core.error.BackStageException
import dev.legendsayantan.backstage.core.platform.PlatformContext
import dev.legendsayantan.backstage.core.workspace.AppConfigRepository
import dev.legendsayantan.backstage.core.workspace.AuditEventType
import dev.legendsayantan.backstage.core.workspace.AuditLogRepository
import dev.legendsayantan.backstage.core.workspace.WorkspaceConfig
import dev.legendsayantan.backstage.core.workspace.WorkspaceId
import dev.legendsayantan.backstage.core.workspace.WorkspaceOperation
import dev.legendsayantan.backstage.core.workspace.WorkspaceRepository
import dev.legendsayantan.backstage.core.workspace.WorkspaceState
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchestrates atomic workspace loading, delta-aware eviction, graceful directory handoffs,
 * LRU auto-unload arbitration, and staged encrypted prefetch restoration.
 *
 * Enforces invariant: Layer 1 [DeviceCredentialSession] MUST be active for all user-initiated
 * load/evict operations.
 */
class DirectoryArbiter(
    private val context: PlatformContext,
    private val workspaceRepository: WorkspaceRepository,
    private val appConfigRepository: AppConfigRepository,
    private val auditLogRepository: AuditLogRepository,
    private val keyProvider: KeyProvider,
    private val passwordVerifier: PasswordVerifier = PasswordVerifier(keyProvider),
    private val ioStreamer: ParallelIoStreamer = ParallelIoStreamer(),
    private val openHandleChecker: OpenHandleChecker = OpenHandleChecker(),
) {
    private val _states = MutableStateFlow<Map<WorkspaceId, WorkspaceState>>(emptyMap())
    val states: StateFlow<Map<WorkspaceId, WorkspaceState>> = _states.asStateFlow()

    val stagingDir: File
        get() = File(context.resolveAppDataDir(), "stage").apply { mkdirs() }

    /** Per-workspace mutex to prevent concurrent load/evict operations on the same workspace. */
    private val workspaceLocks = ConcurrentHashMap<String, Mutex>()
    private fun lockFor(id: WorkspaceId): Mutex = workspaceLocks.getOrPut(id.value) { Mutex() }

    /**
     * Refreshes the runtime [WorkspaceState] of all known workspaces by inspecting
     * local `.bstage_lock` tokens, staged prefetch files, and vault backends.
     */
    suspend fun refreshStates() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val configs = workspaceRepository.findAll()
        val updated = linkedMapOf<WorkspaceId, WorkspaceState>()
        for (ws in configs) {
            val current = _states.value[ws.id]
            if (current is WorkspaceState.Busy) {
                updated[ws.id] = current
                continue
            }
            updated[ws.id] = detectWorkspaceState(ws)
        }
        _states.value = updated
    }

    suspend fun detectWorkspaceState(
        workspace: WorkspaceConfig,
        backendOverride: VaultBackend? = null,
    ): WorkspaceState = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val localDir = File(workspace.resolvedLocalPath())
        val lock = FolderLock.readLock(localDir)
        if (lock != null && lock.workspaceId == workspace.id.value) {
            return@withContext WorkspaceState.Active
        }
        val stagedFile = File(stagingDir, workspace.vaultFileName)
        if (stagedFile.isFile) {
            return@withContext WorkspaceState.StagedEncrypted
        }
        val backend = runCatching { backendOverride ?: VaultBackendFactory.create(workspace.vaultBackend) }.getOrNull()
            ?: return@withContext WorkspaceState.Missing
        if (backend.exists(workspace.vaultFileName)) {
            WorkspaceState.Vaulted
        } else {
            WorkspaceState.Missing
        }
    }

    /**
     * Loads [workspaceId] into its local target directory.
     *
     * @param passwordCallback Invoked if the workspace requires a password (new device, rotated key,
     *                         or ManualOnEveryUse). Receives the [VaultKeyState.NeedsPassword] state
     *                         and returns the user-entered password (or null if cancelled).
     */
    suspend fun loadWorkspace(
        workspaceId: WorkspaceId,
        forceIgnoreHandles: Boolean = false,
        backendOverride: VaultBackend? = null,
        availableBytesOverride: Long? = null,
        passwordCallback: (suspend (VaultKeyState.NeedsPassword) -> String?)? = null,
    ): Unit = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // Invariant 1: Mandatory Layer 1 Device Credential Session
        DeviceCredentialSession.requireActive()

        val workspace = workspaceRepository.findById(workspaceId)
            ?: throw IllegalArgumentException("Unknown workspace: ${workspaceId.value}")

        try {
            lockFor(workspaceId).withLock {
            // Check if a staged encrypted prefetch exists locally and verify its BLAKE3 integrity
            val stagedVaultFile = File(stagingDir, workspace.vaultFileName)
            val stagedMacFile = File(stagingDir, "${workspace.vaultFileName}.bsstage_mac")
            val useStagedFile = if (stagedVaultFile.isFile) {
                val validStaged = if (stagedMacFile.isFile) {
                    val expectedHex = runCatching { stagedMacFile.readText().trim() }.getOrDefault("")
                    val actualHex = runCatching {
                        dev.legendsayantan.backstage.core.crypto.Blake3.hashFile(stagedVaultFile)
                            .let { bytes -> bytes.joinToString("") { "%02x".format(it) } }
                    }.getOrDefault("")
                    expectedHex.isNotEmpty() && expectedHex.equals(actualHex, ignoreCase = true)
                } else {
                    false
                }
                if (!validStaged) {
                    // Corrupted or incomplete staged file -> purge and fall back to remote/cold backend
                    stagedVaultFile.delete()
                    stagedMacFile.delete()
                    File(stagingDir, BsmetaSidecar.sidecarFileName(workspace.vaultFileName)).delete()
                }
                validStaged
            } else {
                false
            }

            val effectiveBackend: VaultBackend = if (useStagedFile) {
                LocalPathBackend(stagingDir, displayName = "Local Prefetch Stage")
            } else {
                backendOverride ?: VaultBackendFactory.create(workspace.vaultBackend)
            }

            if (!effectiveBackend.exists(workspace.vaultFileName)) {
                throw BackStageException(
                    BackStageError.VaultNotFound(workspace.vaultBackend, workspace.vaultFileName)
                )
            }

            // State 1: KEY_CHECK
            setBusy(workspaceId, WorkspaceOperation.KEY_CHECK, 0.05f, "Checking workspace credentials...")
            val keyState = passwordVerifier.assess(workspace, effectiveBackend)
            val vaultKey: ByteArray = when (keyState) {
                is VaultKeyState.Ready -> keyState.vaultKey
                is VaultKeyState.NeedsPassword -> {
                    setBusy(workspaceId, WorkspaceOperation.PASSWORD_PROMPT, 0.10f, "Waiting for workspace password...")
                    val password = passwordCallback?.invoke(keyState)
                        ?: throw BackStageException(
                            BackStageError.PasswordRequired("Password entry cancelled or unavailable for '${workspace.displayName}'")
                        )

                    setBusy(workspaceId, WorkspaceOperation.KEY_DERIVATION, 0.15f, "Verifying password (Argon2id)...")
                    val unwrapped = passwordVerifier.verify(password, keyState)
                        ?: run {
                            auditLogRepository.record(workspaceId, AuditEventType.ERROR, """{"error":"WrongPassword"}""")
                            throw BackStageException(BackStageError.WrongPassword)
                        }

                    // Save verified vault_key to OS keystore if workspace is PasswordBacked
                    if (workspace.keyMode is WorkspaceKeyMode.PasswordBacked) {
                        passwordVerifier.saveToKeystore(workspace.keyMode.keystoreAlias, unwrapped)
                        auditLogRepository.record(workspaceId, AuditEventType.NEW_DEVICE_UNLOCK)
                    }
                    unwrapped
                }
            }

            // State 2: VALIDATING & Graceful Directory Handoff check
            setBusy(workspaceId, WorkspaceOperation.VALIDATING, 0.20f, "Validating local capacity and target directory...")
            val targetDir = File(workspace.resolvedLocalPath())
            val existingLock = FolderLock.readLock(targetDir)
            if (existingLock != null && existingLock.workspaceId != workspaceId.value) {
                // Target directory is occupied by another workspace -> Graceful Directory Handoff!
                val occupantId = WorkspaceId(existingLock.workspaceId)
                val occupant = workspaceRepository.findById(occupantId)
                if (occupant != null) {
                    setBusy(
                        workspaceId,
                        WorkspaceOperation.EVICTING,
                        0.25f,
                        "Graceful Handoff: Evicting occupant '${occupant.displayName}'..."
                    )
                    evictWorkspace(
                        workspaceId = occupant.id,
                        forceIgnoreHandles = forceIgnoreHandles,
                        passwordCallback = passwordCallback,
                    )
                } else {
                    forceDeleteRecursively(targetDir)
                }
            }

            // State 3: Capacity Validation & Auto-Unload Arbitration
            val lruWorkspaces = workspaceRepository.findLeastRecentlyActive()
            val capacityResult = CapacityValidator.validateLocalCapacity(
                targetWorkspace = workspace,
                backend = effectiveBackend,
                allWorkspacesOrderedByLru = lruWorkspaces,
                availableBytesOverride = availableBytesOverride,
            )

            if (!capacityResult.hasEnoughSpace) {
                setBusy(workspaceId, WorkspaceOperation.ARBITRATING, 0.30f, "Arbitrating local SSD space...")
                if (appConfigRepository.autoUnloadEnabled && capacityResult.evictionCandidates.isNotEmpty()) {
                    for (candidate in capacityResult.evictionCandidates) {
                        evictWorkspace(
                            workspaceId = candidate.id,
                            forceIgnoreHandles = forceIgnoreHandles,
                            passwordCallback = passwordCallback,
                        )
                    }
                } else {
                    throw BackStageException(
                        BackStageError.InsufficientSpace(
                            requiredBytes = capacityResult.requiredBytes,
                            availableBytes = capacityResult.availableBytes,
                            candidatesForEviction = capacityResult.evictionCandidates.map { it.id },
                        )
                    )
                }
            }

            // State 4: EXTRACTING
            setBusy(workspaceId, WorkspaceOperation.EXTRACTING, 0.05f, "Decrypting and extracting workspace...")
            val extractedBytes = try {
                ioStreamer.unpackWorkspace(
                    workspace = workspace,
                    targetDirectory = targetDir,
                    backend = effectiveBackend,
                    vaultKey = vaultKey,
                ) { fraction, done, total ->
                    setBusy(
                        id = workspaceId,
                        op = WorkspaceOperation.EXTRACTING,
                        progress = 0.05f + (fraction * 0.85f),
                        message = "Restoring ${workspace.displayName}...",
                        bytesProcessed = done,
                        totalBytes = total,
                    )
                }
            } finally {
                vaultKey.fill(0)
            }

            // Clean up staged prefetch file if we extracted from stagingDir
            if (stagedVaultFile.isFile) {
                stagedVaultFile.delete()
                stagedMacFile.delete()
                File(stagingDir, BsmetaSidecar.sidecarFileName(workspace.vaultFileName)).delete()
            }

            // State 5: ACTIVE — Write .bstage_lock with snapshot & generate contextual launcher script
            FolderLock.writeLock(
                directory = targetDir,
                workspaceId = workspace.id,
                onProgress = { frac, done, total ->
                    setBusy(
                        id = workspaceId,
                        op = WorkspaceOperation.EXTRACTING,
                        progress = 0.90f + (frac * 0.09f),
                        message = "Finalizing ${workspace.displayName}...",
                        bytesProcessed = done,
                        totalBytes = total,
                    )
                }
            )
            LauncherGenerator.writeInFolderLauncher(workspace = workspace, targetDirectory = targetDir)

            val nowSec = System.currentTimeMillis() / 1000L
            workspaceRepository.updateLastActive(workspace.id, nowSec, extractedBytes)
            auditLogRepository.record(workspace.id, AuditEventType.LOAD, """{"bytes":$extractedBytes}""")

            updateState(workspace.id, WorkspaceState.Active)
            } // end withLock
        } catch (e: BackStageException) {
            updateState(workspaceId, WorkspaceState.Error(e.error, e.message))
            throw e
        } catch (e: Exception) {
            val wrapped = BackStageError.BackendIOError(e.message ?: "Unexpected load failure")
            updateState(workspaceId, WorkspaceState.Error(wrapped, e.message ?: "Unexpected load failure"))
            throw BackStageException(wrapped, e.message ?: "Unexpected load failure", e)
        }
    }

    /**
     * Evicts an active workspace from its local directory back to its cold-storage vault,
     * using delta snapshot comparison to skip re-packing when zero files changed, and then
     * purges the local directory.
     */
    suspend fun evictWorkspace(
        workspaceId: WorkspaceId,
        forceIgnoreHandles: Boolean = false,
        backendOverride: VaultBackend? = null,
        passwordOverrideForNewVault: String? = null,
        passwordCallback: (suspend (VaultKeyState.NeedsPassword) -> String?)? = null,
    ): Unit = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // Invariant 1: Mandatory Layer 1 Device Credential Session
        DeviceCredentialSession.requireActive()

        val workspace = workspaceRepository.findById(workspaceId)
            ?: throw IllegalArgumentException("Unknown workspace: ${workspaceId.value}")

        val targetDir = File(workspace.resolvedLocalPath())
        if (!targetDir.isDirectory) {
            updateState(workspaceId, WorkspaceState.Vaulted)
            return@withContext
        }

        try {
            lockFor(workspaceId).withLock {
            // 1. LOCKING — Check for open OS file handles
            setBusy(workspaceId, WorkspaceOperation.LOCKING, 0.04f, "Checking for open file handles...")
            if (!forceIgnoreHandles) {
                val openHandles = openHandleChecker.getOpenHandles(targetDir.absolutePath)
                if (openHandles.isNotEmpty()) {
                    throw BackStageException(BackStageError.DirectoryLocked(openHandles))
                }
            }

            val backend = backendOverride ?: VaultBackendFactory.create(workspace.vaultBackend)
            val lockToken = FolderLock.readLock(targetDir)
            val vaultAlreadyExists = backend.exists(workspace.vaultFileName)

            // 2. Delta Eviction Check: if vault already exists and lockToken snapshot shows zero changes, skip repack!
            val needsRepack = if (vaultAlreadyExists && lockToken != null && lockToken.snapshot.isNotEmpty()) {
                val diff = FolderLock.computeDiff(lockToken.snapshot, targetDir) { frac, done, total ->
                    setBusy(
                        id = workspaceId,
                        op = WorkspaceOperation.VALIDATING,
                        progress = 0.05f + (frac * 0.10f),
                        message = "Scanning workspace for changes...",
                        bytesProcessed = done,
                        totalBytes = total,
                    )
                }
                diff.hasChanges
            } else {
                true
            }

            var uncompressedBytes = workspace.uncompressedSizeBytes ?: CapacityValidator.directorySizeBytes(targetDir)

            if (needsRepack) {
                setBusy(workspaceId, WorkspaceOperation.EVICTING, 0.15f, "Compressing and encrypting workspace to vault...")
                val (vaultKey, salt, wrappedKey) = resolveKeyMaterialForPack(
                    workspace = workspace,
                    backend = backend,
                    vaultAlreadyExists = vaultAlreadyExists,
                    passwordOverrideForNewVault = passwordOverrideForNewVault,
                    passwordCallback = passwordCallback,
                )

                try {
                    uncompressedBytes = ioStreamer.packWorkspace(
                        workspace = workspace,
                        sourceDirectory = targetDir,
                        backend = backend,
                        vaultKey = vaultKey,
                        argon2Salt = salt,
                        wrappedKey = wrappedKey,
                    ) { fraction, done, total ->
                        setBusy(
                            id = workspaceId,
                            op = WorkspaceOperation.EVICTING,
                            progress = 0.15f + (fraction * 0.75f),
                            message = "Archiving ${workspace.displayName}...",
                            bytesProcessed = done,
                            totalBytes = total,
                        )
                    }
                    // Purge any stale staged copy since cold vault was updated
                    purgeStagedFiles(workspace.vaultFileName)
                } finally {
                    vaultKey.fill(0)
                }
            }

            // 3. PURGING — Remove local workspace directory
            setBusy(workspaceId, WorkspaceOperation.PURGING, 0.90f, "Cleaning up local directory...")
            forceDeleteRecursively(targetDir) { frac ->
                setBusy(
                    id = workspaceId,
                    op = WorkspaceOperation.PURGING,
                    progress = 0.90f + (frac * 0.09f),
                    message = "Cleaning up local directory...",
                    bytesProcessed = uncompressedBytes,
                    totalBytes = uncompressedBytes,
                )
            }

            val nowSec = System.currentTimeMillis() / 1000L
            workspaceRepository.updateLastEvicted(workspace.id, nowSec, uncompressedBytes)
            auditLogRepository.record(
                workspace.id,
                AuditEventType.EVICT,
                """{"repacked":$needsRepack,"bytes":$uncompressedBytes}"""
            )

            updateState(workspace.id, WorkspaceState.Vaulted)
            } // end withLock
        } catch (e: BackStageException) {
            updateState(workspaceId, WorkspaceState.Error(e.error, e.message))
            throw e
        }
    }

    /**
     * Stages an encrypted vault locally into `{appDataDir}/stage/` by streaming in 64 KiB blocks
     * without loading the entire vault into RAM, and writes a `.bsstage_mac` BLAKE3 digest file
     * so [loadWorkspace] can verify staged integrity before extraction.
     */
    suspend fun stageEncryptedPrefetch(
        workspaceId: WorkspaceId,
        backendOverride: VaultBackend? = null,
    ): File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val workspace = workspaceRepository.findById(workspaceId)
            ?: throw IllegalArgumentException("Unknown workspace: ${workspaceId.value}")

        val backend = backendOverride ?: VaultBackendFactory.create(workspace.vaultBackend)
        if (!backend.exists(workspace.vaultFileName)) {
            throw BackStageException(BackStageError.VaultNotFound(workspace.vaultBackend, workspace.vaultFileName))
        }

        val totalVaultBytes = runCatching { backend.sizeBytes(workspace.vaultFileName) }.getOrDefault(0L).coerceAtLeast(1L)
        val stagedVault = File(stagingDir, workspace.vaultFileName)
        val tempStaged = File(stagingDir, "${workspace.vaultFileName}.part")
        val stagedMacFile = File(stagingDir, "${workspace.vaultFileName}.bsstage_mac")
        val hasher = dev.legendsayantan.backstage.core.crypto.Blake3.StreamingHasher()

        try {
            setBusy(workspaceId, WorkspaceOperation.EXTRACTING, 0.02f, "Staging vault locally...")
            var stagedBytesSoFar = 0L
            backend.openReadStream(workspace.vaultFileName).use { source ->
                java.io.FileOutputStream(tempStaged).buffered().use { fos ->
                    val ioBuf = kotlinx.io.Buffer()
                    val byteBuf = ByteArray(65_536)
                    while (true) {
                        val readLong = source.readAtMostTo(ioBuf, 65_536L)
                        if (readLong <= 0L) break
                        while (ioBuf.size > 0L) {
                            val chunkLen = minOf(byteBuf.size.toLong(), ioBuf.size).toInt()
                            val n = ioBuf.readAtMostTo(byteBuf, 0, chunkLen)
                            if (n <= 0) break
                            hasher.update(byteBuf, 0, n)
                            fos.write(byteBuf, 0, n)
                            stagedBytesSoFar += n
                            val frac = (stagedBytesSoFar.toFloat() / totalVaultBytes.toFloat()).coerceIn(0f, 0.98f)
                            setBusy(
                                id = workspaceId,
                                op = WorkspaceOperation.EXTRACTING,
                                progress = frac,
                                message = "Staging ${workspace.displayName}...",
                                bytesProcessed = stagedBytesSoFar,
                                totalBytes = totalVaultBytes,
                            )
                        }
                    }
                }
            }
            val digestHex = hasher.finish().joinToString("") { "%02x".format(it) }
            if (stagedVault.exists()) stagedVault.delete()
            tempStaged.renameTo(stagedVault)
            stagedMacFile.writeText(digestHex)
        } finally {
            if (tempStaged.exists()) tempStaged.delete()
        }

        val sidecarName = BsmetaSidecar.sidecarFileName(workspace.vaultFileName)
        if (backend.exists(sidecarName)) {
            File(stagingDir, sidecarName).writeBytes(backend.readAllBytes(sidecarName))
        }

        updateState(workspaceId, WorkspaceState.StagedEncrypted)
        stagedVault
    }

    /**
     * Imports an existing `.bstage`, `.7z`, or `.bvault` archive from disk into the BackStage database.
     * Reads `.bsmeta` sidecar (if present) and/or the 182-byte `TinyHeader` to recover workspace ID,
     * origin platform, format, and password hint.
     */
    suspend fun importExistingVault(
        vaultFile: File,
        localTargetDirectory: File,
        displayNameOverride: String? = null,
    ): WorkspaceConfig = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        DeviceCredentialSession.requireActive()
        if (!vaultFile.isFile) {
            throw BackStageException(
                BackStageError.VaultNotFound(
                    dev.legendsayantan.backstage.core.workspace.VaultBackendConfig.LocalPath(vaultFile.parent ?: "."),
                    vaultFile.name
                )
            )
        }

        val parentDir = vaultFile.parentFile ?: File(".")
        val backend = LocalPathBackend(parentDir)
        val sidecarFile = File(parentDir, BsmetaSidecar.sidecarFileName(vaultFile.name))
        val sidecar = if (sidecarFile.isFile) {
            runCatching { BsmetaSidecar.decode(sidecarFile.readText()) }.getOrNull()
        } else {
            null
        }

        val ext = vaultFile.extension.lowercase()
        val format = when (ext) {
            "bstage" -> VaultFormatConfig.Native
            "7z" -> VaultFormatConfig.Portable
            "bvault" -> VaultFormatConfig.PlainCompressed
            else -> {
                val magic = backend.readBytes(vaultFile.name, 0L, 4)
                if (magic.contentEquals(dev.legendsayantan.backstage.core.crypto.TinyHeader.MAGIC_BVAULT)) {
                    VaultFormatConfig.PlainCompressed
                } else {
                    VaultFormatConfig.Native
                }
            }
        }

        val tinyHeader = if (format != VaultFormatConfig.Portable && vaultFile.length() >= dev.legendsayantan.backstage.core.crypto.TinyHeader.TINY_HEADER_BYTES) {
            runCatching {
                dev.legendsayantan.backstage.core.crypto.TinyHeader.parse(
                    backend.readBytes(vaultFile.name, 0L, dev.legendsayantan.backstage.core.crypto.TinyHeader.TINY_HEADER_BYTES)
                )
            }.getOrNull()
        } else {
            null
        }

        val rawWsId = sidecar?.workspaceId?.takeIf { it.isNotBlank() }
            ?: tinyHeader?.workspaceId?.takeIf { it.isNotBlank() }
            ?: vaultFile.nameWithoutExtension
        // B-6: Normalise to ASCII ≤ 26 bytes so it fits the TinyHeader WORKSPACE_ID field
        val wsId = if (rawWsId.encodeToByteArray().size <= WorkspaceId.MAX_BYTE_LENGTH &&
                       rawWsId.all { it.code in 32..126 }) {
            WorkspaceId(rawWsId)
        } else {
            WorkspaceId.fromArbitraryString(rawWsId)
        }

        val resolvedName = displayNameOverride?.takeIf { it.isNotBlank() }
            ?: sidecar?.displayName?.takeIf { it.isNotBlank() }
            ?: vaultFile.nameWithoutExtension

        val origin = sidecar?.platformOrigin ?: tinyHeader?.platformOrigin ?: dev.legendsayantan.backstage.core.platform.currentPlatform()
        val createdAt = tinyHeader?.createdAtEpochSeconds ?: (System.currentTimeMillis() / 1000L)

        val keyMode = if (format.isEncrypted) {
            WorkspaceKeyMode.PasswordBacked(
                keystoreAlias = WorkspaceConfig.defaultKeystoreAlias(wsId),
                passwordHint = sidecar?.passwordHint,
            )
        } else {
            null
        }

        val config = WorkspaceConfig(
            id = wsId,
            displayName = resolvedName,
            vaultBackend = dev.legendsayantan.backstage.core.workspace.VaultBackendConfig.LocalPath(parentDir.absolutePath),
            vaultFileName = vaultFile.name,
            localTargetPath = localTargetDirectory.absolutePath,
            vaultFormat = format,
            keyMode = keyMode,
            platformOrigin = origin,
            createdAtEpochSeconds = createdAt,
        )

        workspaceRepository.upsert(config)
        updateState(wsId, detectWorkspaceState(config, backend))
        auditLogRepository.record(wsId, AuditEventType.LOAD, """{"imported":"${vaultFile.name}"}""")
        config
    }

    /**
     * Migrates a workspace vault between formats (Native .bstage <-> Portable .7z <-> PlainCompressed .bvault).
     */
    suspend fun migrateVaultFormat(
        workspaceId: WorkspaceId,
        targetFormat: VaultFormatConfig,
        passwordForEncryption: String? = null,
        backendOverride: VaultBackend? = null,
        passwordCallback: (suspend (VaultKeyState.NeedsPassword) -> String?)? = null,
    ): WorkspaceConfig = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        DeviceCredentialSession.requireActive()

        val currentWorkspace = workspaceRepository.findById(workspaceId)
            ?: throw IllegalArgumentException("Unknown workspace: ${workspaceId.value}")

        if (currentWorkspace.vaultFormat == targetFormat) return@withContext currentWorkspace

        val backend = backendOverride ?: VaultBackendFactory.create(currentWorkspace.vaultBackend)
        val localDir = File(currentWorkspace.resolvedLocalPath())
        val wasActive = FolderLock.readLock(localDir)?.workspaceId == workspaceId.value

        if (!wasActive) {
            loadWorkspace(
                workspaceId = workspaceId,
                backendOverride = backend,
                passwordCallback = passwordCallback,
            )
        }

        val oldVaultFileName = currentWorkspace.vaultFileName
        val baseName = oldVaultFileName.substringBeforeLast('.')
        val newVaultFileName = "$baseName.${targetFormat.fileExtension}"

        val newKeyMode: WorkspaceKeyMode? = if (targetFormat.isEncrypted) {
            currentWorkspace.keyMode ?: WorkspaceKeyMode.PasswordBacked(
                keystoreAlias = WorkspaceConfig.defaultKeystoreAlias(workspaceId)
            )
        } else {
            null
        }

        val updatedConfig = currentWorkspace.copy(
            vaultFormat = targetFormat,
            vaultFileName = newVaultFileName,
            keyMode = newKeyMode,
        )
        // NOTE: DB is updated AFTER successful packing to avoid orphaned config if pack fails

        // Pack into the new format
        val canReuseOldEnvelope = currentWorkspace.vaultFormat.isEncrypted &&
            targetFormat.isEncrypted &&
            passwordForEncryption.isNullOrBlank() &&
            backend.exists(oldVaultFileName)

        val (vaultKey, salt, wrappedKey) = if (canReuseOldEnvelope) {
            resolveKeyMaterialForPack(
                workspace = currentWorkspace,
                backend = backend,
                vaultAlreadyExists = true,
                passwordOverrideForNewVault = null,
                passwordCallback = passwordCallback,
            )
        } else {
            resolveKeyMaterialForPack(
                workspace = updatedConfig,
                backend = backend,
                vaultAlreadyExists = false,
                passwordOverrideForNewVault = passwordForEncryption,
                passwordCallback = passwordCallback,
            )
        }

        try {
            ioStreamer.packWorkspace(
                workspace = updatedConfig,
                sourceDirectory = localDir,
                backend = backend,
                vaultKey = vaultKey,
                argon2Salt = salt,
                wrappedKey = wrappedKey,
            )
        } finally {
            vaultKey.fill(0)
        }

        // Persist the updated config only after packing succeeds
        workspaceRepository.upsert(updatedConfig)

        if (oldVaultFileName != newVaultFileName && backend.exists(oldVaultFileName)) {
            backend.delete(oldVaultFileName)
            backend.delete(BsmetaSidecar.sidecarFileName(oldVaultFileName))
        }
        purgeStagedFiles(oldVaultFileName)
        purgeStagedFiles(newVaultFileName)

        if (!wasActive) {
            forceDeleteRecursively(localDir)
            updateState(workspaceId, WorkspaceState.Vaulted)
        } else {
            updateState(workspaceId, WorkspaceState.Active)
        }

        auditLogRepository.record(
            workspaceId,
            AuditEventType.FORMAT_MIGRATE,
            """{"to":"${targetFormat.fileExtension}"}"""
        )
        updatedConfig
    }

    private suspend fun resolveKeyMaterialForPack(
        workspace: WorkspaceConfig,
        backend: VaultBackend,
        vaultAlreadyExists: Boolean,
        passwordOverrideForNewVault: String?,
        passwordCallback: (suspend (VaultKeyState.NeedsPassword) -> String?)?,
    ): Triple<ByteArray, ByteArray, ByteArray> {
        if (!workspace.vaultFormat.isEncrypted) {
            return Triple(
                ByteArray(32),
                ByteArray(16),
                ByteArray(60),
            )
        }

        val sidecarName = BsmetaSidecar.sidecarFileName(workspace.vaultFileName)
        val hasKeyEnvelope = vaultAlreadyExists || backend.exists(sidecarName)

        if (hasKeyEnvelope) {
            val envelope = passwordVerifier.readKeyEnvelope(workspace, backend)
            val keyState = passwordVerifier.assess(workspace, backend)
            val vaultKey = when (keyState) {
                is VaultKeyState.Ready -> keyState.vaultKey
                is VaultKeyState.NeedsPassword -> {
                    val pwd = passwordOverrideForNewVault
                        ?: passwordCallback?.invoke(keyState)
                        ?: throw BackStageException(
                            BackStageError.PasswordRequired("Password required to evict workspace '${workspace.displayName}'")
                        )
                    passwordVerifier.verify(pwd, keyState)
                        ?: throw BackStageException(BackStageError.WrongPassword)
                }
            }
            return Triple(vaultKey, envelope.argon2Salt, envelope.wrappedKey)
        } else {
            val password = passwordOverrideForNewVault
                ?: passwordCallback?.invoke(
                    VaultKeyState.NeedsPassword(
                        reason = dev.legendsayantan.backstage.core.crypto.PasswordReason.INITIAL_CREATION,
                        hint = (workspace.keyMode as? WorkspaceKeyMode.PasswordBacked)?.passwordHint,
                        argon2Salt = ByteArray(16),
                        wrappedKey = ByteArray(60),
                    )
                )
                ?: throw BackStageException(
                    BackStageError.PasswordRequired(
                        "Initial workspace password is required when creating a new encrypted vault for '${workspace.displayName}'"
                    )
                )
            val init = passwordVerifier.initializeWorkspaceKey(workspace, password)
            return Triple(init.vaultKey, init.argon2Salt, init.wrappedKey)
        }
    }

    private fun purgeStagedFiles(vaultFileName: String) {
        val dir = stagingDir
        File(dir, vaultFileName).delete()
        File(dir, "$vaultFileName.bsstage_mac").delete()
        File(dir, BsmetaSidecar.sidecarFileName(vaultFileName)).delete()
    }

    /** Last timestamp (ms) at which a [WorkspaceState.Busy] state was emitted for each workspace.
     * Used to throttle StateFlow updates from the I/O progress loop (~40 FPS max rate) while
     * immediately letting through any >=1.5% delta or phase transition. */
    private val lastProgressEmitMs = ConcurrentHashMap<String, Long>()
    private val progressThrottleMs = 25L

    private fun setBusy(
        id: WorkspaceId,
        op: WorkspaceOperation,
        progress: Float,
        message: String,
        bytesProcessed: Long = 0L,
        totalBytes: Long = 0L,
    ) {
        val clampedProgress = progress.coerceIn(0f, 1f)
        val existingBusy = _states.value[id] as? WorkspaceState.Busy
        val sameOperation = existingBusy != null && existingBusy.operation == op
        val monotonicProgress = if (sameOperation) {
            maxOf(clampedProgress, existingBusy!!.progressFraction)
        } else {
            clampedProgress
        }
        val monotonicBytes = if (sameOperation) {
            maxOf(bytesProcessed, existingBusy!!.bytesProcessed)
        } else {
            bytesProcessed
        }

        val now = System.currentTimeMillis()
        val lastMs = lastProgressEmitMs[id.value] ?: 0L
        val progressDelta = if (existingBusy != null) kotlin.math.abs(monotonicProgress - existingBusy.progressFraction) else 1f
        val messageChanged = existingBusy?.statusMessage != message
        if (sameOperation && !messageChanged && monotonicProgress < 0.99f && progressDelta < 0.015f && (now - lastMs) < progressThrottleMs) {
            return
        }
        lastProgressEmitMs[id.value] = now
        updateState(
            id,
            WorkspaceState.Busy(
                operation = op,
                progressFraction = monotonicProgress,
                bytesProcessed = monotonicBytes,
                totalBytes = totalBytes,
                statusMessage = message,
            )
        )
    }

    private fun updateState(id: WorkspaceId, state: WorkspaceState) {
        _states.update { current ->
            current + (id to state)
        }
    }

    /**
     * Robustly deletes a directory tree, clearing read-only attributes before deletion
     * (required for Git repositories on Windows) and NEVER entering symlinks/junction points.
     *
     * Retries up to 5 times for transient Windows file locks (e.g. Defender / conhost.exe).
     * If only an empty directory shell remains because an external shell/Explorer has its
     * working directory (`cwd`) inside [dir], treats the purge as successful as long as all
     * regular files and `.bstage_lock` have been removed.
     */
    private fun forceDeleteRecursively(
        dir: java.io.File,
        onProgress: (Float) -> Unit = {},
    ) {
        if (!dir.exists() && !java.nio.file.Files.isSymbolicLink(dir.toPath())) return
        for (attempt in 1..5) {
            val allEntries = dir.walkBottomUp()
                .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .toList()
            val totalCount = allEntries.size.coerceAtLeast(1)
            for ((idx, f) in allEntries.withIndex()) {
                val path = f.toPath()
                if (java.nio.file.Files.isSymbolicLink(path)) {
                    runCatching { java.nio.file.Files.deleteIfExists(path) }
                } else {
                    if (!f.canWrite()) runCatching { f.setWritable(true) }
                    if (!f.delete()) {
                        runCatching { java.nio.file.Files.deleteIfExists(path) }
                    }
                }
                if (attempt == 1 && (idx % 16 == 0 || idx == totalCount - 1)) {
                    onProgress(((idx + 1).toFloat() / totalCount.toFloat()).coerceIn(0f, 1f))
                }
            }
            if (!dir.exists()) return
            val hasRemainingFiles = dir.walkTopDown()
                .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .any { it != dir && (it.isFile || java.nio.file.Files.isSymbolicLink(it.toPath())) }
            if (!hasRemainingFiles) {
                // Only empty directory shell(s) remain (e.g. locked by an open terminal/Explorer cwd on Windows)
                onProgress(1f)
                return
            }
            if (attempt < 5) {
                runCatching { Thread.sleep(75L * attempt) }
            }
        }
        val remainingFile = dir.walkTopDown()
            .onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }
            .firstOrNull { it != dir && (it.isFile || java.nio.file.Files.isSymbolicLink(it.toPath())) }
        if (remainingFile != null) {
            throw BackStageException(
                BackStageError.BackendIOError(
                    "Failed to completely purge local directory: ${dir.absolutePath} (locked file: ${remainingFile.name})"
                )
            )
        }
    }
}
