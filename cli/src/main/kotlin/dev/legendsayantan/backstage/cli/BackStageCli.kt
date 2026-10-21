package dev.legendsayantan.backstage.cli

import dev.legendsayantan.backstage.core.BackStageEngine
import dev.legendsayantan.backstage.core.auth.AuthResult
import dev.legendsayantan.backstage.core.backend.VaultBackendFactory
import dev.legendsayantan.backstage.core.crypto.PasswordReason
import dev.legendsayantan.backstage.core.crypto.VaultFormatConfig
import dev.legendsayantan.backstage.core.crypto.VaultKeyState
import dev.legendsayantan.backstage.core.crypto.WorkspaceKeyMode
import dev.legendsayantan.backstage.core.platform.PlatformContext
import dev.legendsayantan.backstage.core.workspace.GroupId
import dev.legendsayantan.backstage.core.workspace.LoadStrategy
import dev.legendsayantan.backstage.core.workspace.WorkspaceConfig
import dev.legendsayantan.backstage.core.workspace.WorkspaceId
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Headless CLI runner (`bstage`).
 * Every invocation executes the mandatory Layer 1 Device Credential Auth Prelude
 * before unlocking the database or performing any workspace operation.
 */
object BackStageCliRunner {
    private val json = Json { prettyPrint = true }

    fun execute(
        args: Array<String>,
        context: PlatformContext = PlatformContext(),
        authGate: dev.legendsayantan.backstage.core.auth.AuthGate = dev.legendsayantan.backstage.core.auth.AuthGate(context),
        passwordReader: (String) -> String? = ::readPasswordFromConsole,
        out: (String) -> Unit = ::println,
    ): Int = runBlocking {
        if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
            printUsage(out)
            return@runBlocking 0
        }

        BackStageEngine(context = context, authGate = authGate).use { engine ->
            // Mandatory Layer 1 Device Credential Gate on every CLI start
            val authResult = engine.authenticateAndUnlock(
                reason = "BackStage CLI requires your device credentials to verify it's you"
            )
            when (authResult) {
                is AuthResult.Success -> Unit
                is AuthResult.Failure -> {
                    out("✗ Device credential authentication failed: ${authResult.reason}")
                    return@runBlocking 1
                }
                is AuthResult.Unavailable -> {
                    out("✗ Device credential authentication is unavailable on this host.")
                    return@runBlocking 1
                }
            }

            engine.keyProvider.degradedProtectionWarning?.let { warning ->
                out("⚠ Security Warning: $warning")
            }
            dev.legendsayantan.backstage.core.db.DatabaseSchemaInitializer.lastDatabaseRecoveryWarning?.let { warning ->
                out("⚠ Database Notice: $warning")
            }

            return@runBlocking try {
                dispatchCommand(args.toList(), engine, passwordReader, out)
            } catch (e: Exception) {
                out("✗ Error: ${e.message}")
                1
            }
        }
    }

    private val valueFlags = setOf("--strategy", "--to", "--name", "--format", "--filter")

    private fun extractPositionalArgs(args: List<String>): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            val token = args[i]
            when {
                token in valueFlags -> i += 2
                token.startsWith("--") -> i += 1
                else -> {
                    result.add(token)
                    i += 1
                }
            }
        }
        return result
    }

    private fun findFlagValue(args: List<String>, flag: String): String? {
        val eqPrefix = "$flag="
        args.firstOrNull { it.startsWith(eqPrefix, ignoreCase = true) }?.let {
            return it.substring(eqPrefix.length)
        }
        val idx = args.indexOfFirst { it.equals(flag, ignoreCase = true) }
        return if (idx >= 0 && idx + 1 < args.size) args[idx + 1] else null
    }

    private suspend fun dispatchCommand(
        args: List<String>,
        engine: BackStageEngine,
        passwordReader: (String) -> String?,
        out: (String) -> Unit,
    ): Int {
        val pos = extractPositionalArgs(args)
        val cmd = pos.firstOrNull()?.lowercase() ?: args[0].lowercase()
        val force = "--force" in args

        return when (cmd) {
            "swap", "load" -> {
                val idArg = pos.getOrNull(1) ?: run {
                    out("Usage: bstage swap <workspace-id> [--headless] [--force]")
                    return 1
                }
                val wsId = WorkspaceId(idArg)
                engine.arbiter.loadWorkspace(
                    workspaceId = wsId,
                    forceIgnoreHandles = force,
                ) { needsPwd ->
                    promptCliPassword(wsId, needsPwd, passwordReader, out)
                }
                out("✓ Loaded workspace '$idArg'")
                0
            }

            "evict" -> {
                val idArg = pos.getOrNull(1) ?: run {
                    out("Usage: bstage evict <workspace-id> [--headless] [--force]")
                    return 1
                }
                val wsId = WorkspaceId(idArg)
                engine.arbiter.evictWorkspace(
                    workspaceId = wsId,
                    forceIgnoreHandles = force,
                ) { needsPwd ->
                    promptCliPassword(wsId, needsPwd, passwordReader, out)
                }
                out("✓ Evicted workspace '$idArg'")
                0
            }

            "group" -> {
                val sub = pos.getOrNull(1)?.lowercase()
                val grpIdArg = pos.getOrNull(2)
                if (sub == null || grpIdArg == null) {
                    out("Usage: bstage group <load|evict> <group-id> [--strategy parallel|sequential|space_aware]")
                    return 1
                }
                val grpId = GroupId(grpIdArg)
                val rawStrategy = findFlagValue(args, "--strategy")
                val strategy = if (rawStrategy != null) {
                    LoadStrategy.entries.firstOrNull { it.name.equals(rawStrategy, ignoreCase = true) } ?: run {
                        out("✗ Unknown strategy '$rawStrategy'. Expected one of: parallel, sequential, space_aware")
                        return 1
                    }
                } else {
                    null
                }
                when (sub) {
                    "load" -> {
                        engine.lifecycleDaemon.loadGroup(grpId, strategy) { needsPwd ->
                            promptCliPassword(WorkspaceId("group-member"), needsPwd, passwordReader, out)
                        }
                        out("✓ Loaded group '$grpIdArg'")
                        0
                    }
                    "evict" -> {
                        engine.lifecycleDaemon.evictGroup(grpId) { needsPwd ->
                            promptCliPassword(WorkspaceId("group-member"), needsPwd, passwordReader, out)
                        }
                        out("✓ Evicted group '$grpIdArg'")
                        0
                    }
                    else -> {
                        out("Unknown group subcommand: $sub")
                        1
                    }
                }
            }

            "status", "list" -> {
                engine.arbiter.refreshStates()
                val filterQuery = findFlagValue(args, "--filter")?.trim()?.lowercase()
                val states = engine.arbiter.states.value
                val workspaces = engine.workspaceRepository.findAll().filter { ws ->
                    if (filterQuery.isNullOrBlank()) true
                    else {
                        val stName = states[ws.id]?.toString()?.lowercase().orEmpty()
                        ws.displayName.lowercase().contains(filterQuery) ||
                            ws.id.value.lowercase().contains(filterQuery) ||
                            ws.tags.any { it.lowercase().contains(filterQuery) } ||
                            stName.contains(filterQuery)
                    }
                }
                val format = if ("--json" in args) "json" else (findFlagValue(args, "--format")?.lowercase() ?: "table")
                when (format) {
                    "json" -> out(json.encodeToString(workspaces))
                    "csv" -> {
                        out("id,display_name,format,state,local_path")
                        for (ws in workspaces) {
                            val st = states[ws.id]
                            out("${ws.id.value},\"${ws.displayName.replace("\"", "\"\"")}\",${ws.vaultFormat.fileExtension},$st,\"${ws.resolvedLocalPath()}\"")
                        }
                    }
                    else -> {
                        if (workspaces.isEmpty()) {
                            out("No workspaces registered.")
                        } else {
                            for (ws in workspaces) {
                                val st = states[ws.id]
                                out("${ws.id.value}\t${ws.displayName}\t${ws.vaultFormat.fileExtension}\t$st\t${ws.resolvedLocalPath()}")
                            }
                        }
                    }
                }
                0
            }

            "password" -> {
                val sub = pos.getOrNull(1)?.lowercase()
                val wsIdArg = pos.getOrNull(2)
                if (sub == null || wsIdArg == null) {
                    out("Usage: bstage password <change|rotate> <workspace-id>")
                    return 1
                }
                val ws = engine.workspaceRepository.findById(WorkspaceId(wsIdArg))
                    ?: run {
                        out("✗ Unknown workspace: $wsIdArg")
                        return 1
                    }
                val backend = VaultBackendFactory.create(ws.vaultBackend)
                val keyState = engine.passwordVerifier.assess(ws, backend)
                val currentVaultKey = when (keyState) {
                    is VaultKeyState.Ready -> keyState.vaultKey
                    is VaultKeyState.NeedsPassword -> {
                        val currentPwd = promptCliPassword(ws.id, keyState, passwordReader, out) ?: run {
                            out("✗ Current password input cancelled.")
                            return 1
                        }
                        engine.passwordVerifier.verify(currentPwd, keyState) ?: run {
                            out("✗ Incorrect current password.")
                            return 1
                        }
                    }
                }
                val newPassword = passwordReader("Enter new password for '${ws.displayName}': ")
                    ?: run {
                        currentVaultKey.fill(0)
                        out("✗ Password input cancelled.")
                        return 1
                    }
                try {
                    when (sub) {
                        "change" -> {
                            engine.passwordChanger.changePassword(
                                workspace = ws,
                                newPassword = newPassword,
                                backend = backend,
                                currentVaultKeyOverride = currentVaultKey,
                            )
                            out("✓ Password updated (fast header patch).")
                            0
                        }
                        "rotate" -> {
                            var lastLoggedPct = -1
                            engine.passwordChanger.rotateVaultKey(
                                workspace = ws,
                                newPassword = newPassword,
                                backend = backend,
                                currentVaultKeyOverride = currentVaultKey,
                            ) { fraction ->
                                val pct = (fraction * 100).toInt()
                                if (pct >= lastLoggedPct + 20 || pct == 100) {
                                    lastLoggedPct = pct
                                    out("  Re-encrypting chunks: $pct%...")
                                }
                            }
                            out("✓ Vault key rotated and all chunks re-encrypted. Other devices must re-enter the new password.")
                            0
                        }
                        else -> {
                            out("Unknown password subcommand: $sub")
                            1
                        }
                    }
                } finally {
                    currentVaultKey.fill(0)
                }
            }

            "keystore" -> {
                val sub = pos.getOrNull(1)?.lowercase()
                val wsIdArg = pos.getOrNull(2)
                if (sub != "forget" || wsIdArg == null) {
                    out("Usage: bstage keystore forget <workspace-id>")
                    return 1
                }
                val ws = engine.workspaceRepository.findById(WorkspaceId(wsIdArg))
                    ?: run {
                        out("✗ Unknown workspace: $wsIdArg")
                        return 1
                    }
                val alias = (ws.keyMode as? WorkspaceKeyMode.PasswordBacked)?.keystoreAlias
                    ?: WorkspaceConfig.defaultKeystoreAlias(ws.id)
                engine.keyProvider.delete(alias)
                out("✓ Removed vault key for '${ws.displayName}' from this device's OS keystore.")
                0
            }

            "vault" -> {
                val sub = pos.getOrNull(1)?.lowercase()
                val wsIdArg = pos.getOrNull(2)
                val targetStr = findFlagValue(args, "--to")?.lowercase()
                if (sub != "migrate" || wsIdArg == null || targetStr == null) {
                    out("Usage: bstage vault migrate <workspace-id> --to <7z|plain-7z>")
                    return 1
                }
                val targetFormat = when (targetStr) {
                    "native", "bstage" -> VaultFormatConfig.Native
                    "portable", "7z", "encrypted-7z" -> VaultFormatConfig.Portable
                    "plain-7z", "plain7z", "unencrypted-7z", "7z-plain" -> VaultFormatConfig.PlainSevenZip
                    "plain", "bvault" -> VaultFormatConfig.PlainCompressed
                    else -> {
                        out("✗ Unknown vault format: $targetStr")
                        return 1
                    }
                }
                val pwd = if (targetFormat.isEncrypted) {
                    passwordReader("Enter workspace password for migration: ")
                } else {
                    null
                }
                val wsId = WorkspaceId(wsIdArg)
                engine.arbiter.migrateVaultFormat(
                    workspaceId = wsId,
                    targetFormat = targetFormat,
                    passwordForEncryption = pwd,
                ) { needsPwd ->
                    promptCliPassword(wsId, needsPwd, passwordReader, out)
                }
                out("✓ Migrated '$wsIdArg' to ${targetFormat.fileExtension}")
                0
            }

            "import" -> {
                val vaultPathArg = pos.getOrNull(1)
                val targetDirArg = pos.getOrNull(2)
                if (vaultPathArg == null || targetDirArg == null) {
                    out("Usage: bstage import <vault-file-path> <local-target-dir> [--name <display-name>]")
                    return 1
                }
                val customName = findFlagValue(args, "--name")
                val imported = engine.arbiter.importExistingVault(
                    vaultFile = File(vaultPathArg),
                    localTargetDirectory = File(targetDirArg),
                    displayNameOverride = customName,
                )
                out("✓ Imported workspace '${imported.displayName}' (${imported.id.value})")
                0
            }

            "prefetch" -> {
                val wsIdArg = pos.getOrNull(1)
                if (wsIdArg != null) {
                    val staged = engine.arbiter.stageEncryptedPrefetch(WorkspaceId(wsIdArg))
                    out("✓ Staged encrypted prefetch for '$wsIdArg' at ${staged.absolutePath}")
                } else {
                    val stagedList = engine.lifecycleDaemon.runScheduledPrefetches()
                    out("✓ Prefetched ${stagedList.size} scheduled workspace(s).")
                }
                0
            }

            "audit" -> {
                val entries = engine.auditLogRepository.findRecent(50)
                if (entries.isEmpty()) {
                    out("No audit log entries recorded.")
                } else {
                    for (entry in entries) {
                        val wsLabel = entry.workspaceId?.value ?: "system"
                        out("${entry.occurredAtEpochSeconds}\t${entry.eventType.name}\t$wsLabel\t${entry.detailJson.orEmpty()}")
                    }
                }
                0
            }

            "config" -> {
                val sub = pos.getOrNull(1)?.lowercase()
                val key = pos.getOrNull(2)
                when (sub) {
                    "get" -> {
                        if (key == null) {
                            engine.appConfigRepository.getAll().forEach { (k, v) -> out("$k = $v") }
                        } else {
                            out("${engine.appConfigRepository.get(key)}")
                        }
                        0
                    }
                    "set" -> {
                        val value = pos.getOrNull(3)
                        if (key == null || value == null) {
                            out("Usage: bstage config set <key> <value>")
                            return 1
                        }
                        engine.appConfigRepository.set(key, value)
                        out("✓ Set $key = $value")
                        0
                    }
                    else -> {
                        out("Usage: bstage config <get|set> [key] [value]")
                        1
                    }
                }
            }

            else -> {
                out("Unknown command: $cmd")
                printUsage(out)
                1
            }
        }
    }

    private fun promptCliPassword(
        workspaceId: WorkspaceId,
        needsPwd: VaultKeyState.NeedsPassword,
        passwordReader: (String) -> String?,
        out: (String) -> Unit,
    ): String? {
        val reasonMsg = when (needsPwd.reason) {
            PasswordReason.INITIAL_CREATION -> "Initial encryption password required for '${workspaceId.value}'."
            PasswordReason.NEW_DEVICE -> "Workspace '${workspaceId.value}' is new on this device."
            PasswordReason.KEY_ROTATED -> "Vault key for '${workspaceId.value}' was rotated on another device."
            PasswordReason.ALWAYS_REQUIRED -> "Workspace '${workspaceId.value}' requires manual password entry."
        }
        out("🔑 $reasonMsg")
        if (!needsPwd.hint.isNullOrBlank()) {
            out("   Hint: ${needsPwd.hint}")
        }
        return passwordReader("   Password: ")
    }

    private fun readPasswordFromConsole(prompt: String): String? {
        val console = System.console()
        return if (console != null) {
            console.readPassword("%s", prompt)?.concatToString()
        } else {
            print(prompt)
            readlnOrNull()
        }
    }

    private fun printUsage(out: (String) -> Unit) {
        out(
            """
            BackStage CLI (bstage) — Encrypted Workspace Lifecycle Manager
            Note: Device credential authentication is always verified on startup.

            Commands:
              bstage swap <workspace-id> [--headless] [--force]
              bstage evict <workspace-id> [--headless] [--force]
              bstage import <vault-file-path> <local-target-dir> [--name <display-name>]
              bstage prefetch [<workspace-id>]
              bstage group <load|evict> <group-id> [--strategy parallel|sequential|space_aware]
              bstage status [--json]
              bstage list [--json]
              bstage password <change|rotate> <workspace-id>
              bstage keystore forget <workspace-id>
              bstage vault migrate <workspace-id> --to <native|portable|plain>
              bstage config <get|set> [key] [value]
            """.trimIndent()
        )
    }
}

fun main(args: Array<String>) {
    val exitCode = BackStageCliRunner.execute(args)
    if (exitCode != 0) {
        kotlin.system.exitProcess(exitCode)
    }
}
