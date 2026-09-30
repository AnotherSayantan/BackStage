package dev.legendsayantan.backstage.core.lifecycle

import dev.legendsayantan.backstage.core.backend.VaultBackendFactory
import dev.legendsayantan.backstage.core.crypto.VaultKeyState
import dev.legendsayantan.backstage.core.io.CapacityValidator
import dev.legendsayantan.backstage.core.io.DirectoryArbiter
import dev.legendsayantan.backstage.core.io.FolderLock
import dev.legendsayantan.backstage.core.workspace.AuditEventType
import dev.legendsayantan.backstage.core.workspace.AuditLogRepository
import dev.legendsayantan.backstage.core.workspace.GroupId
import dev.legendsayantan.backstage.core.workspace.GroupRepository
import dev.legendsayantan.backstage.core.workspace.LoadStrategy
import dev.legendsayantan.backstage.core.workspace.RetentionPolicy
import dev.legendsayantan.backstage.core.workspace.WorkspaceConfig
import dev.legendsayantan.backstage.core.workspace.WorkspaceId
import dev.legendsayantan.backstage.core.workspace.WorkspaceRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

data class PrefetchFailureEvent(
    val workspaceId: WorkspaceId,
    val displayName: String,
    val errorMessage: String,
)

/**
 * Evaluates retention policies (inactivity & deadline auto-eviction), habitual prefetch schedules
 * (including staged encrypted prefetching when the device is unattended), and atomic WorkspaceGroup
 * batch operations (`PARALLEL`, `SEQUENTIAL`, `SPACE_AWARE`).
 */
class LifecycleDaemon(
    private val workspaceRepository: WorkspaceRepository,
    private val groupRepository: GroupRepository,
    private val arbiter: DirectoryArbiter,
    private val auditLogRepository: AuditLogRepository? = null,
    val daemonScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {

    private val _prefetchFailures = MutableSharedFlow<PrefetchFailureEvent>(extraBufferCapacity = 16)
    val prefetchFailures: SharedFlow<PrefetchFailureEvent> = _prefetchFailures.asSharedFlow()

    /**
     * Checks all active workspaces against their configured [RetentionPolicy] and returns the
     * workspaces that have expired and should be evicted. Runs on [Dispatchers.IO].
     */
    suspend fun findExpiredActiveWorkspaces(
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): List<WorkspaceConfig> = withContext(Dispatchers.IO) {
        workspaceRepository.findAll().filter { ws ->
            val localDir = File(ws.resolvedLocalPath())
            val isActive = FolderLock.readLock(localDir)?.workspaceId == ws.id.value
            if (!isActive) return@filter false

            when (val policy = ws.retentionPolicy) {
                null, RetentionPolicy.Never -> false
                is RetentionPolicy.DeadlineExpiry -> nowEpochSeconds >= policy.deadlineEpochSeconds
                is RetentionPolicy.InactivityExpiry -> {
                    val lastActive = ws.lastActiveAtEpochSeconds ?: ws.createdAtEpochSeconds
                    val inactiveSeconds = nowEpochSeconds - lastActive
                    val thresholdSeconds = policy.afterDays.toLong() * 86_400L
                    inactiveSeconds >= thresholdSeconds
                }
            }
        }
    }

    /**
     * Evicts all expired workspaces. Requires an active Layer 1 session.
     */
    suspend fun evictExpiredWorkspaces(
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        passwordCallback: (suspend (VaultKeyState.NeedsPassword) -> String?)? = null,
    ): List<WorkspaceId> {
        val expired = findExpiredActiveWorkspaces(nowEpochSeconds)
        val evictedIds = mutableListOf<WorkspaceId>()
        for (ws in expired) {
            arbiter.evictWorkspace(ws.id, passwordCallback = passwordCallback)
            evictedIds.add(ws.id)
        }
        return evictedIds
    }

    /**
     * Stages encrypted vault files locally for workspaces whose prefetch schedule is due.
     * Safe to run while the device is locked because vault contents remain encrypted on disk
     * until the user next authenticates. Emits [PrefetchFailureEvent] and records to audit log on error.
     */
    suspend fun runScheduledPrefetches(): List<WorkspaceId> = withContext(Dispatchers.IO) {
        val prefetched = mutableListOf<WorkspaceId>()
        for (ws in workspaceRepository.findAll()) {
            val schedule = ws.prefetchSchedule ?: continue
            val localDir = File(ws.resolvedLocalPath())
            val isActive = FolderLock.readLock(localDir)?.workspaceId == ws.id.value
            if (!isActive) {
                runCatching {
                    if (schedule.cancelIfInsufficientSpace) {
                        val backend = VaultBackendFactory.create(ws.vaultBackend)
                        val required = backend.sizeBytes(ws.vaultFileName)
                        if (arbiter.stagingDir.usableSpace < required) {
                            throw IllegalStateException("Insufficient staging space for prefetch of '${ws.displayName}'")
                        }
                    }
                    arbiter.stageEncryptedPrefetch(ws.id)
                    prefetched.add(ws.id)
                }.onFailure { err ->
                    val msg = err.message ?: "Prefetch failed"
                    _prefetchFailures.tryEmit(PrefetchFailureEvent(ws.id, ws.displayName, msg))
                    auditLogRepository?.record(
                        workspaceId = ws.id,
                        eventType = AuditEventType.ERROR,
                        detailJson = """{"prefetchError":"${msg.replace("\"", "'")}"}""",
                    )
                }
            }
        }
        prefetched
    }

    /**
     * Loads all workspaces in [groupId] according to the group's [LoadStrategy].
     */
    suspend fun loadGroup(
        groupId: GroupId,
        strategyOverride: LoadStrategy? = null,
        passwordCallback: (suspend (VaultKeyState.NeedsPassword) -> String?)? = null,
    ) = coroutineScope {
        val group = groupRepository.findById(groupId)
            ?: throw IllegalArgumentException("Unknown group: ${groupId.value}")
        val strategy = strategyOverride ?: group.loadStrategy
        val memberIds = group.workspaceIds.ifEmpty {
            workspaceRepository.findByGroupId(groupId).map { it.id }
        }

        when (strategy) {
            LoadStrategy.PARALLEL -> {
                memberIds.map { wsId ->
                    async {
                        arbiter.loadWorkspace(wsId, passwordCallback = passwordCallback)
                    }
                }.awaitAll()
            }

            LoadStrategy.SEQUENTIAL -> {
                for (wsId in memberIds) {
                    arbiter.loadWorkspace(wsId, passwordCallback = passwordCallback)
                }
            }

            LoadStrategy.SPACE_AWARE -> {
                // Sort largest-first so large primary workspaces claim space before smaller auxiliary ones,
                // and skip any member whose required size exceeds remaining capacity.
                val configs = memberIds.mapNotNull { workspaceRepository.findById(it) }
                    .sortedByDescending { it.uncompressedSizeBytes ?: 0L }
                val lru = workspaceRepository.findLeastRecentlyActive()

                for (ws in configs) {
                    val backend = runCatching { VaultBackendFactory.create(ws.vaultBackend) }.getOrNull() ?: continue
                    val cap = CapacityValidator.validateLocalCapacity(
                        targetWorkspace = ws,
                        backend = backend,
                        allWorkspacesOrderedByLru = lru,
                    )
                    val evictableBytes = cap.evictionCandidates.sumOf { it.uncompressedSizeBytes ?: 0L }
                    if (cap.hasEnoughSpace || (cap.availableBytes + evictableBytes >= cap.requiredBytes)) {
                        arbiter.loadWorkspace(ws.id, passwordCallback = passwordCallback)
                    }
                }
            }
        }
    }

    /**
     * Evicts all active workspaces belonging to [groupId].
     */
    suspend fun evictGroup(
        groupId: GroupId,
        passwordCallback: (suspend (VaultKeyState.NeedsPassword) -> String?)? = null,
    ) {
        val group = groupRepository.findById(groupId)
            ?: throw IllegalArgumentException("Unknown group: ${groupId.value}")
        val members = group.workspaceIds.ifEmpty {
            workspaceRepository.findByGroupId(groupId).map { it.id }
        }
        for (wsId in members) {
            arbiter.evictWorkspace(wsId, passwordCallback = passwordCallback)
        }
    }

    override fun close() {
        daemonScope.cancel("LifecycleDaemon closed")
    }
}
