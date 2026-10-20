package dev.legendsayantan.backstage.core

import dev.legendsayantan.backstage.core.auth.AuthGate
import dev.legendsayantan.backstage.core.auth.AuthResult
import dev.legendsayantan.backstage.core.auth.DeviceCredentialSession
import dev.legendsayantan.backstage.core.auth.TestAuthGate
import dev.legendsayantan.backstage.core.backend.LocalPathBackend
import dev.legendsayantan.backstage.core.crypto.AeadCipher
import dev.legendsayantan.backstage.core.crypto.Blake3
import dev.legendsayantan.backstage.core.crypto.KeyProvider
import dev.legendsayantan.backstage.core.crypto.PasswordChanger
import dev.legendsayantan.backstage.core.crypto.PasswordReason
import dev.legendsayantan.backstage.core.crypto.PasswordVerifier
import dev.legendsayantan.backstage.core.crypto.ResolvedCipher
import dev.legendsayantan.backstage.core.crypto.TinyHeader
import dev.legendsayantan.backstage.core.crypto.VaultFormatConfig
import dev.legendsayantan.backstage.core.crypto.VaultKeyState
import dev.legendsayantan.backstage.core.crypto.WorkspaceKeyMode
import dev.legendsayantan.backstage.core.error.BackStageError
import dev.legendsayantan.backstage.core.error.BackStageException
import dev.legendsayantan.backstage.core.io.FolderLock
import dev.legendsayantan.backstage.core.platform.Platform
import dev.legendsayantan.backstage.core.platform.PlatformContext
import dev.legendsayantan.backstage.core.platform.SecureRandomProvider
import dev.legendsayantan.backstage.core.workspace.VaultBackendConfig
import dev.legendsayantan.backstage.core.workspace.WorkspaceConfig
import dev.legendsayantan.backstage.core.workspace.WorkspaceId
import dev.legendsayantan.backstage.core.workspace.WorkspaceState
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class BackStageCoreTest {

    private lateinit var tempRoot: File

    @BeforeTest
    fun setUp() {
        tempRoot = Files.createTempDirectory("backstage_test_").toFile()
        DeviceCredentialSession.clearCurrent()
    }

    @AfterTest
    fun tearDown() {
        DeviceCredentialSession.clearCurrent()
        tempRoot.deleteRecursively()
    }

    private fun createUnlockedEngine(deviceDir: File): BackStageEngine {
        val ctx = PlatformContext(deviceDir)
        return BackStageEngine(context = ctx, authGate = TestAuthGate(ctx))
    }

    @Test
    fun `layer 1 device credential gate is mandatory even for unencrypted workspaces`() = runTest {
        val deviceDir = File(tempRoot, "device1")
        val vaultDir = File(tempRoot, "vault")
        val localDir = File(tempRoot, "local_plain")
        localDir.mkdirs()
        File(localDir, "README.md").writeText("Plain workspace content")

        createUnlockedEngine(deviceDir).use { engine ->
            // Unlock to register the workspace first
            assertEquals(AuthResult.Success, engine.authenticateAndUnlock())

            val wsId = WorkspaceId("ws_PLAIN001")
            val plainConfig = WorkspaceConfig(
                id = wsId,
                displayName = "Plain Project",
                vaultBackend = VaultBackendConfig.LocalPath(vaultDir.absolutePath),
                vaultFileName = "plain.bvault",
                localTargetPath = localDir.absolutePath,
                vaultFormat = VaultFormatConfig.PlainCompressed,
                keyMode = null,
                createdAtEpochSeconds = 1700000000L,
            )
            engine.workspaceRepository.upsert(plainConfig)
            engine.arbiter.evictWorkspace(wsId, forceIgnoreHandles = true)
            assertFalse(localDir.exists(), "Local directory should be purged after eviction")

            // Simulate process exit -> Layer 1 session cleared
            DeviceCredentialSession.clearCurrent()

            // Attempting to load without Layer 1 session MUST fail even though encryption is disabled
            assertFailsWith<IllegalStateException> {
                engine.arbiter.loadWorkspace(wsId, forceIgnoreHandles = true)
            }

            // Re-authenticate via Layer 1 -> load succeeds without asking for a workspace password
            assertEquals(AuthResult.Success, engine.authenticateAndUnlock())
            engine.arbiter.loadWorkspace(wsId, forceIgnoreHandles = true)
            assertEquals("Plain workspace content", File(localDir, "README.md").readText())
        }
    }

    @Test
    fun `custom password set once on device A is remembered on device A and required with verification on device B`() = runTest {
        val deviceADir = File(tempRoot, "deviceA")
        val deviceBDir = File(tempRoot, "deviceB")
        val sharedVaultDir = File(tempRoot, "shared_vault")
        val localDirA = File(tempRoot, "local_A")
        val localDirB = File(tempRoot, "local_B")

        localDirA.mkdirs()
        File(localDirA, "src/Main.kt").apply {
            parentFile.mkdirs()
            writeText("fun main() = println(\"Hello BackStage!\")")
        }

        val wsId = WorkspaceId("ws_ORION01")
        val customPassword = "correct-horse-battery-staple"
        val hint = "xkcd 936"

        val configOnA = WorkspaceConfig(
            id = wsId,
            displayName = "Project Orion",
            vaultBackend = VaultBackendConfig.LocalPath(sharedVaultDir.absolutePath),
            vaultFileName = "orion.bstage",
            localTargetPath = localDirA.absolutePath,
            platformPathOverrides = mapOf(Platform.WINDOWS to localDirA.absolutePath),
            vaultFormat = VaultFormatConfig.Native,
            keyMode = WorkspaceKeyMode.PasswordBacked(
                keystoreAlias = WorkspaceConfig.defaultKeystoreAlias(wsId),
                passwordHint = hint,
            ),
            createdAtEpochSeconds = 1700000000L,
        )

        // 1. Device A creates and evicts the workspace, setting customPassword once
        createUnlockedEngine(deviceADir).use { engineA ->
            assertEquals(AuthResult.Success, engineA.authenticateAndUnlock())
            engineA.workspaceRepository.upsert(configOnA)

            // S-6: Missing initial password on new vault throws PasswordRequired
            val missingPwdEx = assertFailsWith<BackStageException> {
                engineA.arbiter.evictWorkspace(
                    workspaceId = wsId,
                    forceIgnoreHandles = true,
                    passwordOverrideForNewVault = null,
                )
            }
            assertIs<BackStageError.PasswordRequired>(missingPwdEx.error)

            engineA.arbiter.evictWorkspace(
                workspaceId = wsId,
                forceIgnoreHandles = true,
                passwordOverrideForNewVault = customPassword,
            )
            assertFalse(localDirA.exists())
            assertTrue(File(sharedVaultDir, "orion.bstage").isFile)
            assertTrue(File(sharedVaultDir, "orion.bstage.bsmeta").isFile)

            // 2. Device A loads the workspace back -> NEVER asks for the password!
            var passwordPromptedOnA = false
            engineA.arbiter.loadWorkspace(wsId, forceIgnoreHandles = true) {
                passwordPromptedOnA = true
                "should-not-be-called"
            }
            assertFalse(passwordPromptedOnA, "Device A must retrieve vault_key from OS keystore without prompting")
            assertEquals(
                "fun main() = println(\"Hello BackStage!\")",
                File(localDirA, "src/Main.kt").readText()
            )
        }

        // 3. Separate Device B accesses the same shared vault
        val configOnB = configOnA.copy(
            localTargetPath = localDirB.absolutePath,
            platformPathOverrides = emptyMap(),
        )
        createUnlockedEngine(deviceBDir).use { engineB ->
            assertEquals(AuthResult.Success, engineB.authenticateAndUnlock())
            engineB.workspaceRepository.upsert(configOnB)

            // 3a. Wrong password on Device B is rejected with BackStageError.WrongPassword
            val wrongErr = assertFailsWith<BackStageException> {
                engineB.arbiter.loadWorkspace(wsId, forceIgnoreHandles = true) { needsPwd ->
                    assertEquals(PasswordReason.NEW_DEVICE, needsPwd.reason)
                    assertEquals(hint, needsPwd.hint)
                    "wrong-password-attempt"
                }
            }
            assertEquals(BackStageError.WrongPassword, wrongErr.error)
            assertFalse(File(localDirB, "src/Main.kt").exists())

            // 3b. Correct password on Device B is verified, extracts workspace, and saves key to Device B's keystore
            var promptedCountOnB = 0
            engineB.arbiter.loadWorkspace(wsId, forceIgnoreHandles = true) { needsPwd ->
                promptedCountOnB++
                assertEquals(PasswordReason.NEW_DEVICE, needsPwd.reason)
                customPassword
            }
            assertEquals(1, promptedCountOnB)
            assertEquals(
                "fun main() = println(\"Hello BackStage!\")",
                File(localDirB, "src/Main.kt").readText()
            )

            // 3c. Subsequent evict & load on Device B never prompts for the password again!
            engineB.arbiter.evictWorkspace(wsId, forceIgnoreHandles = true) {
                error("Should not prompt on Device B after key is saved")
            }
            engineB.arbiter.loadWorkspace(wsId, forceIgnoreHandles = true) {
                error("Should not prompt on Device B after key is saved")
            }
            assertEquals(
                "fun main() = println(\"Hello BackStage!\")",
                File(localDirB, "src/Main.kt").readText()
            )
        }
    }

    @Test
    fun `fast password change patches 182-byte header and key rotation invalidates other devices`() = runTest {
        val deviceADir = File(tempRoot, "rot_deviceA")
        val deviceBDir = File(tempRoot, "rot_deviceB")
        val vaultDir = File(tempRoot, "rot_vault")
        val localDirA = File(tempRoot, "rot_localA")
        localDirA.mkdirs()
        File(localDirA, "data.txt").writeText("Sensitive workspace payload")

        val wsId = WorkspaceId("ws_ROTATE1")
        val initialPassword = "initial-password-123"
        val changedPassword = "changed-password-456"
        val rotatedPassword = "rotated-password-789"

        val config = WorkspaceConfig(
            id = wsId,
            displayName = "Rotation Test",
            vaultBackend = VaultBackendConfig.LocalPath(vaultDir.absolutePath),
            vaultFileName = "rotate.bstage",
            localTargetPath = localDirA.absolutePath,
            vaultFormat = VaultFormatConfig.Native,
            keyMode = WorkspaceKeyMode.PasswordBacked(
                keystoreAlias = WorkspaceConfig.defaultKeystoreAlias(wsId),
            ),
            createdAtEpochSeconds = 1700000000L,
        )

        val backend = LocalPathBackend(vaultDir)

        createUnlockedEngine(deviceADir).use { engineA ->
            assertEquals(AuthResult.Success, engineA.authenticateAndUnlock())
            engineA.workspaceRepository.upsert(config)
            engineA.arbiter.evictWorkspace(
                workspaceId = wsId,
                forceIgnoreHandles = true,
                passwordOverrideForNewVault = initialPassword,
            )

            // Device B unlocks with initialPassword so Device B caches the original vault_key
            val verifierB = PasswordVerifier(KeyProvider(PlatformContext(deviceBDir)))
            val stateB1 = assertIs<VaultKeyState.NeedsPassword>(verifierB.assess(config, backend))
            val unlockedKeyB = assertNotNull(verifierB.verify(initialPassword, stateB1))
            verifierB.saveToKeystore(WorkspaceConfig.defaultKeystoreAlias(wsId), unlockedKeyB)

            // Fast password change on Device A (header-only patch)
            engineA.passwordChanger.changePassword(
                workspace = config,
                newPassword = changedPassword,
                backend = backend,
            )

            // Verify on a fresh Device C that initialPassword now fails and changedPassword succeeds
            val verifierC = PasswordVerifier(KeyProvider(PlatformContext(File(tempRoot, "rot_deviceC"))))
            val stateC = assertIs<VaultKeyState.NeedsPassword>(verifierC.assess(config, backend))
            assertNull(verifierC.verify(initialPassword, stateC), "Old password must fail after changePassword")
            assertNotNull(verifierC.verify(changedPassword, stateC), "New password must succeed after changePassword")

            // Device B's cached vault_key is STILL valid after fast password change (no rotation yet)
            assertIs<VaultKeyState.Ready>(verifierB.assess(config, backend))

            // Now Device A performs a full Vault Key Rotation -> revokes Device B's cached vault_key!
            engineA.passwordChanger.rotateVaultKey(
                workspace = config,
                newPassword = rotatedPassword,
                backend = backend,
            )

            // Device B's next assess() detects HEADER_MAC mismatch -> KEY_ROTATED!
            val stateB2 = assertIs<VaultKeyState.NeedsPassword>(verifierB.assess(config, backend))
            assertEquals(PasswordReason.KEY_ROTATED, stateB2.reason)
            assertNull(verifierB.verify(changedPassword, stateB2), "Previous password must fail after key rotation")
            val newKeyForB = assertNotNull(verifierB.verify(rotatedPassword, stateB2), "Rotated password must succeed")
            assertEquals(32, newKeyForB.size)
        }
    }

    @Test
    fun `blake3 chunk manifest detects bit-rot before decryption and graceful handoff swaps occupants`() = runTest {
        val appDir = File(tempRoot, "handoff_app")
        val vaultDir = File(tempRoot, "handoff_vault")
        val sharedTargetDir = File(tempRoot, "shared_ssd_slot")

        val wsA = WorkspaceId("ws_ALPHA01")
        val wsB = WorkspaceId("ws_BETA002")

        val configA = WorkspaceConfig(
            id = wsA,
            displayName = "Alpha Workspace",
            vaultBackend = VaultBackendConfig.LocalPath(vaultDir.absolutePath),
            vaultFileName = "alpha.bstage",
            localTargetPath = sharedTargetDir.absolutePath,
            vaultFormat = VaultFormatConfig.Native,
            keyMode = WorkspaceKeyMode.PasswordBacked(WorkspaceConfig.defaultKeystoreAlias(wsA)),
            createdAtEpochSeconds = 1700000000L,
        )
        val configB = WorkspaceConfig(
            id = wsB,
            displayName = "Beta Workspace",
            vaultBackend = VaultBackendConfig.LocalPath(vaultDir.absolutePath),
            vaultFileName = "beta.bstage",
            localTargetPath = sharedTargetDir.absolutePath,
            vaultFormat = VaultFormatConfig.Native,
            keyMode = WorkspaceKeyMode.PasswordBacked(WorkspaceConfig.defaultKeystoreAlias(wsB)),
            createdAtEpochSeconds = 1700000000L,
        )

        createUnlockedEngine(appDir).use { engine ->
            assertEquals(AuthResult.Success, engine.authenticateAndUnlock())
            engine.workspaceRepository.upsert(configA)
            engine.workspaceRepository.upsert(configB)

            // Create Beta in sharedTargetDir and evict it to vault
            sharedTargetDir.mkdirs()
            File(sharedTargetDir, "project.txt").writeText("BETA_PAYLOAD")
            engine.arbiter.evictWorkspace(wsB, forceIgnoreHandles = true, passwordOverrideForNewVault = "beta-pass")

            // Create Alpha in sharedTargetDir and evict it to vault, then load Alpha so it occupies sharedTargetDir
            sharedTargetDir.mkdirs()
            File(sharedTargetDir, "project.txt").writeText("ALPHA_PAYLOAD")
            engine.arbiter.evictWorkspace(wsA, forceIgnoreHandles = true, passwordOverrideForNewVault = "alpha-pass")
            engine.arbiter.loadWorkspace(wsA, forceIgnoreHandles = true)
            assertEquals("ALPHA_PAYLOAD", File(sharedTargetDir, "project.txt").readText())

            // Modify Alpha locally, then load Beta into the SAME sharedTargetDir -> Graceful Directory Handoff!
            File(sharedTargetDir, "project.txt").writeText("ALPHA_MODIFIED_PAYLOAD")
            engine.arbiter.loadWorkspace(wsB, forceIgnoreHandles = true)
            assertEquals("BETA_PAYLOAD", File(sharedTargetDir, "project.txt").readText())
            assertEquals(WorkspaceState.Active, engine.arbiter.states.value[wsB])
            assertEquals(WorkspaceState.Vaulted, engine.arbiter.states.value[wsA])

            // Load Alpha back -> verify modified content was saved during the handoff!
            engine.arbiter.loadWorkspace(wsA, forceIgnoreHandles = true)
            assertEquals("ALPHA_MODIFIED_PAYLOAD", File(sharedTargetDir, "project.txt").readText())

            // Flip a bit at the end of Beta's encrypted chunk payload -> BLAKE3 chunk check must catch it!
            val betaVaultFile = File(vaultDir, "beta.bstage")
            val corrupted = betaVaultFile.readBytes()
            corrupted[corrupted.size - 1] = (corrupted[corrupted.size - 1].toInt() xor 0xFF).toByte()
            betaVaultFile.writeBytes(corrupted)

            val corruptEx = assertFailsWith<BackStageException> {
                engine.arbiter.loadWorkspace(wsB, forceIgnoreHandles = true)
            }
            assertIs<BackStageError.ChunkCorrupted>(corruptEx.error)
        }
    }

    @Test
    fun `staged encrypted prefetch verifies blake3 mac and retention daemon evicts expired workspaces`() = runTest {
        val appDir = File(tempRoot, "daemon_app")
        val vaultDir = File(tempRoot, "daemon_vault")
        val localDir = File(tempRoot, "daemon_local")
        localDir.mkdirs()
        File(localDir, "hello.txt").writeText("Prefetch & Retention Payload")

        val wsId = WorkspaceId("ws_DAEMON1")
        val config = WorkspaceConfig(
            id = wsId,
            displayName = "Daemon Workspace",
            vaultBackend = VaultBackendConfig.LocalPath(vaultDir.absolutePath),
            vaultFileName = "daemon.bstage",
            localTargetPath = localDir.absolutePath,
            vaultFormat = VaultFormatConfig.Native,
            keyMode = WorkspaceKeyMode.ManualOnEveryUse,
            retentionPolicy = dev.legendsayantan.backstage.core.workspace.RetentionPolicy.InactivityExpiry(afterDays = 7),
            prefetchSchedule = dev.legendsayantan.backstage.core.workspace.PrefetchSchedule(cronExpression = "0 8 * * *"),
            createdAtEpochSeconds = 1700000000L,
        )

        createUnlockedEngine(appDir).use { engine ->
            assertEquals(AuthResult.Success, engine.authenticateAndUnlock())
            engine.workspaceRepository.upsert(config)

            // Evict with ManualOnEveryUse
            engine.arbiter.evictWorkspace(wsId, forceIgnoreHandles = true, passwordOverrideForNewVault = "manual-pass")
            assertFalse(localDir.exists())

            // Stage encrypted prefetch and verify .bsstage_mac is created
            val stagedFile = engine.arbiter.stageEncryptedPrefetch(wsId)
            val stagedMac = File(stagedFile.parentFile, "${stagedFile.name}.bsstage_mac")
            assertTrue(stagedFile.isFile)
            assertTrue(stagedMac.isFile)
            assertEquals(WorkspaceState.StagedEncrypted, engine.arbiter.states.value[wsId])

            // Rotate vault key in ManualOnEveryUse mode using currentVaultKeyOverride
            val backend = LocalPathBackend(vaultDir)
            val needsPwd = assertIs<VaultKeyState.NeedsPassword>(engine.passwordVerifier.assess(config, backend))
            val currentVaultKey = assertNotNull(engine.passwordVerifier.verify("manual-pass", needsPwd))
            engine.passwordChanger.rotateVaultKey(
                workspace = config,
                newPassword = "manual-rotated-pass",
                backend = backend,
                currentVaultKeyOverride = currentVaultKey,
            )

            // Re-stage after rotation and load from staged file
            engine.arbiter.stageEncryptedPrefetch(wsId)
            engine.arbiter.loadWorkspace(wsId, forceIgnoreHandles = true) { "manual-rotated-pass" }
            assertEquals("Prefetch & Retention Payload", File(localDir, "hello.txt").readText())
            assertFalse(stagedFile.exists(), "Staged file should be cleaned up after extraction")
            assertFalse(stagedMac.exists(), "Staged MAC file should be cleaned up after extraction")

            // Test retention policy expiry (8 days later)
            val activeConfig = engine.workspaceRepository.findById(wsId)!!
            val futureEpoch = (activeConfig.lastActiveAtEpochSeconds ?: 1700000000L) + (8L * 86_400L)
            val expiredIds = engine.lifecycleDaemon.evictExpiredWorkspaces(nowEpochSeconds = futureEpoch) {
                "manual-rotated-pass"
            }
            assertEquals(listOf(wsId), expiredIds)
            assertFalse(localDir.exists())
        }
    }

    @Test
    fun `tampering with unencrypted FILE_ATTR_TABLE is caught before extraction and read-only files and empty dirs are handled`() = runTest {
        val appDir = File(tempRoot, "sec_app")
        val vaultDir = File(tempRoot, "sec_vault")
        val localDir = File(tempRoot, "sec_local")
        localDir.mkdirs()

        // Create a read-only file (simulating .git/objects/pack/*.pack on Windows) and an empty directory
        val readOnlyFile = File(localDir, ".git/objects/pack/pack-1234.idx").apply {
            parentFile.mkdirs()
            writeText("git-pack-index-bytes")
            setReadOnly()
        }
        assertTrue(readOnlyFile.exists())

        val wsId = WorkspaceId.fromArbitraryString("ws_日本語_very_long_workspace_identifier_exceeding_26_bytes")
        assertTrue(wsId.value.encodeToByteArray().size <= WorkspaceId.MAX_BYTE_LENGTH)

        val config = WorkspaceConfig(
            id = wsId,
            displayName = "Security & Edge Cases",
            vaultBackend = VaultBackendConfig.LocalPath(vaultDir.absolutePath),
            vaultFileName = "sec.bstage",
            localTargetPath = localDir.absolutePath,
            vaultFormat = VaultFormatConfig.Native,
            keyMode = WorkspaceKeyMode.PasswordBacked(WorkspaceConfig.defaultKeystoreAlias(wsId)),
            createdAtEpochSeconds = 1700000000L,
        )

        createUnlockedEngine(appDir).use { engine ->
            assertEquals(AuthResult.Success, engine.authenticateAndUnlock())
            engine.workspaceRepository.upsert(config)

            // B-9: Evict must cleanly purge read-only .git pack files on Windows
            engine.arbiter.evictWorkspace(wsId, forceIgnoreHandles = true, passwordOverrideForNewVault = "sec-pass-1")
            assertFalse(localDir.exists(), "Read-only files must be purged during eviction")

            // Load back, add an empty directory, and verify B-8 delta snapshot detects the empty directory
            engine.arbiter.loadWorkspace(wsId, forceIgnoreHandles = true)
            val emptySubDir = File(localDir, "newly_created_empty_dir")
            assertTrue(emptySubDir.mkdirs())
            val lockBefore = FolderLock.readLock(localDir)!!
            val diffWithEmptyDir = FolderLock.computeDiff(lockBefore.snapshot, localDir)
            assertTrue(diffWithEmptyDir.hasChanges, "B-8: Adding an empty directory must be detected by snapshot diff")

            // Re-evict so empty directory is persisted in vault
            engine.arbiter.evictWorkspace(wsId, forceIgnoreHandles = true)
            engine.arbiter.loadWorkspace(wsId, forceIgnoreHandles = true)
            assertTrue(File(localDir, "newly_created_empty_dir").isDirectory, "Empty directory must survive pack/unpack")

            // Evict again, stage prefetch, then changePassword -> B-11 must purge stale staged file
            engine.arbiter.evictWorkspace(wsId, forceIgnoreHandles = true)
            val stagedFile = engine.arbiter.stageEncryptedPrefetch(wsId)
            assertTrue(stagedFile.exists())
            val backend = LocalPathBackend(vaultDir)
            engine.passwordChanger.changePassword(
                workspace = config,
                newPassword = "sec-pass-2",
                backend = backend,
            )
            assertFalse(stagedFile.exists(), "B-11: Staged prefetch file must be invalidated on password change")

            // B-2: Tamper with a filename byte inside the unencrypted FILE_ATTR_TABLE (immediately after 182-byte TinyHeader)
            val vaultFile = File(vaultDir, "sec.bstage")
            val originalBytes = vaultFile.readBytes()
            val tamperedBytes = originalBytes.copyOf()
            // Offset 182 is fileCount (4 bytes), offset 186 is pathLen (2 bytes), offset 188 is first char of relativePath
            tamperedBytes[188] = (tamperedBytes[188].toInt() xor 0x01).toByte()
            vaultFile.writeBytes(tamperedBytes)

            val tamperEx = assertFailsWith<BackStageException> {
                engine.arbiter.loadWorkspace(wsId, forceIgnoreHandles = true)
            }
            assertEquals(BackStageError.HeaderMacInvalid, tamperEx.error)
            assertFalse(localDir.exists(), "B-2: No files or directories may be created when FILE_ATTR_TABLE is tampered")
        }
    }

    @Test
    fun test12_dynamicMountedVolumesOnlyIncludeExistingMounts() {
        val volumes = dev.legendsayantan.backstage.core.backend.MountedDriveDetector.discoverMountedVolumes()
        assertTrue(volumes.isNotEmpty(), "At least one mounted volume must be discovered on the host")
        val canonicalPaths = mutableSetOf<String>()
        for (vol in volumes) {
            assertTrue(
                vol.mountPoint.exists() && vol.mountPoint.isDirectory,
                "Discovered volume '${vol.name}' mount point must exist on disk: ${vol.mountPoint}"
            )
            assertTrue(vol.totalBytes > 0L, "Discovered volume '${vol.name}' must report positive total capacity")
            val canonical = runCatching { vol.mountPoint.canonicalPath.lowercase() }
                .getOrDefault(vol.mountPoint.absolutePath.lowercase())
            assertTrue(canonicalPaths.add(canonical), "Duplicate mount point discovered: $canonical")
        }
    }

    @Test
    fun test13_streamingProgressAndMonotonicityAcrossPackUnpackAndSnapshot() = runTest {
        val vaultDir = File(tempRoot, "prog_vault")
        val localDir = File(tempRoot, "prog_local")
        localDir.mkdirs()

        // Create several files so streaming progress emits multiple increments
        for (i in 1..6) {
            File(localDir, "dir_$i/file_$i.bin").apply {
                parentFile.mkdirs()
                writeBytes(ByteArray(128 * 1024) { (it + i).toByte() })
            }
        }

        val snapshotProgress = mutableListOf<Float>()
        val wsId = WorkspaceId("ws_PROG0000000000000001")
        FolderLock.writeLock(
            directory = localDir,
            workspaceId = wsId,
            onProgress = { p, _, _ -> snapshotProgress.add(p) },
        )
        assertTrue(snapshotProgress.isNotEmpty(), "FolderLock.writeLock must emit progress updates")
        for (idx in 1 until snapshotProgress.size) {
            assertTrue(
                snapshotProgress[idx] >= snapshotProgress[idx - 1] - 1e-5f,
                "Snapshot progress must be monotonic: ${snapshotProgress[idx - 1]} -> ${snapshotProgress[idx]}"
            )
        }
        assertEquals(1.0f, snapshotProgress.last(), 1e-4f)

        val config = WorkspaceConfig(
            id = wsId,
            displayName = "Progress Verification",
            vaultBackend = VaultBackendConfig.LocalPath(vaultDir.absolutePath),
            vaultFileName = "prog.bstage",
            localTargetPath = localDir.absolutePath,
            vaultFormat = VaultFormatConfig.Native,
            keyMode = WorkspaceKeyMode.PasswordBacked(WorkspaceConfig.defaultKeystoreAlias(wsId)),
            createdAtEpochSeconds = 1700000000L,
        )
        val backend = LocalPathBackend(vaultDir)
        val vaultKey = ByteArray(32) { (it + 7).toByte() }
        val streamer = dev.legendsayantan.backstage.core.io.ParallelIoStreamer()

        val packProgress = mutableListOf<Float>()
        streamer.packWorkspace(
            workspace = config,
            sourceDirectory = localDir,
            backend = backend,
            vaultKey = vaultKey,
            chunkSize = 256 * 1024,
            onProgress = { p, _, _ -> packProgress.add(p) },
        )
        assertTrue(packProgress.size >= 4, "ParallelIoStreamer.packWorkspace must emit multi-phase progress updates")
        for (idx in 1 until packProgress.size) {
            assertTrue(
                packProgress[idx] >= packProgress[idx - 1] - 1e-5f,
                "Pack progress must be monotonic: ${packProgress[idx - 1]} -> ${packProgress[idx]}"
            )
        }
        assertEquals(1.0f, packProgress.last(), 1e-4f)

        val unpackDir = File(tempRoot, "prog_unpacked")
        val unpackProgress = mutableListOf<Float>()
        streamer.unpackWorkspace(
            workspace = config,
            targetDirectory = unpackDir,
            backend = backend,
            vaultKey = vaultKey,
            onProgress = { p, _, _ -> unpackProgress.add(p) },
        )
        assertTrue(unpackProgress.size >= 4, "ParallelIoStreamer.unpackWorkspace must emit multi-phase progress updates")
        for (idx in 1 until unpackProgress.size) {
            assertTrue(
                unpackProgress[idx] >= unpackProgress[idx - 1] - 1e-5f,
                "Unpack progress must be monotonic: ${unpackProgress[idx - 1]} -> ${unpackProgress[idx]}"
            )
        }
        assertEquals(1.0f, unpackProgress.last(), 1e-4f)
    }

    @Test
    fun `staged prefetch metadata provides instant verification and concurrent load calls are deduplicated`() = runTest {
        val deviceDir = File(tempRoot, "dev_instant_stage")
        val vaultDir = File(tempRoot, "vault_instant_stage")
        val localDir = File(tempRoot, "local_instant_stage")
        localDir.mkdirs()
        File(localDir, "data.bin").writeBytes(ByteArray(100_000) { (it % 256).toByte() })

        createUnlockedEngine(deviceDir).use { engine ->
            assertEquals(AuthResult.Success, engine.authenticateAndUnlock())

            val wsId = WorkspaceId("ws_INSTANT01")
            val config = WorkspaceConfig(
                id = wsId,
                displayName = "Instant Staging Test",
                vaultBackend = VaultBackendConfig.LocalPath(vaultDir.absolutePath),
                vaultFileName = "instant.bstage",
                localTargetPath = localDir.absolutePath,
                createdAtEpochSeconds = 1700000000L,
            )
            engine.workspaceRepository.upsert(config)

            // Evict and stage
            engine.arbiter.evictWorkspace(wsId, forceIgnoreHandles = true, passwordOverrideForNewVault = "test-pass")
            assertFalse(localDir.exists())

            val stagedFile = engine.arbiter.stageEncryptedPrefetch(wsId)
            val stagedMacFile = File(stagedFile.parentFile, "${stagedFile.name}.bsstage_mac")
            assertTrue(stagedFile.isFile)
            assertTrue(stagedMacFile.isFile)

            val macContent = stagedMacFile.readText().trim()
            val parts = macContent.split(":")
            assertEquals(3, parts.size, "Stage MAC should contain hash, size, and mtime")
            assertEquals(stagedFile.length(), parts[1].toLong())
            assertEquals(stagedFile.lastModified(), parts[2].toLong())

            // Restore from staged file (instant verification)
            engine.arbiter.loadWorkspace(wsId, forceIgnoreHandles = true) { "test-pass" }
            assertTrue(localDir.isDirectory)
            assertEquals(100_000L, File(localDir, "data.bin").length())
            assertFalse(stagedFile.exists(), "Staged file should be purged after extraction")

            // Re-evict and test legacy MAC format fallback
            engine.arbiter.evictWorkspace(wsId, forceIgnoreHandles = true, passwordOverrideForNewVault = "test-pass")
            engine.arbiter.stageEncryptedPrefetch(wsId)
            // Overwrite MAC file with legacy 1-part format (hash only)
            stagedMacFile.writeText(parts[0])

            // Load should fall back to full BLAKE3 verification and succeed
            engine.arbiter.loadWorkspace(wsId, forceIgnoreHandles = true) { "test-pass" }
            assertTrue(localDir.isDirectory)
            assertEquals(100_000L, File(localDir, "data.bin").length())

            // Calling loadWorkspace again when already active is a deduplicated no-op
            engine.arbiter.loadWorkspace(wsId, forceIgnoreHandles = true) { "test-pass" }
            assertEquals(WorkspaceState.Active, engine.arbiter.currentState(wsId))
        }
    }

    @Test
    fun `existing local folder workspace without vault is detected as Active, not Missing`() = runTest {
        val deviceDir = File(tempRoot, "device_local_test")
        val vaultDir = File(tempRoot, "vault_local_test")
        val localDir = File(tempRoot, "local_existing_adobe")
        localDir.mkdirs()
        File(localDir, "App.exe").writeText("Mock Executable")

        createUnlockedEngine(deviceDir).use { engine ->
            assertEquals(AuthResult.Success, engine.authenticateAndUnlock())
            val wsId = WorkspaceId("ws_ADOBE01")
            val config = WorkspaceConfig(
                id = wsId,
                displayName = "Adobe",
                vaultBackend = VaultBackendConfig.LocalPath(vaultDir.absolutePath),
                vaultFileName = "adobe.bvault",
                localTargetPath = localDir.absolutePath,
                vaultFormat = VaultFormatConfig.Native,
                keyMode = WorkspaceKeyMode.ManualOnEveryUse,
                createdAtEpochSeconds = 1700000000L,
            )

            // Local folder exists on disk, no .bstage_lock, no vault yet
            val detectedState = engine.arbiter.detectWorkspaceState(config)
            assertEquals(
                WorkspaceState.Active,
                detectedState,
                "An existing local folder workspace without vault must be detected as Active, never Missing"
            )

            // Verify .bstage_lock was opportunistically written
            val lock = FolderLock.readLock(localDir)
            assertNotNull(lock, "Lock should be opportunistically established for active local directory")
            assertEquals(wsId.value, lock.workspaceId)
        }
    }

    @Test
    fun `empty existing local folder workspace without vault is detected as Active, not Missing`() = runTest {
        val deviceDir = File(tempRoot, "device_empty_test")
        val vaultDir = File(tempRoot, "vault_empty_test")
        val localDir = File(tempRoot, "local_empty_dir")
        localDir.mkdirs()

        createUnlockedEngine(deviceDir).use { engine ->
            assertEquals(AuthResult.Success, engine.authenticateAndUnlock())
            val wsId = WorkspaceId("ws_EMPTY01")
            val config = WorkspaceConfig(
                id = wsId,
                displayName = "Empty Workspace",
                vaultBackend = VaultBackendConfig.LocalPath(vaultDir.absolutePath),
                vaultFileName = "empty.bvault",
                localTargetPath = localDir.absolutePath,
                vaultFormat = VaultFormatConfig.Native,
                keyMode = WorkspaceKeyMode.ManualOnEveryUse,
                createdAtEpochSeconds = 1700000000L,
            )

            val detectedState = engine.arbiter.detectWorkspaceState(config)
            assertEquals(
                WorkspaceState.Active,
                detectedState,
                "An empty local folder workspace without vault must be detected as Active"
            )
        }
    }

    @Test
    fun `workspace with non-existent local directory and non-existent vault is detected as Missing`() = runTest {
        val deviceDir = File(tempRoot, "device_missing_test")
        val vaultDir = File(tempRoot, "vault_missing_test")
        val localDir = File(tempRoot, "non_existent_folder")

        createUnlockedEngine(deviceDir).use { engine ->
            assertEquals(AuthResult.Success, engine.authenticateAndUnlock())
            val wsId = WorkspaceId("ws_MISSING01")
            val config = WorkspaceConfig(
                id = wsId,
                displayName = "Missing Workspace",
                vaultBackend = VaultBackendConfig.LocalPath(vaultDir.absolutePath),
                vaultFileName = "missing.bvault",
                localTargetPath = localDir.absolutePath,
                vaultFormat = VaultFormatConfig.Native,
                keyMode = WorkspaceKeyMode.ManualOnEveryUse,
                createdAtEpochSeconds = 1700000000L,
            )

            val detectedState = engine.arbiter.detectWorkspaceState(config)
            assertEquals(
                WorkspaceState.Missing,
                detectedState,
                "Workspace with no local directory and no vault must be detected as Missing"
            )
        }
    }

    @Test
    fun `workspace with existing files and existing vault is detected as Active`() = runTest {
        val deviceDir = File(tempRoot, "device_files_and_vault")
        val vaultDir = File(tempRoot, "vault_files_and_vault")
        val localDir = File(tempRoot, "local_files_and_vault")
        localDir.mkdirs()
        File(localDir, "test.txt").writeText("Hello BackStage")

        createUnlockedEngine(deviceDir).use { engine ->
            assertEquals(AuthResult.Success, engine.authenticateAndUnlock())
            val wsId = WorkspaceId("ws_BOTH01")
            val config = WorkspaceConfig(
                id = wsId,
                displayName = "Both Test",
                vaultBackend = VaultBackendConfig.LocalPath(vaultDir.absolutePath),
                vaultFileName = "both.bvault",
                localTargetPath = localDir.absolutePath,
                vaultFormat = VaultFormatConfig.PlainCompressed,
                keyMode = null,
                createdAtEpochSeconds = 1700000000L,
            )
            engine.workspaceRepository.upsert(config)

            // Evict to create the vault
            engine.arbiter.evictWorkspace(wsId, forceIgnoreHandles = true)
            assertFalse(localDir.exists())

            // Recreate local directory with files and delete .bstage_lock to simulate manual restore or unarchived state
            localDir.mkdirs()
            File(localDir, "restored.txt").writeText("Unarchived content")
            FolderLock.removeLock(localDir)

            val detectedState = engine.arbiter.detectWorkspaceState(config)
            assertEquals(
                WorkspaceState.Active,
                detectedState,
                "When local files are present on disk, workspace must be detected as Active even if vault exists"
            )
        }
    }

    @Test
    fun testPlainSevenZipEvictAndRestoreWithoutPassword() = runTest {
        val deviceDir = File(tempRoot, "bstage_plain7z_dev")
        val localDir = File(tempRoot, "bstage_plain7z_local")
        val vaultDir = File(tempRoot, "bstage_plain7z_vault")
        localDir.mkdirs()
        vaultDir.mkdirs()
        File(localDir, "plain_sample.txt").writeText("Unencrypted 7z payload content")
        val sub = File(localDir, "subfolder").apply { mkdirs() }
        File(sub, "nested.txt").writeText("Nested unencrypted file")

        createUnlockedEngine(deviceDir).use { engine ->
            assertEquals(AuthResult.Success, engine.authenticateAndUnlock())
            val wsId = WorkspaceId("ws_PLAIN7Z_01")
            val config = WorkspaceConfig(
                id = wsId,
                displayName = "Plain 7z Test",
                vaultBackend = VaultBackendConfig.LocalPath(vaultDir.absolutePath),
                vaultFileName = "plain_archive.7z",
                localTargetPath = localDir.absolutePath,
                vaultFormat = VaultFormatConfig.PlainSevenZip,
                keyMode = null,
                createdAtEpochSeconds = 1700000000L,
            )
            engine.workspaceRepository.upsert(config)

            // Evict (compress into unencrypted .7z archive)
            engine.arbiter.evictWorkspace(wsId, forceIgnoreHandles = true)
            assertFalse(localDir.exists(), "Local directory should be removed after eviction")

            val vaultFile = File(vaultDir, "plain_archive.7z")
            assertTrue(vaultFile.exists(), "Vault file should exist")
            assertTrue(vaultFile.length() > 0L, "Vault file should not be empty")

            val sidecarFile = File(vaultDir, "plain_archive.7z.bsmeta")
            assertTrue(sidecarFile.exists(), "Sidecar should exist")
            val sidecar = dev.legendsayantan.backstage.core.crypto.BsmetaSidecar.decode(sidecarFile.readText())
            assertEquals("plain7z", sidecar.vaultFormat)
            assertNull(sidecar.kdf, "Plain 7z sidecar must not contain KDF info")
            assertNull(sidecar.headerMacHex, "Plain 7z sidecar must not contain headerMacHex")

            // Restore (uncompress without password)
            engine.arbiter.loadWorkspace(wsId)
            assertTrue(localDir.exists(), "Local directory should be restored")
            assertEquals("Unencrypted 7z payload content", File(localDir, "plain_sample.txt").readText())
            assertEquals("Nested unencrypted file", File(sub, "nested.txt").readText())
        }
    }

    @Test
    fun testMigrateBetweenPortableAndPlainSevenZip() = runTest {
        val deviceDir = File(tempRoot, "bstage_mig7z_dev")
        val localDir = File(tempRoot, "bstage_mig7z_local")
        val vaultDir = File(tempRoot, "bstage_mig7z_vault")
        localDir.mkdirs()
        vaultDir.mkdirs()
        File(localDir, "secret_data.txt").writeText("Secret migration test content")

        createUnlockedEngine(deviceDir).use { engine ->
            assertEquals(AuthResult.Success, engine.authenticateAndUnlock())
            val wsId = WorkspaceId("ws_MIG7Z_01")
            val config = WorkspaceConfig(
                id = wsId,
                displayName = "Migrate 7z Test",
                vaultBackend = VaultBackendConfig.LocalPath(vaultDir.absolutePath),
                vaultFileName = "mig_archive.7z",
                localTargetPath = localDir.absolutePath,
                vaultFormat = VaultFormatConfig.Portable,
                keyMode = WorkspaceKeyMode.PasswordBacked(
                    keystoreAlias = WorkspaceConfig.defaultKeystoreAlias(wsId)
                ),
                createdAtEpochSeconds = 1700000000L,
            )
            engine.workspaceRepository.upsert(config)

            // Evict as encrypted Portable .7z
            engine.arbiter.evictWorkspace(wsId, forceIgnoreHandles = true, passwordOverrideForNewVault = "pwd123456")
            assertFalse(localDir.exists())

            val sidecarEncrypted = dev.legendsayantan.backstage.core.crypto.BsmetaSidecar.decode(
                File(vaultDir, "mig_archive.7z.bsmeta").readText()
            )
            assertNotNull(sidecarEncrypted.kdf, "Encrypted Portable sidecar must contain KDF info")

            // Migrate to PlainSevenZip (unencrypted .7z)
            val migratedPlain = engine.arbiter.migrateVaultFormat(
                workspaceId = wsId,
                targetFormat = VaultFormatConfig.PlainSevenZip,
            )
            assertEquals(VaultFormatConfig.PlainSevenZip, migratedPlain.vaultFormat)
            assertNull(migratedPlain.keyMode, "PlainSevenZip must have keyMode = null")

            val sidecarPlain = dev.legendsayantan.backstage.core.crypto.BsmetaSidecar.decode(
                File(vaultDir, "mig_archive.7z.bsmeta").readText()
            )
            assertEquals("plain7z", sidecarPlain.vaultFormat)
            assertNull(sidecarPlain.kdf, "Plain 7z sidecar must not contain KDF info")

            // Restore without password
            engine.arbiter.loadWorkspace(wsId)
            assertTrue(localDir.exists())
            assertEquals("Secret migration test content", File(localDir, "secret_data.txt").readText())
        }
    }
}



