package dev.legendsayantan.backstage.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.legendsayantan.backstage.core.BackStageEngine
import dev.legendsayantan.backstage.core.auth.AuthResult
import dev.legendsayantan.backstage.core.backend.MountedDriveDetector
import dev.legendsayantan.backstage.core.crypto.PasswordReason
import dev.legendsayantan.backstage.core.crypto.VaultFormatConfig
import dev.legendsayantan.backstage.core.crypto.VaultKeyState
import dev.legendsayantan.backstage.core.crypto.WorkspaceKeyMode
import dev.legendsayantan.backstage.core.error.BackStageError
import dev.legendsayantan.backstage.core.error.BackStageException
import dev.legendsayantan.backstage.core.io.FolderLock
import dev.legendsayantan.backstage.core.io.LauncherGenerator
import dev.legendsayantan.backstage.core.platform.SecureRandomProvider
import dev.legendsayantan.backstage.core.platform.Platform
import dev.legendsayantan.backstage.core.platform.currentPlatform
import dev.legendsayantan.backstage.core.workspace.AuditEventType
import dev.legendsayantan.backstage.core.workspace.AuditLogEntry
import dev.legendsayantan.backstage.core.workspace.GroupId
import dev.legendsayantan.backstage.core.workspace.LoadStrategy
import dev.legendsayantan.backstage.core.workspace.PrefetchSchedule
import dev.legendsayantan.backstage.core.workspace.RetentionPolicy
import dev.legendsayantan.backstage.core.workspace.VaultBackendConfig
import dev.legendsayantan.backstage.core.workspace.WorkspaceConfig
import dev.legendsayantan.backstage.core.workspace.WorkspaceGroup
import dev.legendsayantan.backstage.core.workspace.WorkspaceId
import dev.legendsayantan.backstage.core.workspace.WorkspaceOperation
import dev.legendsayantan.backstage.core.workspace.WorkspaceState
import dev.legendsayantan.backstage.ui.theme.AccentPrimary
import dev.legendsayantan.backstage.ui.theme.AccentSecondary
import dev.legendsayantan.backstage.ui.theme.BackStageTheme
import dev.legendsayantan.backstage.ui.theme.BgBase
import dev.legendsayantan.backstage.ui.theme.BgSurface
import dev.legendsayantan.backstage.ui.theme.BgSurfaceHover
import dev.legendsayantan.backstage.ui.theme.BorderColor
import dev.legendsayantan.backstage.ui.theme.DangerColor
import dev.legendsayantan.backstage.ui.theme.SuccessColor
import dev.legendsayantan.backstage.ui.theme.TextMain
import dev.legendsayantan.backstage.ui.theme.TextMuted
import dev.legendsayantan.backstage.ui.theme.WarningColor
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ActivePasswordPrompt(
    val workspace: WorkspaceConfig,
    val needsPassword: VaultKeyState.NeedsPassword,
    val deferredPassword: CompletableDeferred<String?>,
)

private fun formatUserFriendlyError(err: Throwable): String {
    val bs = err as? BackStageException
    return when (val code = bs?.error) {
        is BackStageError.DirectoryLocked -> {
            val apps = code.openHandles.map { it.processName }.distinct().take(3).joinToString(", ")
            "Files are currently open in $apps. Close them or use Force Archive."
        }
        is BackStageError.InsufficientSpace -> {
            val deficitMb = ((code.requiredBytes - code.availableBytes).coerceAtLeast(0L)) / (1024 * 1024)
            "Not enough free disk space (need ~${deficitMb.coerceAtLeast(1)} MB more)."
        }
        is BackStageError.WrongPassword -> "Incorrect password."
        is BackStageError.PasswordRequired -> "Password is required to unlock this vault."
        is BackStageError.VaultNotFound -> "Vault file '${code.fileName}' was not found at its storage location."
        is BackStageError.MountedDriveNotFound -> "Drive '${code.driveName}' is not currently mounted."
        is BackStageError.HeaderMacInvalid -> "Vault header integrity check failed — check your password."
        is BackStageError.ChunkCorrupted -> "Archive block #${code.chunkIndex} failed integrity verification."
        is BackStageError.SevenZipNotInstalled -> "7-Zip is not installed on this system."
        else -> err.message ?: "Operation failed"
    }
}

private fun resolveVaultDirPath(backend: VaultBackendConfig): String {
    return when (backend) {
        is VaultBackendConfig.LocalPath -> backend.directoryPath
        is VaultBackendConfig.MountedDrive -> {
            val root = backend.platformRoots[currentPlatform()]
                ?: MountedDriveDetector.detectMountRoot(backend.driveType)?.absolutePath
                ?: ""
            if (root.isBlank()) backend.relativePath
            else if (backend.relativePath.isBlank()) root
            else File(root, backend.relativePath).absolutePath
        }
        is VaultBackendConfig.ExternalDrive -> backend.relativePath
        is VaultBackendConfig.S3Compatible -> backend.endpointUrl
        is VaultBackendConfig.WebDav -> backend.endpointUrl
    }
}

private fun buildVaultBackendForPath(
    rawPath: String,
    previousBackend: VaultBackendConfig? = null,
): VaultBackendConfig {
    val trimmed = rawPath.trim()
    val targetFile = File(trimmed).absoluteFile
    val targetNorm = targetFile.toPath().normalize()
    val platform = currentPlatform()

    val matchedCloud = runCatching {
        MountedDriveDetector.discoverMountedVolumes(platform)
            .filter { it.isCloudMount && it.driveType != null }
            .firstOrNull { vol ->
                val mountNorm = vol.mountPoint.absoluteFile.toPath().normalize()
                targetNorm.startsWith(mountNorm)
            }
    }.getOrNull()

    val matchedDriveType = matchedCloud?.driveType
    if (matchedCloud != null && matchedDriveType != null) {
        val mountNorm = matchedCloud.mountPoint.absoluteFile.toPath().normalize()
        val rel = if (targetNorm == mountNorm) {
            ""
        } else {
            mountNorm.relativize(targetNorm).toString().replace('\\', '/')
        }
        val prevRoots = (previousBackend as? VaultBackendConfig.MountedDrive)?.platformRoots ?: emptyMap()
        return VaultBackendConfig.MountedDrive(
            driveName = matchedCloud.name,
            driveType = matchedDriveType,
            relativePath = rel,
            platformRoots = prevRoots + (platform to matchedCloud.mountPoint.absolutePath),
        )
    }

    return VaultBackendConfig.LocalPath(trimmed)
}

@Composable
fun BackStageApp(
    engine: BackStageEngine,
    onBringToFront: (() -> Unit)? = null,
) {
    BackStageTheme {
        val scope = rememberCoroutineScope()
        var isAuthenticated by remember { mutableStateOf(engine.isUnlocked) }
        var authErrorMessage by remember { mutableStateOf<String?>(null) }
        var isAuthenticating by remember { mutableStateOf(false) }

        var workspaces by remember { mutableStateOf<List<WorkspaceConfig>>(emptyList()) }
        var groups by remember { mutableStateOf<List<WorkspaceGroup>>(emptyList()) }
        var selectedWorkspaceForSettings by remember { mutableStateOf<WorkspaceConfig?>(null) }
        var showNewWorkspaceDialog by remember { mutableStateOf(false) }
        var showImportVaultDialog by remember { mutableStateOf(false) }
        var showManageGroupsDialog by remember { mutableStateOf(false) }
        var showPreferencesDialog by remember { mutableStateOf(false) }
        var activePasswordPrompt by remember { mutableStateOf<ActivePasswordPrompt?>(null) }
        var bannerFeedback by remember { mutableStateOf<String?>(null) }
        var inFlightWorkspaces by remember { mutableStateOf(setOf<WorkspaceId>()) }
        var inFlightGroups by remember { mutableStateOf(setOf<GroupId>()) }

        fun reloadWorkspaces() {
            if (engine.isUnlocked) {
                scope.launch(Dispatchers.IO) {
                    try {
                        val list = engine.workspaceRepository.findAll()
                        val grpList = engine.groupRepository.findAll()
                        withContext(Dispatchers.Main) {
                            workspaces = list
                            groups = grpList
                        }
                        println("[BackStage] Loaded ${list.size} workspaces and ${grpList.size} groups.")
                        engine.arbiter.refreshStates()
                    } catch (t: Throwable) {
                        System.err.println("[BackStage ERROR] reloadWorkspaces failed: ${t.message}")
                        t.printStackTrace()
                    }
                }
            }
        }

        LaunchedEffect(Unit) {
            if (!isAuthenticated) {
                println("[BackStage] Prompting for device credential authentication...")
                isAuthenticating = true
                val res = try {
                    engine.authenticateAndUnlock()
                } catch (t: Throwable) {
                    System.err.println("[BackStage ERROR] authenticateAndUnlock thrown exception: ${t.message}")
                    t.printStackTrace()
                    AuthResult.Failure(t.message ?: "Authentication error")
                }
                println("[BackStage] Authentication completed with result: $res")
                isAuthenticating = false
                when (res) {
                    is AuthResult.Success -> {
                        isAuthenticated = true
                        authErrorMessage = null
                        reloadWorkspaces()
                        onBringToFront?.invoke()
                    }
                    is AuthResult.Failure -> {
                        authErrorMessage = res.reason
                        onBringToFront?.invoke()
                    }
                    is AuthResult.Unavailable -> {
                        authErrorMessage = "Authentication unavailable."
                        onBringToFront?.invoke()
                    }
                }
            } else {
                reloadWorkspaces()
                onBringToFront?.invoke()
            }
        }

        LaunchedEffect(isAuthenticated) {
            if (isAuthenticated) {
                onBringToFront?.invoke()
            }
            if (isAuthenticated && engine.isUnlocked) {
                engine.lifecycleDaemon.prefetchFailures.collect { failure ->
                    bannerFeedback = "Could not stage '${failure.displayName}': ${failure.errorMessage}"
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxSize(),
            color = BgBase,
        ) {
            if (!isAuthenticated) {
                DeviceCredentialGateScreen(
                    isAuthenticating = isAuthenticating,
                    errorMessage = authErrorMessage,
                    onAuthenticateClick = {
                        scope.launch {
                            isAuthenticating = true
                            authErrorMessage = null
                            val res = try {
                                engine.authenticateAndUnlock()
                            } catch (t: Throwable) {
                                System.err.println("[BackStage ERROR] authenticateAndUnlock exception: ${t.message}")
                                t.printStackTrace()
                                AuthResult.Failure(t.message ?: "Authentication error")
                            }
                            isAuthenticating = false
                            when (res) {
                                is AuthResult.Success -> {
                                    isAuthenticated = true
                                    reloadWorkspaces()
                                    onBringToFront?.invoke()
                                }
                                is AuthResult.Failure -> {
                                    authErrorMessage = res.reason
                                    onBringToFront?.invoke()
                                }
                                is AuthResult.Unavailable -> {
                                    authErrorMessage = "Authentication unavailable."
                                    onBringToFront?.invoke()
                                }
                            }
                        }
                    },
                )
            } else {
                val stateMap by engine.arbiter.states.collectAsState()
                val persistentSecurityWarning = listOfNotNull(
                    engine.keyProvider.degradedProtectionWarning,
                    dev.legendsayantan.backstage.core.db.DatabaseSchemaInitializer.lastDatabaseRecoveryWarning,
                ).joinToString("\n").takeIf { it.isNotBlank() }

                Box(modifier = Modifier.fillMaxSize()) {
                    val settingsTarget = selectedWorkspaceForSettings
                    if (settingsTarget != null) {
                        WorkspaceSettingsScreen(
                            engine = engine,
                            workspace = settingsTarget,
                            groups = groups,
                            onBack = {
                                selectedWorkspaceForSettings = null
                                reloadWorkspaces()
                            },
                            onFeedback = { bannerFeedback = it },
                            onDeleted = {
                                selectedWorkspaceForSettings = null
                                bannerFeedback = "Removed '${settingsTarget.displayName}'"
                                reloadWorkspaces()
                            },
                        )
                    } else {
                        DashboardScreen(
                            workspaces = workspaces,
                            groups = groups,
                            states = stateMap,
                            persistentWarning = persistentSecurityWarning,
                            bannerMessage = bannerFeedback,
                            onDismissBanner = { bannerFeedback = null },
                            onNewWorkspaceClick = { showNewWorkspaceDialog = true },
                            onImportVaultClick = { showImportVaultDialog = true },
                            onManageGroupsClick = { showManageGroupsDialog = true },
                            onOpenPreferencesClick = { showPreferencesDialog = true },
                            onOpenSettings = { selectedWorkspaceForSettings = it },
                            inFlightWorkspaces = inFlightWorkspaces,
                            inFlightGroups = inFlightGroups,
                            onLoadWorkspace = { ws ->
                                if (ws.id !in inFlightWorkspaces && engine.arbiter.currentState(ws.id) !is WorkspaceState.Busy) {
                                    inFlightWorkspaces = inFlightWorkspaces + ws.id
                                    scope.launch {
                                        try {
                                            runCatching {
                                                engine.arbiter.loadWorkspace(ws.id) { needsPwd ->
                                                    val deferred = CompletableDeferred<String?>()
                                                    activePasswordPrompt = ActivePasswordPrompt(ws, needsPwd, deferred)
                                                    val result = deferred.await()
                                                    activePasswordPrompt = null
                                                    result
                                                }
                                                bannerFeedback = "Restored '${ws.displayName}'"
                                                reloadWorkspaces()
                                            }.onFailure { err ->
                                                bannerFeedback = formatUserFriendlyError(err)
                                            }
                                        } finally {
                                            inFlightWorkspaces = inFlightWorkspaces - ws.id
                                        }
                                    }
                                }
                            },
                            onEvictWorkspace = { ws, forceIgnoreHandles ->
                                if (ws.id !in inFlightWorkspaces && engine.arbiter.currentState(ws.id) !is WorkspaceState.Busy) {
                                    inFlightWorkspaces = inFlightWorkspaces + ws.id
                                    scope.launch {
                                        try {
                                            runCatching {
                                                engine.arbiter.evictWorkspace(
                                                    workspaceId = ws.id,
                                                    forceIgnoreHandles = forceIgnoreHandles,
                                                ) { needsPwd ->
                                                    val deferred = CompletableDeferred<String?>()
                                                    activePasswordPrompt = ActivePasswordPrompt(ws, needsPwd, deferred)
                                                    val result = deferred.await()
                                                    activePasswordPrompt = null
                                                    result
                                                }
                                                bannerFeedback = "Archived '${ws.displayName}'"
                                                reloadWorkspaces()
                                            }.onFailure { err ->
                                                bannerFeedback = formatUserFriendlyError(err)
                                            }
                                        } finally {
                                            inFlightWorkspaces = inFlightWorkspaces - ws.id
                                        }
                                    }
                                }
                            },
                            onStageWorkspace = { ws ->
                                if (ws.id !in inFlightWorkspaces && engine.arbiter.currentState(ws.id) !is WorkspaceState.Busy) {
                                    inFlightWorkspaces = inFlightWorkspaces + ws.id
                                    scope.launch {
                                        try {
                                            runCatching {
                                                engine.arbiter.stageEncryptedPrefetch(ws.id)
                                                bannerFeedback = "Staged '${ws.displayName}' for instant restore"
                                                reloadWorkspaces()
                                            }.onFailure { err ->
                                                bannerFeedback = formatUserFriendlyError(err)
                                            }
                                        } finally {
                                            inFlightWorkspaces = inFlightWorkspaces - ws.id
                                        }
                                    }
                                }
                            },
                            onLoadGroup = { grp ->
                                if (grp.id !in inFlightGroups) {
                                    inFlightGroups = inFlightGroups + grp.id
                                    scope.launch {
                                        try {
                                            runCatching {
                                                engine.lifecycleDaemon.loadGroup(grp.id) { needsPwd ->
                                                    val dummyWs = workspaces.firstOrNull { it.groupId == grp.id }
                                                        ?: workspaces.firstOrNull()
                                                    if (dummyWs == null) null
                                                    else {
                                                        val deferred = CompletableDeferred<String?>()
                                                        activePasswordPrompt = ActivePasswordPrompt(dummyWs, needsPwd, deferred)
                                                        val result = deferred.await()
                                                        activePasswordPrompt = null
                                                        result
                                                    }
                                                }
                                                bannerFeedback = "Restored group '${grp.displayName}'"
                                                reloadWorkspaces()
                                            }.onFailure { err ->
                                                bannerFeedback = formatUserFriendlyError(err)
                                            }
                                        } finally {
                                            inFlightGroups = inFlightGroups - grp.id
                                        }
                                    }
                                }
                            },
                            onEvictGroup = { grp ->
                                if (grp.id !in inFlightGroups) {
                                    inFlightGroups = inFlightGroups + grp.id
                                    scope.launch {
                                        try {
                                            runCatching {
                                                engine.lifecycleDaemon.evictGroup(grp.id) { needsPwd ->
                                                    val dummyWs = workspaces.firstOrNull { it.groupId == grp.id }
                                                        ?: workspaces.firstOrNull()
                                                    if (dummyWs == null) null
                                                    else {
                                                        val deferred = CompletableDeferred<String?>()
                                                        activePasswordPrompt = ActivePasswordPrompt(dummyWs, needsPwd, deferred)
                                                        val result = deferred.await()
                                                        activePasswordPrompt = null
                                                        result
                                                    }
                                                }
                                                bannerFeedback = "Archived group '${grp.displayName}'"
                                                reloadWorkspaces()
                                            }.onFailure { err ->
                                                bannerFeedback = formatUserFriendlyError(err)
                                            }
                                        } finally {
                                            inFlightGroups = inFlightGroups - grp.id
                                        }
                                    }
                                }
                            },
                        )
                    }

                    val busyEntries = remember { engine.arbiter.states }
                        .collectAsState().value
                        .entries.mapNotNull { (id, st) ->
                            (st as? WorkspaceState.Busy)?.let { id to it }
                        }
                    AnimatedVisibility(
                        visible = busyEntries.isNotEmpty() && selectedWorkspaceForSettings != null,
                        enter = fadeIn() + expandVertically(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)),
                        exit = fadeOut() + shrinkVertically(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(24.dp),
                    ) {
                        SwapProgressHud(busyEntries = busyEntries, workspaces = workspaces)
                    }

                    activePasswordPrompt?.let { prompt ->
                        PasswordPromptDialog(
                            engine = engine,
                            prompt = prompt,
                            onCancel = {
                                prompt.deferredPassword.complete(null)
                                activePasswordPrompt = null
                            },
                            onVerified = { verifiedPassword ->
                                prompt.deferredPassword.complete(verifiedPassword)
                            },
                        )
                    }

                    if (showNewWorkspaceDialog) {
                        NewWorkspaceDialog(
                            engine = engine,
                            groups = groups,
                            onDismiss = { showNewWorkspaceDialog = false },
                            onCreated = { created, initialPassword ->
                                showNewWorkspaceDialog = false
                                workspaces = workspaces + created
                                engine.arbiter.updateState(created.id, WorkspaceState.Active)
                                scope.launch {
                                    val localDir = java.io.File(created.resolvedLocalPath())
                                    withContext(Dispatchers.IO) {
                                        runCatching { localDir.mkdirs() }
                                        runCatching {
                                            FolderLock.writeLock(localDir, created.id, snapshot = emptyList())
                                        }
                                        if (initialPassword != null && created.vaultFormat.isEncrypted) {
                                            runCatching {
                                                val initMaterial = engine.passwordVerifier.initializeWorkspaceKey(created, initialPassword)
                                                val backend = dev.legendsayantan.backstage.core.backend.VaultBackendFactory.create(created.vaultBackend)
                                                val sidecarName = dev.legendsayantan.backstage.core.crypto.BsmetaSidecar.sidecarFileName(created.vaultFileName)
                                                val macCover = initMaterial.argon2Salt + initMaterial.wrappedKey
                                                val headerMac = dev.legendsayantan.backstage.core.crypto.Blake3.keyedHash(macCover, initMaterial.vaultKey)
                                                val sidecar = dev.legendsayantan.backstage.core.crypto.BsmetaSidecar(
                                                    workspaceId = created.id.value,
                                                    displayName = created.displayName,
                                                    platformOrigin = created.platformOrigin,
                                                    vaultFormat = created.vaultFormat.fileExtension,
                                                    archiveFilename = created.vaultFileName,
                                                    passwordHint = (created.keyMode as? WorkspaceKeyMode.PasswordBacked)?.passwordHint,
                                                    headerMacHex = headerMac.toHexString(),
                                                    kdf = dev.legendsayantan.backstage.core.crypto.BsmetaKdfInfo(
                                                        saltHex = initMaterial.argon2Salt.toHexString(),
                                                        wrappedKeyHex = initMaterial.wrappedKey.toHexString(),
                                                    )
                                                )
                                                backend.writeAllBytes(sidecarName, dev.legendsayantan.backstage.core.crypto.BsmetaSidecar.encode(sidecar).encodeToByteArray())
                                            }
                                        }
                                    }
                                    bannerFeedback = "Created '${created.displayName}'"
                                    reloadWorkspaces()
                                }
                            },
                        )
                    }

                    if (showImportVaultDialog) {
                        ImportVaultDialog(
                            engine = engine,
                            onDismiss = { showImportVaultDialog = false },
                            onImported = { imported ->
                                showImportVaultDialog = false
                                bannerFeedback = "Imported '${imported.displayName}'"
                                reloadWorkspaces()
                            },
                        )
                    }

                    if (showManageGroupsDialog) {
                        ManageGroupsDialog(
                            engine = engine,
                            workspaces = workspaces,
                            groups = groups,
                            onDismiss = {
                                showManageGroupsDialog = false
                                reloadWorkspaces()
                            },
                            onFeedback = {
                                bannerFeedback = it
                                reloadWorkspaces()
                            },
                        )
                    }

                    if (showPreferencesDialog) {
                        PreferencesAndActivityDialog(
                            engine = engine,
                            workspaces = workspaces,
                            onDismiss = {
                                showPreferencesDialog = false
                                reloadWorkspaces()
                            },
                            onFeedback = {
                                bannerFeedback = it
                                reloadWorkspaces()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Minimal, clean lock screen — simply says "Locked" with an "Unlock" action.
 */
@Composable
private fun DeviceCredentialGateScreen(
    isAuthenticating: Boolean,
    errorMessage: String?,
    onAuthenticateClick: () -> Unit,
) {
    LaunchedEffect(isAuthenticating, errorMessage) {
        println("[BackStage] Lock screen displayed (authenticating=$isAuthenticating, error=$errorMessage)")
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                color = BgSurface,
                shape = CircleShape,
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.size(64.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    BackStageGlyph(
                        type = GlyphType.LOCK,
                        color = AccentPrimary,
                        size = 26.dp,
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Locked",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextMain,
                letterSpacing = 0.4.sp,
            )

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = errorMessage,
                    color = DangerColor,
                    fontSize = 12.sp,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (isAuthenticating) {
                CircularProgressIndicator(
                    color = AccentPrimary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(24.dp),
                )
            } else {
                Button(
                    onClick = onAuthenticateClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentPrimary,
                        contentColor = Color(0xFF12100B),
                    ),
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                ) {
                    Text("Unlock", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    workspaces: List<WorkspaceConfig>,
    groups: List<WorkspaceGroup>,
    states: Map<WorkspaceId, WorkspaceState>,
    persistentWarning: String?,
    bannerMessage: String?,
    onDismissBanner: () -> Unit,
    onNewWorkspaceClick: () -> Unit,
    onImportVaultClick: () -> Unit,
    onManageGroupsClick: () -> Unit,
    onOpenPreferencesClick: () -> Unit,
    onOpenSettings: (WorkspaceConfig) -> Unit,
    inFlightWorkspaces: Set<WorkspaceId> = emptySet(),
    inFlightGroups: Set<GroupId> = emptySet(),
    onLoadWorkspace: (WorkspaceConfig) -> Unit,
    onEvictWorkspace: (WorkspaceConfig, Boolean) -> Unit,
    onStageWorkspace: (WorkspaceConfig) -> Unit,
    onLoadGroup: (WorkspaceGroup) -> Unit,
    onEvictGroup: (WorkspaceGroup) -> Unit,
) {
    LaunchedEffect(workspaces.size, groups.size) {
        println("[BackStage] DashboardScreen active: ${workspaces.size} workspaces, ${groups.size} groups.")
    }

    var searchQuery by remember { mutableStateOf("") }
    var selectedStateFilter by remember { mutableStateOf("All") } // All, Active, Archived, Ready
    var selectedGroupId by remember { mutableStateOf<GroupId?>(null) }

    val filteredWorkspaces = remember(workspaces, states, searchQuery, selectedStateFilter, selectedGroupId) {
        workspaces.filter { ws ->
            val st = states[ws.id] ?: WorkspaceState.Vaulted
            val matchesSearch = searchQuery.isBlank() ||
                ws.displayName.contains(searchQuery.trim(), ignoreCase = true) ||
                ws.resolvedLocalPath().contains(searchQuery.trim(), ignoreCase = true) ||
                ws.tags.any { it.contains(searchQuery.trim(), ignoreCase = true) }
            val matchesState = when (selectedStateFilter) {
                "Active" -> st is WorkspaceState.Active
                "Archived" -> st is WorkspaceState.Vaulted
                "Ready" -> st is WorkspaceState.StagedEncrypted
                else -> true
            }
            val matchesGroup = selectedGroupId == null || ws.groupId == selectedGroupId
            matchesSearch && matchesState && matchesGroup
        }
    }

    val selectedGroup = remember(groups, selectedGroupId) {
        groups.firstOrNull { it.id == selectedGroupId }
    }

    var volumeStats by remember { mutableStateOf<List<Triple<String, Int, Int>>>(emptyList()) }

    LaunchedEffect(workspaces) {
        val stats = withContext(Dispatchers.IO) {
            val platform = currentPlatform()
            val mountedCloudVolumes = runCatching {
                MountedDriveDetector.discoverMountedVolumes(platform)
            }.getOrDefault(emptyList()).filter { it.isCloudMount || it.driveType != null }

            val mountedRoots = mountedCloudVolumes.map {
                runCatching { it.mountPoint.canonicalPath.lowercase() }
                    .getOrDefault(it.mountPoint.absolutePath.lowercase())
            }.toSet()

            val defaultSystemPath = if (platform == Platform.WINDOWS) {
                System.getenv("SystemDrive")?.trimEnd('\\', '/') ?: "C:"
            } else {
                System.getProperty("user.home").orEmpty().ifBlank { "/" }
            }

            // Only inspect local active workspace paths (never cold vault storage / mounted drives)
            val candidatePaths = workspaces
                .map { it.resolvedLocalPath().trim() }
                .filter { it.isNotBlank() && File(it).isAbsolute }
                .ifEmpty { listOf(defaultSystemPath) }

            val validVolumes = candidatePaths
                .mapNotNull { pathStr ->
                    val target = File(pathStr)
                    val probeDir = generateSequence(target) { it.parentFile }.firstOrNull { it.exists() }
                        ?: return@mapNotNull null
                    if (probeDir.totalSpace <= 0L) return@mapNotNull null
                    val root = generateSequence(target) { it.parentFile }.last()

                    // Exclude network / UNC paths
                    if (root.path.startsWith("\\\\")) return@mapNotNull null

                    // Exclude mounted cloud / virtual drives
                    val canonical = runCatching { probeDir.canonicalPath.lowercase() }
                        .getOrDefault(probeDir.absolutePath.lowercase())
                    if (mountedRoots.any { canonical == it || canonical.startsWith("$it\\") || canonical.startsWith("$it/") }) {
                        return@mapNotNull null
                    }

                    // Check FileStore name and type for virtual / cloud filesystems
                    val store = runCatching { java.nio.file.Files.getFileStore(probeDir.toPath()) }.getOrNull()
                    if (store != null) {
                        val name = runCatching { store.name().lowercase() }.getOrDefault("")
                        val type = runCatching { store.type().lowercase() }.getOrDefault("")
                        val isCloudOrVirtual = name.contains("google drive") ||
                            name.contains("onedrive") ||
                            name.contains("dropbox") ||
                            name.contains("icloud") ||
                            name.contains("nextcloud") ||
                            type.contains("winfsp") ||
                            type.contains("rclone") ||
                            type.contains("fuse") ||
                            type.contains("davfs") ||
                            type.contains("cifs") ||
                            type.contains("smb") ||
                            type.contains("nfs")
                        if (isCloudOrVirtual) return@mapNotNull null
                    }

                    val rootLabel = root.path.trimEnd('\\', '/').ifEmpty { "/" }
                    rootLabel to probeDir
                }
                .distinctBy { it.first.uppercase() }
                .sortedBy { it.first.uppercase() }

            val finalVolumes = if (validVolumes.isEmpty()) {
                val fallbackTarget = File(defaultSystemPath)
                val fallbackProbe = generateSequence(fallbackTarget) { it.parentFile }.firstOrNull { it.exists() }
                if (fallbackProbe != null && fallbackProbe.totalSpace > 0L) {
                    val root = generateSequence(fallbackTarget) { it.parentFile }.last()
                    val rootLabel = root.path.trimEnd('\\', '/').ifEmpty { "/" }
                    listOf(rootLabel to fallbackProbe)
                } else emptyList()
            } else {
                validVolumes
            }

            finalVolumes.map { (rootLabel, probeDir) ->
                val totalGb = (probeDir.totalSpace / (1024.0 * 1024.0 * 1024.0)).toInt().coerceAtLeast(1)
                val usedGb = ((probeDir.totalSpace - probeDir.usableSpace) / (1024.0 * 1024.0 * 1024.0)).toInt()
                Triple(rootLabel, usedGb, totalGb)
            }
        }
        volumeStats = stats
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 26.dp),
    ) {
        // Minimalist Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Workspaces",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMain,
                    modifier = Modifier.alignByBaseline(),
                )
                if (workspaces.isNotEmpty()) {
                    val activeCount = workspaces.count { (states[it.id] ?: WorkspaceState.Vaulted) is WorkspaceState.Active }
                    val readyCount = workspaces.count { (states[it.id] ?: WorkspaceState.Vaulted) is WorkspaceState.StagedEncrypted }
                    Text(
                        text = buildString {
                            append("${workspaces.size}")
                            if (activeCount > 0) append(" · $activeCount active")
                            if (readyCount > 0) append(" · $readyCount staged")
                        },
                        fontSize = 12.sp,
                        color = TextMuted,
                        modifier = Modifier.alignByBaseline(),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = onManageGroupsClick,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Text(
                        text = if (groups.isEmpty()) "Groups" else "Groups (${groups.size})",
                        color = TextMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                TextButton(
                    onClick = onImportVaultClick,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                ) {
                    Text("Import", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Surface(
                    color = BgSurface,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, BorderColor),
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenPreferencesClick),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        BackStageGlyph(type = GlyphType.SLIDERS, color = TextMuted, size = 14.dp)
                    }
                }
                Spacer(modifier = Modifier.width(2.dp))
                Button(
                    onClick = onNewWorkspaceClick,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AccentPrimary,
                        contentColor = Color(0xFF12100B),
                    ),
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BackStageGlyph(type = GlyphType.PLUS, color = Color(0xFF12100B), size = 11.dp)
                        Text("New", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (!persistentWarning.isNullOrBlank()) {
            Surface(
                color = WarningColor.copy(alpha = 0.12f),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, WarningColor),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = persistentWarning,
                    color = WarningColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        if (bannerMessage != null) {
            Surface(
                color = BgSurface,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(bannerMessage, color = TextMain, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = onDismissBanner,
                        modifier = Modifier.height(28.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("Dismiss", color = AccentPrimary, fontSize = 11.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        // Compact Disk Capacity Strip (Single Horizontal Row)
        if (volumeStats.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = BgSurface,
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    volumeStats.forEachIndexed { index, (volName, usedSpaceGb, totalSpaceGb) ->
                        if (index > 0) {
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(24.dp)
                                    .background(BorderColor),
                            )
                        }
                        val freeGb = (totalSpaceGb - usedSpaceGb).coerceAtLeast(0)
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = "Disk $volName",
                                    color = TextMain,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "$freeGb GB free of $totalSpaceGb GB",
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = (usedSpaceGb.toFloat() / totalSpaceGb.toFloat()).coerceIn(0f, 1f),
                                color = AccentPrimary,
                                trackColor = BgBase,
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            )
                        }
                    }
                }
            }
        }

        if (workspaces.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))

            // Unified Single-Line Minimalist Filter & Search Strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactSearchField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = "Filter workspaces...",
                    modifier = Modifier.weight(1f),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for ((filterKey, filterLabel) in listOf(
                        "All" to "All",
                        "Active" to "Active",
                        "Archived" to "Archived",
                        "Ready" to "Staged",
                    )) {
                        FilterChipPill(
                            text = filterLabel,
                            selected = selectedStateFilter == filterKey,
                            onClick = { selectedStateFilter = filterKey },
                        )
                    }
                }

                if (groups.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .height(18.dp)
                            .background(BorderColor),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        for (grp in groups) {
                            FilterChipPill(
                                text = grp.displayName,
                                selected = selectedGroupId == grp.id,
                                onClick = {
                                    selectedGroupId = if (selectedGroupId == grp.id) null else grp.id
                                },
                            )
                        }
                        if (selectedGroup != null) {
                            val isGroupInFlight = selectedGroup.id in inFlightGroups
                            TextButton(
                                onClick = { onLoadGroup(selectedGroup) },
                                enabled = !isGroupInFlight,
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text("Restore All", color = if (isGroupInFlight) TextMuted else AccentPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            TextButton(
                                onClick = { onEvictGroup(selectedGroup) },
                                enabled = !isGroupInFlight,
                                modifier = Modifier.height(30.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text("Archive All", color = if (isGroupInFlight) TextMuted.copy(alpha = 0.5f) else TextMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (workspaces.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        color = BgSurface,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderColor),
                        modifier = Modifier.size(46.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            BackStageGlyph(type = GlyphType.VAULT, color = TextMuted, size = 18.dp)
                        }
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "No workspaces yet",
                        color = TextMain,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Add a project folder to archive it when inactive and restore it on demand.",
                        color = TextMuted,
                        fontSize = 12.sp,
                    )
                }
            }
        } else if (filteredWorkspaces.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No matching workspaces",
                        color = TextMain,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TextButton(
                        onClick = {
                            searchQuery = ""
                            selectedStateFilter = "All"
                            selectedGroupId = null
                        }
                    ) {
                        Text("Clear filters", color = AccentPrimary, fontSize = 12.sp)
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filteredWorkspaces, key = { it.id.value }) { ws ->
                    val grpName = groups.firstOrNull { it.id == ws.groupId }?.displayName
                    WorkspaceCard(
                        workspace = ws,
                        groupName = grpName,
                        state = states[ws.id] ?: WorkspaceState.Vaulted,
                        isInFlight = ws.id in inFlightWorkspaces,
                        onLoad = { onLoadWorkspace(ws) },
                        onEvict = { force -> onEvictWorkspace(ws, force) },
                        onStage = { onStageWorkspace(ws) },
                        onSettings = { onOpenSettings(ws) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = BgSurface,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, BorderColor),
        modifier = modifier.height(32.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = TextMuted.copy(alpha = 0.65f),
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = TextMain,
                        fontSize = 12.sp,
                    ),
                    cursorBrush = SolidColor(AccentPrimary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (value.isNotEmpty()) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable { onValueChange("") },
                    contentAlignment = Alignment.Center,
                ) {
                    BackStageGlyph(type = GlyphType.CLOSE, color = TextMuted, size = 10.dp)
                }
            }
        }
    }
}

@Composable
private fun FilterChipPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (selected) AccentPrimary.copy(alpha = 0.14f) else Color.Transparent,
        shape = RoundedCornerShape(6.dp),
        border = if (selected) BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.65f)) else null,
        modifier = Modifier
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(horizontal = 10.dp),
        ) {
            Text(
                text = text,
                color = if (selected) AccentPrimary else TextMuted,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun WorkspaceCard(
    workspace: WorkspaceConfig,
    groupName: String?,
    state: WorkspaceState,
    isInFlight: Boolean = false,
    onLoad: () -> Unit,
    onEvict: (Boolean) -> Unit,
    onStage: () -> Unit,
    onSettings: () -> Unit,
) {
    val protectionLabel = when (workspace.vaultFormat) {
        VaultFormatConfig.Native -> "Legacy .bstage"
        VaultFormatConfig.Portable -> "7z"
        VaultFormatConfig.PlainSevenZip -> "7z (Unencrypted)"
        VaultFormatConfig.PlainCompressed -> "Legacy .bvault"
    }
    val (stateLabel, stateColor) = when (state) {
        WorkspaceState.Active -> "Active" to SuccessColor
        WorkspaceState.Vaulted -> "Archived" to TextMuted.copy(alpha = 0.55f)
        WorkspaceState.StagedEncrypted -> "Staged" to AccentSecondary
        WorkspaceState.Missing -> "Missing" to WarningColor
        is WorkspaceState.Busy -> {
            val opText = when (state.operation) {
                WorkspaceOperation.EVICTING, WorkspaceOperation.PURGING, WorkspaceOperation.LOCKING -> "Archiving"
                WorkspaceOperation.EXTRACTING, WorkspaceOperation.VALIDATING, WorkspaceOperation.KEY_CHECK, WorkspaceOperation.KEY_DERIVATION, WorkspaceOperation.ARBITRATING -> "Restoring"
                WorkspaceOperation.ROTATING_KEY -> "Updating"
                WorkspaceOperation.PASSWORD_PROMPT -> "Password Required"
            }
            opText to AccentPrimary
        }
        is WorkspaceState.Error -> "Attention" to DangerColor
    }
    val sizeInfo = workspace.uncompressedSizeBytes?.let { bytes ->
        val mb = bytes / (1024.0 * 1024.0)
        if (mb >= 1024.0) " · %.1f GB".format(mb / 1024.0)
        else if (mb >= 1.0) " · %.1f MB".format(mb)
        else " · ${bytes / 1024} KB"
    }.orEmpty()
    val groupAndTagsInfo = buildString {
        if (!groupName.isNullOrBlank()) append(" · $groupName")
        if (workspace.tags.isNotEmpty()) {
            append(" · ")
            append(workspace.tags.take(2).joinToString(" ") { "#$it" })
        }
    }

    Surface(
        shape = RoundedCornerShape(10.dp),
        color = BgSurface,
        border = BorderStroke(1.dp, if (state is WorkspaceState.Busy) AccentPrimary.copy(alpha = 0.45f) else BorderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = state !is WorkspaceState.Busy, onClick = onSettings),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(BgBase),
                        contentAlignment = Alignment.Center,
                    ) {
                        BackStageGlyph(
                            type = if (state is WorkspaceState.Active) GlyphType.FOLDER else GlyphType.VAULT,
                            color = when (state) {
                                is WorkspaceState.Active -> AccentPrimary
                                is WorkspaceState.StagedEncrypted -> AccentSecondary
                                is WorkspaceState.Busy -> AccentPrimary
                                else -> TextMuted
                            },
                            size = 15.dp,
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = workspace.displayName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMain,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(stateColor),
                                )
                                Text(
                                    text = stateLabel,
                                    color = if (state is WorkspaceState.Vaulted) TextMuted else stateColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${workspace.resolvedLocalPath()} · $protectionLabel$sizeInfo$groupAndTagsInfo",
                            color = TextMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (state is WorkspaceState.Active && !isInFlight) {
                        OutlinedButton(
                            onClick = { onEvict(false) },
                            shape = RoundedCornerShape(7.dp),
                            border = BorderStroke(1.dp, BorderColor),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) {
                            Text("Archive", color = AccentPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    } else if (state is WorkspaceState.Vaulted && !isInFlight) {
                        if (workspace.vaultFormat.isEncrypted) {
                            TextButton(
                                onClick = onStage,
                                shape = RoundedCornerShape(7.dp),
                                modifier = Modifier.height(32.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            ) {
                                Text("Stage", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        Button(
                            onClick = onLoad,
                            shape = RoundedCornerShape(7.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentPrimary,
                                contentColor = Color(0xFF12100B),
                            ),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        ) {
                            Text("Restore", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else if (state is WorkspaceState.StagedEncrypted && !isInFlight) {
                        Button(
                            onClick = onLoad,
                            shape = RoundedCornerShape(7.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentPrimary,
                                contentColor = Color(0xFF12100B),
                            ),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        ) {
                            Text("Restore", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    } else if (state is WorkspaceState.Missing && !isInFlight) {
                        OutlinedButton(
                            onClick = onSettings,
                            shape = RoundedCornerShape(7.dp),
                            border = BorderStroke(1.dp, WarningColor.copy(alpha = 0.5f)),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) {
                            Text("Configure", color = WarningColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    } else if (state is WorkspaceState.Error && !isInFlight) {
                        OutlinedButton(
                            onClick = onSettings,
                            shape = RoundedCornerShape(7.dp),
                            border = BorderStroke(1.dp, DangerColor.copy(alpha = 0.5f)),
                            modifier = Modifier.height(32.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        ) {
                            Text("Details", color = DangerColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    } else if (isInFlight && state !is WorkspaceState.Busy) {
                        Box(
                            modifier = Modifier.size(32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                color = AccentPrimary,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .clickable(enabled = state !is WorkspaceState.Busy && !isInFlight, onClick = onSettings),
                        contentAlignment = Alignment.Center,
                    ) {
                        BackStageGlyph(type = GlyphType.SLIDERS, color = TextMuted, size = 14.dp)
                    }
                }
            }

            // Inline responsive progress bar when Busy
            if (state is WorkspaceState.Busy) {
                Spacer(modifier = Modifier.height(10.dp))
                val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = state.progressFraction.coerceIn(0.01f, 1f),
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 60,
                        easing = androidx.compose.animation.core.LinearEasing,
                    ),
                    label = "card_progress_${workspace.id.value}",
                )
                val isIndeterminate = state.totalBytes == 0L && state.progressFraction <= 0.06f
                val progressDetail = when {
                    state.totalBytes > 0L -> {
                        val doneMb = state.bytesProcessed / (1024.0 * 1024.0)
                        val totalMb = state.totalBytes / (1024.0 * 1024.0)
                        val pct = (state.progressFraction * 100).toInt().coerceIn(0, 100)
                        if (totalMb >= 1000.0) {
                            "%.1f / %.1f GB (%d%%)".format(doneMb / 1024.0, totalMb / 1024.0, pct)
                        } else if (totalMb >= 1.0) {
                            "%.1f / %.1f MB (%d%%)".format(doneMb, totalMb, pct)
                        } else {
                            "${state.bytesProcessed / 1024} / ${state.totalBytes / 1024} KB ($pct%)"
                        }
                    }
                    state.progressFraction > 0f -> "${(state.progressFraction * 100).toInt().coerceIn(0, 100)}%"
                    else -> ""
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = state.statusMessage,
                        color = TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (progressDetail.isNotEmpty()) {
                        Text(
                            text = progressDetail,
                            color = AccentPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(5.dp))
                if (isIndeterminate) {
                    LinearProgressIndicator(
                        color = AccentPrimary,
                        trackColor = BgBase,
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    )
                } else {
                    LinearProgressIndicator(
                        progress = animatedProgress,
                        color = AccentPrimary,
                        trackColor = BgBase,
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    )
                }
            }

            if (state is WorkspaceState.Error) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = DangerColor.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(6.dp),
                    border = BorderStroke(1.dp, DangerColor.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val friendlyErr = when (val err = state.error) {
                            is BackStageError.DirectoryLocked -> {
                                val procs = err.openHandles.map { it.processName }.distinct().take(3).joinToString(", ")
                                "Folder is in use by $procs. Close those apps or force archive."
                            }
                            else -> state.message
                        }
                        Text(
                            text = friendlyErr,
                            color = DangerColor,
                            fontSize = 12.sp,
                            modifier = Modifier.weight(1f),
                        )
                        if (state.error is BackStageError.DirectoryLocked) {
                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedButton(
                                onClick = { onEvict(true) },
                                shape = RoundedCornerShape(6.dp),
                                border = BorderStroke(1.dp, DangerColor),
                                modifier = Modifier.height(26.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            ) {
                                Text("Force Archive", color = DangerColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkspaceSettingsScreen(
    engine: BackStageEngine,
    workspace: WorkspaceConfig,
    groups: List<WorkspaceGroup>,
    onBack: () -> Unit,
    onFeedback: (String) -> Unit,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var currentConfig by remember { mutableStateOf(workspace) }
    var encryptionEnabled by remember { mutableStateOf(currentConfig.vaultFormat.isEncrypted) }
    var selectedFormat by remember { mutableStateOf(currentConfig.vaultFormat) }
    var migrationPassword by remember { mutableStateOf("") }
    var isMigrating by remember { mutableStateOf(false) }

    // General metadata state
    var editedDisplayName by remember(currentConfig) { mutableStateOf(currentConfig.displayName) }
    var editedDescription by remember(currentConfig) { mutableStateOf(currentConfig.description) }
    var editedTagsText by remember(currentConfig) { mutableStateOf(currentConfig.tags.joinToString(", ")) }
    var editedGroupId by remember(currentConfig) { mutableStateOf<GroupId?>(currentConfig.groupId) }

    // Automation & Retention state
    val initialRetentionMode = remember(currentConfig) {
        when (currentConfig.retentionPolicy) {
            null, RetentionPolicy.Never -> "Manual"
            is RetentionPolicy.InactivityExpiry -> "InactiveDays"
            is RetentionPolicy.DeadlineExpiry -> "Deadline"
        }
    }
    var retentionMode by remember(currentConfig) { mutableStateOf(initialRetentionMode) }
    var retentionDaysText by remember(currentConfig) {
        val d = (currentConfig.retentionPolicy as? RetentionPolicy.InactivityExpiry)?.afterDays ?: 7
        mutableStateOf(d.toString())
    }
    var deadlineDaysFromNowText by remember(currentConfig) {
        val remDays = (currentConfig.retentionPolicy as? RetentionPolicy.DeadlineExpiry)?.let {
            val diffSec = (it.deadlineEpochSeconds - (System.currentTimeMillis() / 1000L)).coerceAtLeast(86_400L)
            (diffSec / 86_400L).toInt()
        } ?: 14
        mutableStateOf(remDays.toString())
    }

    // Prefetch schedule state
    var prefetchEnabled by remember(currentConfig) { mutableStateOf(currentConfig.prefetchSchedule != null) }
    var prefetchCron by remember(currentConfig) {
        mutableStateOf(currentConfig.prefetchSchedule?.cronExpression ?: "0 8 * * 1-5")
    }
    var prefetchIdleOnly by remember(currentConfig) {
        mutableStateOf(currentConfig.prefetchSchedule?.onIdleNetworkOnly ?: true)
    }

    // Key mode toggle (Save password on device vs Ask every time)
    var askPasswordEveryTime by remember(currentConfig) {
        mutableStateOf(currentConfig.keyMode is WorkspaceKeyMode.ManualOnEveryUse)
    }

    val initialVaultDir = remember(currentConfig) {
        resolveVaultDirPath(currentConfig.vaultBackend)
    }
    var editedVaultDir by remember(currentConfig) { mutableStateOf(initialVaultDir) }
    var editedLocalTargetDir by remember(currentConfig) { mutableStateOf(currentConfig.resolvedLocalPath()) }

    var passwordHint by remember(currentConfig) {
        mutableStateOf((currentConfig.keyMode as? WorkspaceKeyMode.PasswordBacked)?.passwordHint.orEmpty())
    }
    var newPasswordInput by remember { mutableStateOf("") }
    var showPasswordChars by remember { mutableStateOf(false) }

    val keystoreAlias = (currentConfig.keyMode as? WorkspaceKeyMode.PasswordBacked)?.keystoreAlias
        ?: WorkspaceConfig.defaultKeystoreAlias(currentConfig.id)
    var hasStoredKeyInOsKeystore by remember {
        mutableStateOf(engine.keyProvider.exists(keystoreAlias))
    }

    var confirmDeleteVaultToo by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    var activeTab by remember { mutableStateOf("Overview") } // Overview, Security, Automation

    val formatChanged = selectedFormat != currentConfig.vaultFormat
    val needsNewPasswordForMigration = formatChanged && !currentConfig.vaultFormat.isEncrypted && selectedFormat.isEncrypted
    val pathsChanged = editedVaultDir.trim() != initialVaultDir.trim() ||
        editedLocalTargetDir.trim() != currentConfig.resolvedLocalPath().trim()

    val parsedTags = editedTagsText.split(",").map { it.trim().removePrefix("#") }.filter { it.isNotBlank() }.distinct()
    val generalChanged = editedDisplayName.trim() != currentConfig.displayName ||
        editedDescription.trim() != currentConfig.description ||
        parsedTags != currentConfig.tags ||
        editedGroupId != currentConfig.groupId

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, BorderColor),
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BackStageGlyph(type = GlyphType.ARROW_LEFT, color = TextMain, size = 12.dp)
                        Text("Back", color = TextMain, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Text(
                    text = currentConfig.displayName,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = TextMain,
                )
            }

            // Minimalist Segmented Tab Switcher
            Surface(
                color = BgSurface,
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, BorderColor),
            ) {
                Row(
                    modifier = Modifier.padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for ((tabKey, tabLabel) in listOf(
                        "Overview" to "Overview & Paths",
                        "Security" to "Security",
                        "Automation" to "Automation & Manage",
                    )) {
                        FilterChipPill(
                            text = tabLabel,
                            selected = activeTab == tabKey,
                            onClick = { activeTab = tabKey },
                        )
                    }
                }
            }
        }

        if (activeTab == "Overview") {
            // General & Organization
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = BgSurface),
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Details",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextMain,
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        withContext(Dispatchers.IO) {
                                            val targetDir = File(currentConfig.resolvedLocalPath())
                                            targetDir.mkdirs()
                                            LauncherGenerator.writeInFolderLauncher(
                                                workspace = currentConfig,
                                                targetDirectory = targetDir,
                                            )
                                        }
                                        onFeedback("Created launcher script inside '${currentConfig.resolvedLocalPath()}'.")
                                    }.onFailure { err ->
                                        onFeedback("Could not create launcher: ${formatUserFriendlyError(err)}")
                                    }
                                }
                            },
                            modifier = Modifier.height(30.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                        ) {
                            Text("Create Folder Launcher", color = AccentPrimary, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StyledTextField(
                            value = editedDisplayName,
                            onValueChange = { editedDisplayName = it },
                            label = "Name",
                            modifier = Modifier.weight(1f),
                        )
                        StyledTextField(
                            value = editedTagsText,
                            onValueChange = { editedTagsText = it },
                            label = "Tags",
                            placeholder = "android, client, rust...",
                            modifier = Modifier.weight(1f),
                        )
                    }
                    StyledTextField(
                        value = editedDescription,
                        onValueChange = { editedDescription = it },
                        label = "Notes (optional)",
                        placeholder = "Short note about this workspace...",
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (groups.isNotEmpty()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Group:", color = TextMuted, fontSize = 12.sp)
                            FilterChipPill(
                                text = "None",
                                selected = editedGroupId == null,
                                onClick = { editedGroupId = null },
                            )
                            for (grp in groups) {
                                FilterChipPill(
                                    text = grp.displayName,
                                    selected = editedGroupId == grp.id,
                                    onClick = { editedGroupId = grp.id },
                                )
                            }
                        }
                    }

                    if (generalChanged && editedDisplayName.isNotBlank()) {
                        Button(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        val updated = withContext(Dispatchers.IO) {
                                            val nextConfig = currentConfig.copy(
                                                displayName = editedDisplayName.trim(),
                                                description = editedDescription.trim(),
                                                tags = parsedTags,
                                                groupId = editedGroupId,
                                            )
                                            engine.workspaceRepository.upsert(nextConfig)
                                            for (grp in groups) {
                                                val hasMember = currentConfig.id in grp.workspaceIds
                                                if (grp.id == editedGroupId && !hasMember) {
                                                    engine.groupRepository.upsert(
                                                        grp.copy(workspaceIds = grp.workspaceIds + currentConfig.id)
                                                    )
                                                } else if (grp.id != editedGroupId && hasMember) {
                                                    engine.groupRepository.upsert(
                                                        grp.copy(workspaceIds = grp.workspaceIds - currentConfig.id)
                                                    )
                                                }
                                            }
                                            nextConfig
                                        }
                                        currentConfig = updated
                                        onFeedback("Saved workspace details.")
                                    }.onFailure { err ->
                                        onFeedback("Failed to save details: ${formatUserFriendlyError(err)}")
                                    }
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentPrimary,
                                contentColor = Color(0xFF12100B),
                            ),
                        ) {
                            Text("Save Details", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Locations
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = BgSurface),
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Locations",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMain,
                    )

                    DirectoryInputField(
                        value = editedLocalTargetDir,
                        onValueChange = { editedLocalTargetDir = it },
                        label = "Working Folder",
                        dialogTitle = "Select Working Folder",
                    )

                    VaultStorageBackendSelector(
                        vaultDir = editedVaultDir,
                        onVaultDirChange = { editedVaultDir = it },
                    )

                    if (pathsChanged) {
                        Button(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        val updated = withContext(Dispatchers.IO) {
                                            val updatedOverrides = currentConfig.platformPathOverrides +
                                                (currentPlatform() to editedLocalTargetDir.trim())
                                            val nextConfig = currentConfig.copy(
                                                vaultBackend = buildVaultBackendForPath(
                                                    rawPath = editedVaultDir,
                                                    previousBackend = currentConfig.vaultBackend,
                                                ),
                                                localTargetPath = editedLocalTargetDir.trim(),
                                                platformPathOverrides = updatedOverrides,
                                            )
                                            engine.workspaceRepository.upsert(nextConfig)
                                            nextConfig
                                        }
                                        currentConfig = updated
                                        onFeedback("Saved folder locations.")
                                    }.onFailure { err ->
                                        onFeedback("Failed to save locations: ${formatUserFriendlyError(err)}")
                                    }
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentPrimary,
                                contentColor = Color(0xFF12100B),
                            ),
                        ) {
                            Text("Save Locations", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        if (activeTab == "Security") {
            // Protection & Format
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = BgSurface),
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Vault Format",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMain,
                            )
                            Text(
                                text = "Choose how this workspace is stored when archived.",
                                fontSize = 12.sp,
                                color = TextMuted,
                            )
                        }
                        Switch(
                            checked = encryptionEnabled,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF12100B),
                                checkedTrackColor = AccentPrimary,
                            ),
                            onCheckedChange = { enabled ->
                                encryptionEnabled = enabled
                                selectedFormat = if (enabled) {
                                    VaultFormatConfig.Portable
                                } else {
                                    VaultFormatConfig.PlainSevenZip
                                }
                            },
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    VaultFormatOptionRow(
                        selected = selectedFormat == VaultFormatConfig.Portable,
                        title = "7-Zip Encrypted (.7z)",
                        subtitle = "AES-256 header encryption with multi-volume cloud protection. Fully openable with standard 7-Zip.",
                        onSelect = {
                            encryptionEnabled = true
                            selectedFormat = VaultFormatConfig.Portable
                        },
                    )
                    VaultFormatOptionRow(
                        selected = selectedFormat == VaultFormatConfig.PlainSevenZip,
                        title = "7-Zip Unencrypted (.7z)",
                        subtitle = "Standard open 7-Zip archive with multi-volume cloud protection without password.",
                        onSelect = {
                            encryptionEnabled = false
                            selectedFormat = VaultFormatConfig.PlainSevenZip
                        },
                    )
                    if (currentConfig.vaultFormat == VaultFormatConfig.Native || currentConfig.vaultFormat == VaultFormatConfig.PlainCompressed) {
                        VaultFormatOptionRow(
                            selected = selectedFormat == currentConfig.vaultFormat,
                            title = "Legacy Vault (${if (currentConfig.vaultFormat == VaultFormatConfig.Native) ".bstage" else ".bvault"})",
                            subtitle = "Legacy format (select a 7-Zip format above to migrate).",
                            onSelect = {
                                selectedFormat = currentConfig.vaultFormat
                                encryptionEnabled = currentConfig.vaultFormat.isEncrypted
                            },
                        )
                    }

                    if (formatChanged) {
                        Spacer(modifier = Modifier.height(12.dp))
                        if (needsNewPasswordForMigration) {
                            StyledTextField(
                                value = migrationPassword,
                                onValueChange = { migrationPassword = it },
                                label = "New Password",
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                        Button(
                            enabled = !isMigrating && (!needsNewPasswordForMigration || migrationPassword.isNotBlank()),
                            onClick = {
                                scope.launch {
                                    isMigrating = true
                                    runCatching {
                                        val updated = engine.arbiter.migrateVaultFormat(
                                            workspaceId = currentConfig.id,
                                            targetFormat = selectedFormat,
                                            passwordForEncryption = migrationPassword.takeIf { it.isNotBlank() },
                                        )
                                        currentConfig = updated
                                        migrationPassword = ""
                                        hasStoredKeyInOsKeystore = engine.keyProvider.exists(keystoreAlias)
                                        onFeedback("Updated protection format.")
                                    }.onFailure { err ->
                                        onFeedback("Update failed: ${formatUserFriendlyError(err)}")
                                    }
                                    isMigrating = false
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentPrimary,
                                contentColor = Color(0xFF12100B),
                            ),
                        ) {
                            Text(if (isMigrating) "Updating..." else "Apply Format", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Password management
            if (encryptionEnabled) {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = BgSurface),
                    border = BorderStroke(1.dp, BorderColor),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Password",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextMain,
                                )
                                Text(
                                    text = when {
                                        askPasswordEveryTime -> "Always asks for password — never saved on this device."
                                        hasStoredKeyInOsKeystore -> "Saved on this device — you won't be asked for this password here."
                                        else -> "Not saved on this device — you'll be asked for the password when restoring."
                                    },
                                    color = if (hasStoredKeyInOsKeystore && !askPasswordEveryTime) SuccessColor else TextMuted,
                                    fontSize = 12.sp,
                                )
                            }
                            if (hasStoredKeyInOsKeystore) {
                                TextButton(
                                    onClick = {
                                        engine.keyProvider.delete(keystoreAlias)
                                        hasStoredKeyInOsKeystore = false
                                        onFeedback("Forgot saved password on this device.")
                                    },
                                ) {
                                    Text("Forget Saved Key", color = WarningColor, fontSize = 12.sp)
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Require password on every restore and archive",
                                    color = TextMain,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "Never cache the unlock key on this device.",
                                    color = TextMuted,
                                    fontSize = 11.sp,
                                )
                            }
                            Switch(
                                checked = askPasswordEveryTime,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF12100B),
                                    checkedTrackColor = AccentPrimary,
                                ),
                                onCheckedChange = { manualOnly ->
                                    askPasswordEveryTime = manualOnly
                                    scope.launch {
                                        val newKeyMode: WorkspaceKeyMode = if (manualOnly) {
                                            engine.keyProvider.delete(keystoreAlias)
                                            hasStoredKeyInOsKeystore = false
                                            WorkspaceKeyMode.ManualOnEveryUse
                                        } else {
                                            WorkspaceKeyMode.PasswordBacked(
                                                keystoreAlias = keystoreAlias,
                                                passwordHint = passwordHint.ifBlank { null },
                                            )
                                        }
                                        val updated = withContext(Dispatchers.IO) {
                                            val next = currentConfig.copy(keyMode = newKeyMode)
                                            engine.workspaceRepository.upsert(next)
                                            next
                                        }
                                        currentConfig = updated
                                        onFeedback(
                                            if (manualOnly) "Password will now be requested on every use."
                                            else "Password will be remembered on this device after your next unlock."
                                        )
                                    }
                                },
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            StyledTextField(
                                value = newPasswordInput,
                                onValueChange = { newPasswordInput = it },
                                label = "New Password",
                                visualTransformation = if (showPasswordChars) VisualTransformation.None else PasswordVisualTransformation(),
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                trailingContent = {
                                    TextButton(onClick = { showPasswordChars = !showPasswordChars }) {
                                        Text(if (showPasswordChars) "Hide" else "Show", color = AccentPrimary, fontSize = 11.sp)
                                    }
                                },
                            )
                            StyledTextField(
                                value = passwordHint,
                                onValueChange = { passwordHint = it },
                                label = "Hint (optional)",
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                enabled = newPasswordInput.isNotBlank(),
                                onClick = {
                                    scope.launch {
                                        runCatching {
                                            val backend = dev.legendsayantan.backstage.core.backend.VaultBackendFactory.create(currentConfig.vaultBackend)
                                            val localTarget = File(currentConfig.resolvedLocalPath())
                                            val isLocalActive = localTarget.isDirectory && (FolderLock.readLock(localTarget) != null || (localTarget.listFiles()?.isNotEmpty() == true))
                                            val vaultExists = backend.exists(currentConfig.vaultFileName)

                                            if (!isLocalActive && vaultExists && !hasStoredKeyInOsKeystore) {
                                                onFeedback("Please restore '${currentConfig.displayName}' with your current password first.")
                                                return@launch
                                            }

                                            if (!hasStoredKeyInOsKeystore && (isLocalActive || !vaultExists)) {
                                                // Initialize fresh key material and persist into keystore
                                                val initMaterial = engine.passwordVerifier.initializeWorkspaceKey(
                                                    workspace = currentConfig,
                                                    password = newPasswordInput,
                                                )
                                                if (!askPasswordEveryTime) {
                                                    engine.keyProvider.store(keystoreAlias, initMaterial.vaultKey)
                                                }
                                                if (vaultExists && isLocalActive) {
                                                    // User reset forgotten password on active workspace: purge old stale vault
                                                    runCatching { backend.delete(currentConfig.vaultFileName) }
                                                    runCatching { backend.delete(dev.legendsayantan.backstage.core.crypto.BsmetaSidecar.sidecarFileName(currentConfig.vaultFileName)) }
                                                }
                                                // Write initial .bsmeta sidecar so password hint and MAC are preserved
                                                val sidecarName = dev.legendsayantan.backstage.core.crypto.BsmetaSidecar.sidecarFileName(currentConfig.vaultFileName)
                                                val macCover = initMaterial.argon2Salt + initMaterial.wrappedKey
                                                val headerMac = dev.legendsayantan.backstage.core.crypto.Blake3.keyedHash(macCover, initMaterial.vaultKey)
                                                val sidecar = dev.legendsayantan.backstage.core.crypto.BsmetaSidecar(
                                                    workspaceId = currentConfig.id.value,
                                                    displayName = currentConfig.displayName,
                                                    platformOrigin = currentConfig.platformOrigin,
                                                    vaultFormat = currentConfig.vaultFormat.fileExtension,
                                                    archiveFilename = currentConfig.vaultFileName,
                                                    passwordHint = passwordHint.ifBlank { null },
                                                    headerMacHex = headerMac.toHexString(),
                                                    kdf = dev.legendsayantan.backstage.core.crypto.BsmetaKdfInfo(
                                                        saltHex = initMaterial.argon2Salt.toHexString(),
                                                        wrappedKeyHex = initMaterial.wrappedKey.toHexString(),
                                                    )
                                                )
                                                backend.writeAllBytes(sidecarName, dev.legendsayantan.backstage.core.crypto.BsmetaSidecar.encode(sidecar).encodeToByteArray())
                                            } else {
                                                engine.passwordChanger.changePassword(
                                                    workspace = currentConfig,
                                                    newPassword = newPasswordInput,
                                                    newPasswordHint = passwordHint.ifBlank { null },
                                                    backend = backend,
                                                )
                                            }
                                            val updatedKeyMode = when (val km = currentConfig.keyMode) {
                                                is WorkspaceKeyMode.PasswordBacked -> km.copy(passwordHint = passwordHint.ifBlank { null })
                                                WorkspaceKeyMode.ManualOnEveryUse -> WorkspaceKeyMode.ManualOnEveryUse
                                                null -> WorkspaceKeyMode.PasswordBacked(
                                                    keystoreAlias = keystoreAlias,
                                                    passwordHint = passwordHint.ifBlank { null },
                                                )
                                            }
                                            val updated = currentConfig.copy(keyMode = updatedKeyMode)
                                            engine.workspaceRepository.upsert(updated)
                                            currentConfig = updated
                                            newPasswordInput = ""
                                            val hadKeyBefore = hasStoredKeyInOsKeystore
                                            hasStoredKeyInOsKeystore = engine.keyProvider.exists(keystoreAlias)
                                            onFeedback(if (hadKeyBefore) "Password updated." else "Password set.")
                                        }.onFailure {
                                            onFeedback("Could not update password: ${formatUserFriendlyError(it)}")
                                        }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = AccentPrimary,
                                    contentColor = Color(0xFF12100B),
                                ),
                            ) {
                                Text(if (hasStoredKeyInOsKeystore) "Update Password" else "Set Password", fontWeight = FontWeight.SemiBold)
                            }

                            if (currentConfig.vaultFormat == VaultFormatConfig.Native) {
                                OutlinedButton(
                                    enabled = newPasswordInput.isNotBlank(),
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, BorderColor),
                                    onClick = {
                                        scope.launch {
                                            runCatching {
                                                val backend = dev.legendsayantan.backstage.core.backend.VaultBackendFactory.create(currentConfig.vaultBackend)
                                                engine.passwordChanger.rotateVaultKey(
                                                    workspace = currentConfig,
                                                    newPassword = newPasswordInput,
                                                    newPasswordHint = passwordHint.ifBlank { null },
                                                    backend = backend,
                                                )
                                                val updatedKeyMode = when (val km = currentConfig.keyMode) {
                                                    is WorkspaceKeyMode.PasswordBacked -> km.copy(passwordHint = passwordHint.ifBlank { null })
                                                    WorkspaceKeyMode.ManualOnEveryUse -> WorkspaceKeyMode.ManualOnEveryUse
                                                    null -> null
                                                }
                                                val updated = currentConfig.copy(keyMode = updatedKeyMode)
                                                engine.workspaceRepository.upsert(updated)
                                                currentConfig = updated
                                                newPasswordInput = ""
                                                hasStoredKeyInOsKeystore = engine.keyProvider.exists(keystoreAlias)
                                                onFeedback("Updated password and signed out other devices.")
                                            }.onFailure {
                                                onFeedback("Operation failed: ${formatUserFriendlyError(it)}")
                                            }
                                        }
                                    },
                                ) {
                                    Text("Sign Out Other Devices", color = TextMain)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (activeTab == "Automation") {
            // Automation & Lifecycle (Retention Policy + Scheduled Staging)
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = BgSurface),
                border = BorderStroke(1.dp, BorderColor),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Auto-Archive Rules",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextMain,
                    )
                    Text(
                        text = "Automatically archive this workspace after inactivity or on a scheduled deadline.",
                        fontSize = 12.sp,
                        color = TextMuted,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((modeKey, label) in listOf(
                            "Manual" to "Manual only",
                            "InactiveDays" to "After inactivity",
                            "Deadline" to "After deadline",
                        )) {
                            FilterChipPill(
                                text = label,
                                selected = retentionMode == modeKey,
                                onClick = { retentionMode = modeKey },
                            )
                        }
                    }

                    if (retentionMode == "InactiveDays") {
                        StyledTextField(
                            value = retentionDaysText,
                            onValueChange = { retentionDaysText = it.filter { ch -> ch.isDigit() } },
                            label = "Archive after inactive days",
                            placeholder = "7",
                            modifier = Modifier.width(240.dp),
                        )
                    }

                    if (retentionMode == "Deadline") {
                        StyledTextField(
                            value = deadlineDaysFromNowText,
                            onValueChange = { deadlineDaysFromNowText = it.filter { ch -> ch.isDigit() } },
                            label = "Archive after N days from today",
                            placeholder = "14",
                            modifier = Modifier.width(260.dp),
                        )
                    }

                    HorizontalDivider(color = BorderColor)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Scheduled Pre-Staging",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextMain,
                            )
                            Text(
                                text = "Pre-download the encrypted vault locally on a schedule so restoring is near-instant.",
                                fontSize = 12.sp,
                                color = TextMuted,
                            )
                        }
                        Switch(
                            checked = prefetchEnabled,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF12100B),
                                checkedTrackColor = AccentPrimary,
                            ),
                            onCheckedChange = { prefetchEnabled = it },
                        )
                    }

                    if (prefetchEnabled) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            for ((presetLabel, presetCron) in listOf(
                                "Weekdays 8 AM" to "0 8 * * 1-5",
                                "Daily 8 AM" to "0 8 * * *",
                                "Daily 6 PM" to "0 18 * * *",
                            )) {
                                FilterChipPill(
                                    text = presetLabel,
                                    selected = prefetchCron.trim() == presetCron,
                                    onClick = { prefetchCron = presetCron },
                                )
                            }
                        }
                        StyledTextField(
                            value = prefetchCron,
                            onValueChange = { prefetchCron = it },
                            label = "Schedule (cron: min hour day month weekday)",
                            placeholder = "0 8 * * 1-5",
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Only pre-stage when network is idle", color = TextMuted, fontSize = 12.sp)
                            Switch(
                                checked = prefetchIdleOnly,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF12100B),
                                    checkedTrackColor = AccentPrimary,
                                ),
                                onCheckedChange = { prefetchIdleOnly = it },
                            )
                        }
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                runCatching {
                                    val days = retentionDaysText.toIntOrNull()?.coerceAtLeast(1) ?: 7
                                    val deadlineDays = deadlineDaysFromNowText.toIntOrNull()?.coerceAtLeast(1) ?: 14
                                    val newPolicy: RetentionPolicy = when (retentionMode) {
                                        "InactiveDays" -> RetentionPolicy.InactivityExpiry(afterDays = days)
                                        "Deadline" -> RetentionPolicy.DeadlineExpiry(
                                            deadlineEpochSeconds = (System.currentTimeMillis() / 1000L) + (deadlineDays.toLong() * 86_400L)
                                        )
                                        else -> RetentionPolicy.Never
                                    }
                                    val newSchedule = if (prefetchEnabled && prefetchCron.isNotBlank()) {
                                        PrefetchSchedule(
                                            cronExpression = prefetchCron.trim(),
                                            onIdleNetworkOnly = prefetchIdleOnly,
                                        )
                                    } else {
                                        null
                                    }
                                    val updated = withContext(Dispatchers.IO) {
                                        val next = currentConfig.copy(
                                            retentionPolicy = newPolicy,
                                            prefetchSchedule = newSchedule,
                                        )
                                        engine.workspaceRepository.upsert(next)
                                        if (newSchedule != null) {
                                            runCatching { LauncherGenerator.registerScheduledPrefetch(next) }
                                        }
                                        next
                                    }
                                    currentConfig = updated
                                    onFeedback("Saved automation rules.")
                                }.onFailure { err ->
                                    onFeedback("Could not save rules: ${formatUserFriendlyError(err)}")
                                }
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentPrimary,
                            contentColor = Color(0xFF12100B),
                        ),
                    ) {
                        Text("Save Rules", fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Danger Zone — Remove Workspace
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = BgSurface),
                border = BorderStroke(1.dp, DangerColor.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        text = "Remove Workspace",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = DangerColor,
                    )
                    Text(
                        text = "Stop managing this workspace in BackStage. Your active working folder on disk will not be deleted.",
                        fontSize = 12.sp,
                        color = TextMuted,
                    )
                    if (!showDeleteConfirmation) {
                        OutlinedButton(
                            onClick = { showDeleteConfirmation = true },
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, DangerColor),
                        ) {
                            Text("Remove from BackStage...", color = DangerColor, fontWeight = FontWeight.Medium)
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Switch(
                                checked = confirmDeleteVaultToo,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF12100B),
                                    checkedTrackColor = DangerColor,
                                ),
                                onCheckedChange = { confirmDeleteVaultToo = it },
                            )
                            Text(
                                text = "Also delete the archived vault file (${currentConfig.vaultFileName})",
                                color = if (confirmDeleteVaultToo) DangerColor else TextMain,
                                fontSize = 12.sp,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            if (confirmDeleteVaultToo) {
                                                runCatching {
                                                    val backend = dev.legendsayantan.backstage.core.backend.VaultBackendFactory.create(currentConfig.vaultBackend)
                                                    backend.delete(currentConfig.vaultFileName)
                                                    backend.delete(
                                                        dev.legendsayantan.backstage.core.crypto.BsmetaSidecar.sidecarFileName(currentConfig.vaultFileName)
                                                    )
                                                }
                                            }
                                            val localDir = File(currentConfig.resolvedLocalPath())
                                            FolderLock.removeLock(localDir)
                                            engine.keyProvider.delete(keystoreAlias)
                                            engine.workspaceRepository.delete(currentConfig.id)
                                        }
                                        onDeleted()
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = DangerColor,
                                    contentColor = Color.White,
                                ),
                            ) {
                                Text("Confirm Removal", fontWeight = FontWeight.SemiBold)
                            }
                            OutlinedButton(
                                onClick = {
                                    showDeleteConfirmation = false
                                    confirmDeleteVaultToo = false
                                },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, BorderColor),
                            ) {
                                Text("Cancel", color = TextMain)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultFormatOptionRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(selectedColor = AccentPrimary),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(title, color = TextMain, fontWeight = FontWeight.Medium, fontSize = 14.sp)
            Text(subtitle, color = TextMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun PasswordPromptDialog(
    engine: BackStageEngine,
    prompt: ActivePasswordPrompt,
    onCancel: () -> Unit,
    onVerified: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var isVerifying by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val isInitialCreation = prompt.needsPassword.reason == PasswordReason.INITIAL_CREATION

    val reasonDescription = when (prompt.needsPassword.reason) {
        PasswordReason.INITIAL_CREATION ->
            "Set a password for '${prompt.workspace.displayName}' to encrypt it before archiving."
        PasswordReason.NEW_DEVICE ->
            "Enter the password for '${prompt.workspace.displayName}' to unlock it on this device."
        PasswordReason.ALWAYS_REQUIRED ->
            "Enter the password for '${prompt.workspace.displayName}'."
        PasswordReason.KEY_ROTATED ->
            "The password for '${prompt.workspace.displayName}' was updated on another device. Enter the new password to continue."
    }

    BackStageModalDialog(
        glyph = GlyphType.LOCK,
        title = if (isInitialCreation) "Set Workspace Password" else "Unlock Workspace",
        subtitle = prompt.workspace.displayName,
        width = 460.dp,
        onDismissRequest = onCancel,
        dismissEnabled = !isVerifying,
        confirmButtonText = if (isVerifying) (if (isInitialCreation) "Saving..." else "Unlocking...") else if (isInitialCreation) "Save Password & Archive" else "Unlock",
        confirmEnabled = !isVerifying && password.isNotEmpty() && (!isInitialCreation || (confirmPassword.isNotEmpty() && password == confirmPassword)),
        onConfirm = {
            scope.launch {
                isVerifying = true
                errorMessage = null
                if (isInitialCreation) {
                    if (password != confirmPassword) {
                        errorMessage = "Passwords do not match."
                        isVerifying = false
                        return@launch
                    }
                    isVerifying = false
                    onVerified(password)
                } else {
                    val verifiedKey = engine.passwordVerifier.verify(password, prompt.needsPassword)
                    isVerifying = false
                    if (verifiedKey != null) {
                        verifiedKey.fill(0)
                        onVerified(password)
                    } else {
                        errorMessage = "Incorrect password."
                    }
                }
            }
        },
    ) {
        Text(
            text = reasonDescription,
            color = TextMain,
            fontSize = 13.sp,
        )

        if (!prompt.needsPassword.hint.isNullOrBlank()) {
            Text(
                text = "Hint: ${prompt.needsPassword.hint}",
                color = AccentPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        StyledTextField(
            value = password,
            onValueChange = {
                password = it
                errorMessage = null
            },
            label = if (isInitialCreation) "New Password" else "Password",
            placeholder = "Enter password...",
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            enabled = !isVerifying,
            modifier = Modifier.fillMaxWidth(),
            trailingContent = {
                TextButton(
                    onClick = { showPassword = !showPassword },
                    modifier = Modifier.padding(end = 4.dp),
                ) {
                    Text(
                        text = if (showPassword) "Hide" else "Show",
                        color = AccentPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            },
        )

        if (isInitialCreation) {
            StyledTextField(
                value = confirmPassword,
                onValueChange = {
                    confirmPassword = it
                    errorMessage = null
                },
                label = "Confirm Password",
                placeholder = "Repeat password...",
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                enabled = !isVerifying,
                modifier = Modifier.fillMaxWidth(),
            )
            if (confirmPassword.isNotEmpty() && password != confirmPassword) {
                Text(
                    text = "Passwords do not match",
                    color = DangerColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        if (isVerifying) {
            LinearProgressIndicator(
                progress = { 0.5f },
                color = AccentPrimary,
                trackColor = BgBase,
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
            )
        }

        if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = DangerColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun NewWorkspaceDialog(
    engine: BackStageEngine,
    groups: List<WorkspaceGroup>,
    onDismiss: () -> Unit,
    onCreated: (WorkspaceConfig, String?) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var tagsInput by remember { mutableStateOf("") }
    var selectedGroupId by remember { mutableStateOf<GroupId?>(null) }
    var vaultDir by remember {
        mutableStateOf(File(System.getProperty("user.home") ?: ".", "BackStageVaults").absolutePath)
    }
    var localTargetDir by remember {
        mutableStateOf(File(System.getProperty("user.home") ?: ".", "Workspaces/NewProject").absolutePath)
    }
    var format by remember {
        mutableStateOf<VaultFormatConfig>(engine.appConfigRepository.defaultVaultFormat)
    }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var passwordHint by remember { mutableStateOf("") }
    var rememberPasswordOnDevice by remember { mutableStateOf(true) }
    var autoArchiveInactiveDays by remember { mutableStateOf(false) }
    var showMoreOptions by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val passwordsMatch = !format.isEncrypted || (password.isNotBlank() && password == confirmPassword)

    BackStageModalDialog(
        glyph = GlyphType.VAULT,
        title = "New Workspace",
        subtitle = null,
        width = 580.dp,
        maxHeight = 700.dp,
        onDismissRequest = onDismiss,
        dismissEnabled = !isSaving,
        confirmButtonText = if (isSaving) "Creating..." else "Create",
        confirmEnabled = !isSaving && name.isNotBlank() && vaultDir.isNotBlank() && localTargetDir.isNotBlank() && passwordsMatch,
        footerLeadingContent = {
            TextButton(onClick = { showMoreOptions = !showMoreOptions }) {
                Text(
                    text = if (showMoreOptions) "Fewer options" else "More options",
                    color = TextMuted,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        onConfirm = {
            scope.launch {
                isSaving = true
                errorText = null
                runCatching {
                    val initialPassword = password.takeIf { format.isEncrypted }
                    val config = withContext(Dispatchers.IO) {
                        val id = WorkspaceId.generate(SecureRandomProvider.nextBytes(20))
                        val baseSlug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                            .ifBlank { id.value }
                        val vaultDirFile = File(vaultDir.trim())
                        val slug = if (File(vaultDirFile, "$baseSlug.${format.fileExtension}").exists()) {
                            "$baseSlug-${id.value.takeLast(6).lowercase()}"
                        } else {
                            baseSlug
                        }
                        val keyMode: WorkspaceKeyMode? = if (format.isEncrypted) {
                            if (rememberPasswordOnDevice) {
                                WorkspaceKeyMode.PasswordBacked(
                                    keystoreAlias = WorkspaceConfig.defaultKeystoreAlias(id),
                                    passwordHint = passwordHint.ifBlank { null },
                                )
                            } else {
                                WorkspaceKeyMode.ManualOnEveryUse
                            }
                        } else {
                            null
                        }
                        val tags = tagsInput.split(",").map { it.trim().removePrefix("#") }.filter { it.isNotBlank() }.distinct()
                        val createdConfig = WorkspaceConfig(
                            id = id,
                            displayName = name.trim(),
                            vaultBackend = buildVaultBackendForPath(vaultDir.trim()),
                            vaultFileName = "$slug.${format.fileExtension}",
                            localTargetPath = localTargetDir.trim(),
                            vaultFormat = format,
                            keyMode = keyMode,
                            retentionPolicy = if (autoArchiveInactiveDays) {
                                RetentionPolicy.InactivityExpiry(afterDays = 7)
                            } else {
                                RetentionPolicy.Never
                            },
                            tags = tags,
                            groupId = selectedGroupId,
                            createdAtEpochSeconds = System.currentTimeMillis() / 1000L,
                        )
                        File(localTargetDir.trim()).mkdirs()
                        engine.workspaceRepository.upsert(createdConfig)
                        if (selectedGroupId != null) {
                            val grp = groups.firstOrNull { it.id == selectedGroupId }
                            if (grp != null && id !in grp.workspaceIds) {
                                engine.groupRepository.upsert(grp.copy(workspaceIds = grp.workspaceIds + id))
                            }
                        }
                        createdConfig
                    }
                    onCreated(config, initialPassword)
                }.onFailure { err ->
                    errorText = formatUserFriendlyError(err)
                }
                isSaving = false
            }
        },
    ) {
        StyledTextField(
            value = name,
            onValueChange = { name = it },
            label = "Workspace Name",
            placeholder = "Project name...",
            enabled = !isSaving,
            modifier = Modifier.fillMaxWidth(),
        )

        DirectoryInputField(
            value = localTargetDir,
            onValueChange = { selected ->
                localTargetDir = selected
                if (name.isBlank()) {
                    val folderName = File(selected.trim()).name
                    if (folderName.isNotBlank() && !folderName.equals("NewProject", ignoreCase = true)) {
                        name = folderName
                    }
                }
            },
            label = "Working Folder",
            enabled = !isSaving,
            dialogTitle = "Select Working Folder",
        )

        VaultStorageBackendSelector(
            vaultDir = vaultDir,
            onVaultDirChange = { vaultDir = it },
            enabled = !isSaving,
        )

        DialogSectionLabel(title = "Protection")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FormatChoiceCard(
                selected = format == VaultFormatConfig.Portable,
                title = "7-Zip Encrypted",
                subtitle = "AES-256 Password (.7z)",
                onClick = { format = VaultFormatConfig.Portable },
                modifier = Modifier.weight(1f),
            )
            FormatChoiceCard(
                selected = format == VaultFormatConfig.PlainSevenZip,
                title = "7-Zip Plain",
                subtitle = "Unencrypted (.7z)",
                onClick = { format = VaultFormatConfig.PlainSevenZip },
                modifier = Modifier.weight(1f),
            )
        }

        if (format.isEncrypted) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StyledTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Password",
                    placeholder = "Enter password...",
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f),
                )
                StyledTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    label = "Confirm",
                    placeholder = "Repeat password...",
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f),
                    trailingContent = {
                        TextButton(
                            onClick = { showPassword = !showPassword },
                            modifier = Modifier.padding(end = 4.dp),
                        ) {
                            Text(
                                text = if (showPassword) "Hide" else "Show",
                                color = AccentPrimary,
                                fontSize = 11.sp,
                            )
                        }
                    },
                )
            }

            if (confirmPassword.isNotEmpty() && password != confirmPassword) {
                Text(
                    text = "Passwords do not match",
                    color = DangerColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        if (showMoreOptions) {
            DialogSectionLabel(title = "Options")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                StyledTextField(
                    value = tagsInput,
                    onValueChange = { tagsInput = it },
                    label = "Tags (optional)",
                    placeholder = "work, android...",
                    enabled = !isSaving,
                    modifier = Modifier.weight(1f),
                )
                if (format.isEncrypted) {
                    StyledTextField(
                        value = passwordHint,
                        onValueChange = { passwordHint = it },
                        label = "Password Hint (optional)",
                        placeholder = "Optional reminder...",
                        enabled = !isSaving,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (groups.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Group:", color = TextMuted, fontSize = 12.sp)
                    FilterChipPill(
                        text = "None",
                        selected = selectedGroupId == null,
                        onClick = { selectedGroupId = null },
                    )
                    for (grp in groups) {
                        FilterChipPill(
                            text = grp.displayName,
                            selected = selectedGroupId == grp.id,
                            onClick = { selectedGroupId = grp.id },
                        )
                    }
                }
            }

            if (format.isEncrypted) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Remember password on this device",
                        color = TextMain,
                        fontSize = 12.sp,
                    )
                    Switch(
                        checked = rememberPasswordOnDevice,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF12100B),
                            checkedTrackColor = AccentPrimary,
                        ),
                        onCheckedChange = { rememberPasswordOnDevice = it },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Auto-archive after 7 days of inactivity",
                    color = TextMain,
                    fontSize = 12.sp,
                )
                Switch(
                    checked = autoArchiveInactiveDays,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF12100B),
                        checkedTrackColor = AccentPrimary,
                    ),
                    onCheckedChange = { autoArchiveInactiveDays = it },
                )
            }
        }

        if (errorText != null) {
            Text(
                text = errorText!!,
                color = DangerColor,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun ManageGroupsDialog(
    engine: BackStageEngine,
    workspaces: List<WorkspaceConfig>,
    groups: List<WorkspaceGroup>,
    onDismiss: () -> Unit,
    onFeedback: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var newGroupName by remember { mutableStateOf("") }
    var selectedStrategy by remember { mutableStateOf(LoadStrategy.SPACE_AWARE) }

    BackStageModalDialog(
        glyph = GlyphType.FOLDER,
        title = "Workspace Groups",
        subtitle = "Organize related workspaces to restore or archive them together",
        width = 560.dp,
        maxHeight = 640.dp,
        onDismissRequest = onDismiss,
        confirmButtonText = "Done",
        onConfirm = onDismiss,
    ) {
        DialogSectionLabel(title = "Create Group")

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StyledTextField(
                value = newGroupName,
                onValueChange = { newGroupName = it },
                label = "Group Name",
                placeholder = "e.g., Client Projects, Game Dev...",
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = newGroupName.isNotBlank(),
                onClick = {
                    val trimmed = newGroupName.trim()
                    val grpId = GroupId(
                        "grp_" + trimmed.lowercase().replace(Regex("[^a-z0-9]+"), "_")
                            .trim('_')
                            .ifBlank { (System.currentTimeMillis() % 100000).toString() }
                    )
                    val newGrp = WorkspaceGroup(
                        id = grpId,
                        displayName = trimmed,
                        loadStrategy = selectedStrategy,
                        workspaceIds = emptyList(),
                        createdAtEpochSeconds = System.currentTimeMillis() / 1000L,
                    )
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            engine.groupRepository.upsert(newGrp)
                        }
                        newGroupName = ""
                        onFeedback("Created group '${newGrp.displayName}'")
                    }
                },
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AccentPrimary,
                    contentColor = Color(0xFF12100B),
                ),
                modifier = Modifier.height(42.dp),
            ) {
                Text("Add Group", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Restore Mode:", color = TextMuted, fontSize = 12.sp)
            for ((strategy, label) in listOf(
                LoadStrategy.SPACE_AWARE to "Space-Aware",
                LoadStrategy.PARALLEL to "Parallel",
                LoadStrategy.SEQUENTIAL to "Sequential",
            )) {
                FilterChipPill(
                    text = label,
                    selected = selectedStrategy == strategy,
                    onClick = { selectedStrategy = strategy },
                )
            }
        }

        if (groups.isNotEmpty()) {
            DialogSectionLabel(title = "Existing Groups")
            for (grp in groups) {
                val memberCount = workspaces.count { it.groupId == grp.id }
                Surface(
                    color = BgBase,
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, BorderColor),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(grp.displayName, color = TextMain, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            val strategyLabel = when (grp.loadStrategy) {
                                LoadStrategy.SPACE_AWARE -> "Space-Aware"
                                LoadStrategy.PARALLEL -> "Parallel"
                                LoadStrategy.SEQUENTIAL -> "Sequential"
                            }
                            Text(
                                text = "$memberCount ${if (memberCount == 1) "workspace" else "workspaces"} · $strategyLabel",
                                color = TextMuted,
                                fontSize = 11.sp,
                            )
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        for (ws in workspaces.filter { it.groupId == grp.id }) {
                                            engine.workspaceRepository.upsert(ws.copy(groupId = null))
                                        }
                                        engine.groupRepository.delete(grp.id)
                                    }
                                    onFeedback("Deleted group '${grp.displayName}'")
                                }
                            }
                        ) {
                            Text("Delete", color = DangerColor, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreferencesAndActivityDialog(
    engine: BackStageEngine,
    workspaces: List<WorkspaceConfig>,
    onDismiss: () -> Unit,
    onFeedback: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var activeTab by remember { mutableStateOf(0) } // 0 = Preferences, 1 = Activity
    var autoUnloadEnabled by remember { mutableStateOf(engine.appConfigRepository.autoUnloadEnabled) }
    var prefetchOnIdleOnly by remember { mutableStateOf(engine.appConfigRepository.prefetchOnIdleOnly) }
    var defaultFormat by remember { mutableStateOf(engine.appConfigRepository.defaultVaultFormat) }
    val auditEntries = remember { engine.auditLogRepository.findRecent(limit = 25) }
    val wsNames = remember(workspaces) { workspaces.associate { it.id.value to it.displayName } }

    BackStageModalDialog(
        glyph = GlyphType.SLIDERS,
        title = "Preferences",
        subtitle = null,
        width = 560.dp,
        maxHeight = 640.dp,
        onDismissRequest = onDismiss,
        headerTrailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChipPill(
                    text = "Preferences",
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                )
                FilterChipPill(
                    text = "Activity",
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                )
            }
        },
        confirmButtonText = "Save",
        onConfirm = {
            scope.launch {
                withContext(Dispatchers.IO) {
                    engine.appConfigRepository.autoUnloadEnabled = autoUnloadEnabled
                    engine.appConfigRepository.prefetchOnIdleOnly = prefetchOnIdleOnly
                    engine.appConfigRepository.defaultVaultFormat = defaultFormat
                }
                onFeedback("Saved preferences.")
                onDismiss()
            }
        },
    ) {
        if (activeTab == 0) {
            DialogSectionLabel(title = "Space Management")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Auto-archive least-used workspaces when disk is low",
                        color = TextMain,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Frees space automatically when restoring a workspace.",
                        color = TextMuted,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = autoUnloadEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF12100B),
                        checkedTrackColor = AccentPrimary,
                    ),
                    onCheckedChange = { autoUnloadEnabled = it },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Stage scheduled workspaces only when network is idle",
                        color = TextMain,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Avoids background transfers while you use bandwidth.",
                        color = TextMuted,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = prefetchOnIdleOnly,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color(0xFF12100B),
                        checkedTrackColor = AccentPrimary,
                    ),
                    onCheckedChange = { prefetchOnIdleOnly = it },
                )
            }

            DialogSectionLabel(title = "Default Vault Format")

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FormatChoiceCard(
                    selected = defaultFormat == VaultFormatConfig.Portable,
                    title = "7-Zip Encrypted",
                    subtitle = "AES-256 Password (.7z)",
                    onClick = { defaultFormat = VaultFormatConfig.Portable },
                    modifier = Modifier.weight(1f),
                )
                FormatChoiceCard(
                    selected = defaultFormat == VaultFormatConfig.PlainSevenZip,
                    title = "7-Zip Plain",
                    subtitle = "Unencrypted (.7z)",
                    onClick = { defaultFormat = VaultFormatConfig.PlainSevenZip },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            if (auditEntries.isEmpty()) {
                Text(
                    text = "No workspace operations recorded yet.",
                    color = TextMuted,
                    fontSize = 12.sp,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val nowSec = System.currentTimeMillis() / 1000L
                    for (entry in auditEntries.take(15)) {
                        val wsTitle = entry.workspaceId?.let { wsNames[it.value] ?: it.value } ?: "System"
                        val actionLabel = when (entry.eventType) {
                            AuditEventType.LOAD -> "Restored"
                            AuditEventType.EVICT -> "Archived"
                            AuditEventType.AUTH -> "Unlocked App"
                            AuditEventType.PASSWORD_CHANGE -> "Changed Password"
                            AuditEventType.KEY_ROTATE -> "Signed Out Other Devices"
                            AuditEventType.NEW_DEVICE_UNLOCK -> "Unlocked on New Device"
                            AuditEventType.STALE_KEY_DETECTED -> "Refreshed Key"
                            AuditEventType.FORMAT_MIGRATE -> "Updated Format"
                            AuditEventType.ERROR -> "Error"
                        }
                        val statusColor = if (entry.eventType == AuditEventType.ERROR) DangerColor else SuccessColor
                        val minsAgo = ((nowSec - entry.occurredAtEpochSeconds).coerceAtLeast(0L)) / 60L
                        val timeLabel = when {
                            minsAgo < 1L -> "Just now"
                            minsAgo < 60L -> "${minsAgo}m ago"
                            minsAgo < 1440L -> "${minsAgo / 60L}h ago"
                            else -> "${minsAgo / 1440L}d ago"
                        }
                        Surface(
                            color = BgBase,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, BorderColor),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "$actionLabel · $wsTitle",
                                        color = TextMain,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    if (entry.eventType == AuditEventType.ERROR && !entry.detailJson.isNullOrBlank()) {
                                        Text(
                                            text = entry.detailJson!!,
                                            color = DangerColor,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                Text(
                                    text = timeLabel,
                                    color = statusColor,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FormatChoiceCard(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = if (selected) AccentPrimary.copy(alpha = 0.12f) else BgBase,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, if (selected) AccentPrimary else BorderColor),
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                color = if (selected) AccentPrimary else TextMain,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = subtitle,
                color = TextMuted,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ImportVaultDialog(
    engine: BackStageEngine,
    onDismiss: () -> Unit,
    onImported: (WorkspaceConfig) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val defaultImportedTarget = remember {
        File(System.getProperty("user.home") ?: ".", "Workspaces/ImportedProject").absolutePath
    }
    var vaultFilePath by remember { mutableStateOf("") }
    var localTargetDir by remember { mutableStateOf(defaultImportedTarget) }
    var customDisplayName by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isImporting by remember { mutableStateOf(false) }

    BackStageModalDialog(
        glyph = GlyphType.FILE,
        title = "Import Vault",
        subtitle = null,
        width = 560.dp,
        onDismissRequest = onDismiss,
        dismissEnabled = !isImporting,
        confirmButtonText = if (isImporting) "Importing..." else "Import",
        confirmEnabled = !isImporting && vaultFilePath.isNotBlank() && localTargetDir.isNotBlank(),
        onConfirm = {
            scope.launch {
                isImporting = true
                errorText = null
                runCatching {
                    val imported = engine.arbiter.importExistingVault(
                        vaultFile = File(vaultFilePath.trim()),
                        localTargetDirectory = File(localTargetDir.trim()),
                        displayNameOverride = customDisplayName.trim().ifBlank { null },
                    )
                    onImported(imported)
                }.onFailure { err ->
                    errorText = formatUserFriendlyError(err)
                }
                isImporting = false
            }
        },
    ) {
        VaultFileInputField(
            value = vaultFilePath,
            onValueChange = { selected ->
                vaultFilePath = selected
                val baseName = File(selected.trim()).nameWithoutExtension
                if (baseName.isNotBlank() && localTargetDir == defaultImportedTarget) {
                    localTargetDir = File(
                        System.getProperty("user.home") ?: ".",
                        "Workspaces/$baseName",
                    ).absolutePath
                }
            },
            label = "Vault File",
            enabled = !isImporting,
            dialogTitle = "Select Vault File",
        )

        DirectoryInputField(
            value = localTargetDir,
            onValueChange = { localTargetDir = it },
            label = "Working Folder",
            enabled = !isImporting,
            dialogTitle = "Select Working Folder",
        )

        StyledTextField(
            value = customDisplayName,
            onValueChange = { customDisplayName = it },
            label = "Name (optional)",
            placeholder = "Leave blank to use original name...",
            enabled = !isImporting,
            modifier = Modifier.fillMaxWidth(),
        )

        if (errorText != null) {
            Text(
                text = errorText!!,
                color = DangerColor,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun SwapProgressHud(
    busyEntries: List<Pair<WorkspaceId, WorkspaceState.Busy>>,
    workspaces: List<WorkspaceConfig>,
) {
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = BgSurfaceHover),
        border = BorderStroke(1.dp, AccentPrimary.copy(alpha = 0.5f)),
        modifier = Modifier.width(360.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for ((id, busy) in busyEntries) {
                val name = workspaces.firstOrNull { it.id == id }?.displayName ?: id.value

                val operationLabel = busy.statusMessage.ifBlank {
                    when (busy.operation) {
                        WorkspaceOperation.LOCKING -> "Checking for open files..."
                        WorkspaceOperation.KEY_CHECK -> "Checking credentials..."
                        WorkspaceOperation.KEY_DERIVATION -> "Verifying password..."
                        WorkspaceOperation.PASSWORD_PROMPT -> "Waiting for password..."
                        WorkspaceOperation.VALIDATING -> "Checking available space..."
                        WorkspaceOperation.ARBITRATING -> "Freeing space..."
                        WorkspaceOperation.EVICTING -> "Archiving..."
                        WorkspaceOperation.EXTRACTING -> "Restoring..."
                        WorkspaceOperation.PURGING -> "Cleaning up..."
                        WorkspaceOperation.ROTATING_KEY -> "Re-encrypting vault..."
                    }
                }

                val bytesLabel = when {
                    busy.totalBytes > 0L -> {
                        val doneMb = busy.bytesProcessed / (1024.0 * 1024.0)
                        val totalMb = busy.totalBytes / (1024.0 * 1024.0)
                        val pct = (busy.progressFraction * 100).toInt().coerceIn(0, 100)
                        if (totalMb >= 1000.0) {
                            "%.1f / %.1f GB (%d%%)".format(doneMb / 1024.0, totalMb / 1024.0, pct)
                        } else if (totalMb >= 1.0) {
                            "%.1f / %.1f MB (%d%%)".format(doneMb, totalMb, pct)
                        } else {
                            "${busy.bytesProcessed / 1024} / ${busy.totalBytes / 1024} KB ($pct%)"
                        }
                    }
                    busy.progressFraction > 0f -> "${(busy.progressFraction * 100).toInt().coerceIn(0, 100)}%"
                    else -> ""
                }

                // Short 60ms linear interpolation matches the 25ms state emission rate without lagging
                val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
                    targetValue = busy.progressFraction.coerceIn(0.01f, 1f),
                    animationSpec = androidx.compose.animation.core.tween(
                        durationMillis = 60,
                        easing = androidx.compose.animation.core.LinearEasing,
                    ),
                    label = "progress_${id.value}",
                )
                val isIndeterminate = busy.totalBytes == 0L && busy.progressFraction <= 0.06f

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = name,
                                color = TextMain,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = operationLabel,
                                color = TextMuted,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (bytesLabel.isNotEmpty()) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = bytesLabel,
                                color = AccentPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                    if (isIndeterminate) {
                        LinearProgressIndicator(
                            color = AccentPrimary,
                            trackColor = BgBase,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = animatedProgress,
                            color = AccentPrimary,
                            trackColor = BgBase,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp)
                                .clip(RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
        }
    }
}

