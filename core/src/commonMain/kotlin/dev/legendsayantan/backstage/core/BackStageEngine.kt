package dev.legendsayantan.backstage.core

import dev.legendsayantan.backstage.core.auth.AuthGate
import dev.legendsayantan.backstage.core.auth.AuthResult
import dev.legendsayantan.backstage.core.auth.DeviceCredentialSession
import dev.legendsayantan.backstage.core.crypto.KeyProvider
import dev.legendsayantan.backstage.core.crypto.PasswordChanger
import dev.legendsayantan.backstage.core.crypto.PasswordVerifier
import dev.legendsayantan.backstage.core.db.DatabaseSchemaInitializer
import dev.legendsayantan.backstage.core.error.BackStageError
import dev.legendsayantan.backstage.core.error.BackStageException
import dev.legendsayantan.backstage.core.io.DirectoryArbiter
import dev.legendsayantan.backstage.core.io.OpenHandleChecker
import dev.legendsayantan.backstage.core.io.ParallelIoStreamer
import dev.legendsayantan.backstage.core.lifecycle.LifecycleDaemon
import dev.legendsayantan.backstage.core.platform.PlatformContext
import dev.legendsayantan.backstage.core.workspace.AppConfigRepository
import dev.legendsayantan.backstage.core.workspace.AuditEventType
import dev.legendsayantan.backstage.core.workspace.AuditLogRepository
import dev.legendsayantan.backstage.core.workspace.GroupRepository
import dev.legendsayantan.backstage.core.workspace.WorkspaceRepository
import java.io.File
import java.sql.Connection

/**
 * Primary entry point and dependency container for the BackStage Core Engine.
 * Enforces mandatory Layer 1 Device Credential Authentication before unlocking the
 * SQLCipher state database or exposing workspace operations.
 */
class BackStageEngine(
    val context: PlatformContext,
    val authGate: AuthGate = AuthGate(context),
    val keyProvider: KeyProvider = KeyProvider(context),
) : AutoCloseable {

    private var dbConnection: Connection? = null

    lateinit var workspaceRepository: WorkspaceRepository
        private set
    lateinit var groupRepository: GroupRepository
        private set
    lateinit var auditLogRepository: AuditLogRepository
        private set
    lateinit var appConfigRepository: AppConfigRepository
        private set
    lateinit var passwordVerifier: PasswordVerifier
        private set
    lateinit var passwordChanger: PasswordChanger
        private set
    lateinit var arbiter: DirectoryArbiter
        private set
    lateinit var lifecycleDaemon: LifecycleDaemon
        private set

    val isUnlocked: Boolean
        get() = DeviceCredentialSession.current() != null && dbConnection != null

    /**
     * Challenges the user with the native OS security layer (Windows Hello / Linux Polkit / Android BiometricPrompt),
     * issues a process-scoped [DeviceCredentialSession], and unlocks the encrypted SQLite/SQLCipher state database.
     */
    suspend fun authenticateAndUnlock(
        reason: String = "BackStage requires your device credentials to verify it's you",
    ): AuthResult {
        val result = authGate.authenticate(reason)
        if (result !is AuthResult.Success) {
            return result
        }

        val dbKey = DatabaseSchemaInitializer.getOrCreateDatabaseKey(keyProvider)
        DeviceCredentialSession.issue(
            dbKey = dbKey,
            nowMillis = System.currentTimeMillis(),
        )
        dbKey.fill(0)

        val dbFile = File(context.resolveAppDataDir(), "backstage.db")
        val conn = DatabaseSchemaInitializer.bootstrapDatabase(
            context = context,
            keyProvider = keyProvider,
            dbFile = dbFile,
        )
        dbConnection = conn

        workspaceRepository = WorkspaceRepository(conn)
        groupRepository = GroupRepository(conn)
        auditLogRepository = AuditLogRepository(conn)
        appConfigRepository = AppConfigRepository(conn)
        passwordVerifier = PasswordVerifier(keyProvider)
        val stagingDir = File(context.resolveAppDataDir(), "stage").apply { mkdirs() }
        passwordChanger = PasswordChanger(keyProvider, stagingDir = stagingDir)
        arbiter = DirectoryArbiter(
            context = context,
            workspaceRepository = workspaceRepository,
            appConfigRepository = appConfigRepository,
            auditLogRepository = auditLogRepository,
            keyProvider = keyProvider,
            passwordVerifier = passwordVerifier,
            ioStreamer = ParallelIoStreamer(),
            openHandleChecker = OpenHandleChecker(),
        )
        lifecycleDaemon = LifecycleDaemon(
            workspaceRepository = workspaceRepository,
            groupRepository = groupRepository,
            arbiter = arbiter,
            auditLogRepository = auditLogRepository,
        )

        auditLogRepository.record(
            workspaceId = null,
            eventType = AuditEventType.AUTH,
            detailJson = """{"status":"success"}""",
        )
        arbiter.refreshStates()
        return AuthResult.Success
    }

    fun requireUnlocked() {
        if (!isUnlocked) {
            throw BackStageException(
                BackStageError.AuthFailed("BackStage engine is locked; device credential authentication is required.")
            )
        }
    }

    override fun close() {
        if (::lifecycleDaemon.isInitialized) {
            runCatching { lifecycleDaemon.close() }
        }
        runCatching { dbConnection?.close() }
        dbConnection = null
        DeviceCredentialSession.clearCurrent()
    }
}
