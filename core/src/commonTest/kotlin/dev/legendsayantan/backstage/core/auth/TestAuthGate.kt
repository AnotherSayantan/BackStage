package dev.legendsayantan.backstage.core.auth

import dev.legendsayantan.backstage.core.platform.PlatformContext

/**
 * Test-only [AuthGate] implementation compiled exclusively in test source sets.
 * Never included in production binaries.
 */
class TestAuthGate(
    context: PlatformContext,
    private val handler: suspend (String) -> AuthResult = { AuthResult.Success },
) : AuthGate(context) {
    override suspend fun authenticate(reason: String): AuthResult = handler(reason)
}
