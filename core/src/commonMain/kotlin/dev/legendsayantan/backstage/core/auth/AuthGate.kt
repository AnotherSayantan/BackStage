package dev.legendsayantan.backstage.core.auth

import dev.legendsayantan.backstage.core.platform.PlatformContext
import dev.legendsayantan.backstage.core.platform.SecureRandomProvider

sealed interface AuthResult {
    data object Success : AuthResult
    data class Failure(val reason: String) : AuthResult
    data object Unavailable : AuthResult
}

/**
 * Layer 1 — Mandatory Device Credential Gate.
 * Every app launch and every CLI invocation MUST pass through this gate.
 * Non-bypassable regardless of whether an individual workspace uses encryption.
 */
expect open class AuthGate(context: PlatformContext) {
    open suspend fun authenticate(reason: String = "BackStage requires your device credentials to verify it's you"): AuthResult
}

/**
 * Process-scoped device credential session.
 * Lives only for the duration of the running process; cleared and zero-filled on exit.
 * No persistent TTL on disk.
 */
class DeviceCredentialSession private constructor(
    val sessionToken: ByteArray,
    val issuedAtEpochMillis: Long,
    val dbKey: ByteArray,
) {
    @Volatile
    private var destroyed: Boolean = false

    val isActive: Boolean
        get() = !destroyed

    fun destroy() {
        destroyed = true
        sessionToken.fill(0)
        dbKey.fill(0)
    }

    companion object {
        @Volatile
        private var currentSession: DeviceCredentialSession? = null

        fun current(): DeviceCredentialSession? =
            currentSession?.takeIf { it.isActive }

        fun requireActive(): DeviceCredentialSession =
            current() ?: throw IllegalStateException(
                "Layer 1 DeviceCredentialSession is not active. Device authentication must pass before any BackStage operation."
            )

        fun issue(dbKey: ByteArray, nowMillis: Long): DeviceCredentialSession {
            currentSession?.destroy()
            val session = DeviceCredentialSession(
                sessionToken = SecureRandomProvider.nextBytes(32),
                issuedAtEpochMillis = nowMillis,
                dbKey = dbKey.copyOf(),
            )
            currentSession = session
            return session
        }

        fun clearCurrent() {
            currentSession?.destroy()
            currentSession = null
        }
    }
}
