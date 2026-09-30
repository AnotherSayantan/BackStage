package dev.legendsayantan.backstage.cli

import dev.legendsayantan.backstage.core.auth.AuthGate
import dev.legendsayantan.backstage.core.auth.AuthResult
import dev.legendsayantan.backstage.core.auth.DeviceCredentialSession
import dev.legendsayantan.backstage.core.platform.PlatformContext
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackStageCliTest {

    @BeforeTest
    fun setUp() {
        DeviceCredentialSession.clearCurrent()
    }

    @AfterTest
    fun tearDown() {
        DeviceCredentialSession.clearCurrent()
    }

    private fun <T> withIsolatedTempDir(block: (File) -> T): T {
        val dir = Files.createTempDirectory("backstage_cli_test_").toFile()
        return try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `cli blocks execution when device credential authentication fails`() = withIsolatedTempDir { tempDir ->
        val ctx = PlatformContext(tempDir)
        val failingGate = object : AuthGate(ctx) {
            override suspend fun authenticate(reason: String): AuthResult =
                AuthResult.Failure("PIN verification cancelled")
        }
        val lines = mutableListOf<String>()
        val exitCode = BackStageCliRunner.execute(
            args = arrayOf("status"),
            context = ctx,
            authGate = failingGate,
            out = { lines.add(it) }
        )
        assertEquals(1, exitCode)
        assertTrue(lines.any { it.contains("Device credential authentication failed") })
    }

    @Test
    fun `cli executes config and status after device credential authentication succeeds`() = withIsolatedTempDir { tempDir ->
        val ctx = PlatformContext(tempDir)
        val passingGate = object : AuthGate(ctx) {
            override suspend fun authenticate(reason: String): AuthResult = AuthResult.Success
        }
        val lines = mutableListOf<String>()
        val exitCode = BackStageCliRunner.execute(
            args = arrayOf("config", "--headless", "get", "auto_unload_enabled"),
            context = ctx,
            authGate = passingGate,
            out = { lines.add(it) }
        )
        assertEquals(0, exitCode)
        assertTrue(lines.any { it.contains("true") })

        val badStrategyLines = mutableListOf<String>()
        val badExit = BackStageCliRunner.execute(
            args = arrayOf("group", "load", "grp_TEST", "--strategy", "not_a_strategy"),
            context = ctx,
            authGate = passingGate,
            out = { badStrategyLines.add(it) }
        )
        assertEquals(1, badExit)
        assertTrue(badStrategyLines.any { it.contains("Unknown strategy") })
    }
}
