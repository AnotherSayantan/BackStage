package dev.legendsayantan.backstage.core.backend

import dev.legendsayantan.backstage.core.error.BackStageError
import dev.legendsayantan.backstage.core.error.BackStageException
import dev.legendsayantan.backstage.core.platform.Platform
import dev.legendsayantan.backstage.core.platform.currentPlatform
import dev.legendsayantan.backstage.core.workspace.MountedDriveType
import dev.legendsayantan.backstage.core.workspace.VaultBackendConfig
import java.io.File
import java.nio.file.Files

/**
 * Represents an actually mounted storage volume or cloud drive discovered dynamically from the OS.
 */
data class MountedStorageVolume(
    val name: String,
    val subtitle: String,
    val mountPoint: File,
    val defaultVaultDir: File,
    val isCloudMount: Boolean,
    val driveType: MountedDriveType? = null,
    val freeBytes: Long,
    val totalBytes: Long,
)

/**
 * Dynamically discovers mounted local disks and cloud/network drives across Windows, Linux, and macOS
 * by inspecting the OS's actual mounted filesystems (`FileStore`, `/proc/mounts`, GVFS, environment
 * variables, and client mount tables). Never offers unmounted cloud drives.
 */
object MountedDriveDetector {

    fun discoverMountedVolumes(platform: Platform = currentPlatform()): List<MountedStorageVolume> {
        return when (platform) {
            Platform.WINDOWS -> discoverWindowsVolumes()
            Platform.LINUX -> discoverLinuxVolumes()
            Platform.MACOS -> discoverMacOsVolumes()
            Platform.ANDROID -> discoverAndroidVolumes()
        }
    }

    fun detectMountRoot(
        driveType: MountedDriveType,
        platform: Platform = currentPlatform(),
    ): File? {
        val discovered = discoverMountedVolumes(platform)
            .firstOrNull { it.isCloudMount && it.driveType == driveType }
            ?.mountPoint
        return discovered
    }

    private fun discoverWindowsVolumes(): List<MountedStorageVolume> {
        val results = mutableListOf<MountedStorageVolume>()
        val seenCanonicalPaths = mutableSetOf<String>()
        val systemDrive = System.getenv("SystemDrive")?.trimEnd('\\', '/')?.uppercase() ?: "C:"
        val userHome = System.getProperty("user.home").orEmpty()

        // 1. Inspect actual mounted drive letters via NIO FileStore
        val roots = runCatching { File.listRoots().toList() }.getOrDefault(emptyList())
        for (root in roots) {
            if (!root.exists()) continue
            val total = root.totalSpace
            if (total <= 0L) continue
            val free = root.usableSpace

            val store = runCatching { Files.getFileStore(root.toPath()) }.getOrNull()
            val volumeLabel = runCatching { store?.name()?.trim().orEmpty() }.getOrDefault("")
            val fsType = runCatching { store?.type()?.trim().orEmpty() }.getOrDefault("")
            val driveLetter = root.path.trimEnd('\\', '/')

            val myDriveSubdir = File(root, "My Drive")
            val isGoogleDriveMount = volumeLabel.contains("Google Drive", ignoreCase = true) ||
                File(root, ".shortcut-targets-by-id").exists() ||
                (myDriveSubdir.isDirectory && (
                    volumeLabel.contains("Google", ignoreCase = true) ||
                        File(root, "Shared drives").exists() ||
                        File(root, "Other computers").exists()
                    ))

            val isCloudOrNetworkFs = isGoogleDriveMount ||
                volumeLabel.contains("OneDrive", ignoreCase = true) ||
                volumeLabel.contains("Dropbox", ignoreCase = true) ||
                volumeLabel.contains("iCloud", ignoreCase = true) ||
                volumeLabel.contains("Nextcloud", ignoreCase = true) ||
                fsType.contains("WinFsp", ignoreCase = true) ||
                fsType.contains("rclone", ignoreCase = true) ||
                fsType.contains("WebDAV", ignoreCase = true)

            if (isGoogleDriveMount) {
                val writableRoot = if (myDriveSubdir.isDirectory) myDriveSubdir else root
                val canonical = runCatching { writableRoot.canonicalPath.lowercase() }
                    .getOrDefault(writableRoot.absolutePath.lowercase())
                if (seenCanonicalPaths.add(canonical)) {
                    results.add(
                        MountedStorageVolume(
                            name = "Google Drive",
                            subtitle = writableRoot.absolutePath,
                            mountPoint = writableRoot,
                            defaultVaultDir = File(writableRoot, "BackStageVaults"),
                            isCloudMount = true,
                            driveType = MountedDriveType.GOOGLE_DRIVE,
                            freeBytes = free,
                            totalBytes = total,
                        )
                    )
                }
            } else if (isCloudOrNetworkFs) {
                val canonical = runCatching { root.canonicalPath.lowercase() }
                    .getOrDefault(root.absolutePath.lowercase())
                if (seenCanonicalPaths.add(canonical)) {
                    val label = volumeLabel.ifBlank { "Mounted Cloud ($driveLetter)" }
                    val inferredType = when {
                        label.contains("OneDrive", ignoreCase = true) -> MountedDriveType.ONEDRIVE
                        label.contains("iCloud", ignoreCase = true) -> MountedDriveType.ICLOUD_DRIVE
                        else -> MountedDriveType.CUSTOM
                    }
                    results.add(
                        MountedStorageVolume(
                            name = label,
                            subtitle = root.absolutePath,
                            mountPoint = root,
                            defaultVaultDir = File(root, "BackStageVaults"),
                            isCloudMount = true,
                            driveType = inferredType,
                            freeBytes = free,
                            totalBytes = total,
                        )
                    )
                }
            } else {
                // Genuine local or external physical partition
                val canonical = runCatching { root.canonicalPath.lowercase() }
                    .getOrDefault(root.absolutePath.lowercase())
                if (seenCanonicalPaths.add(canonical)) {
                    val isSystemDrive = driveLetter.equals(systemDrive, ignoreCase = true)
                    val defaultVault = if (isSystemDrive && userHome.isNotBlank()) {
                        File(userHome, "BackStageVaults")
                    } else {
                        File(root, "BackStageVaults")
                    }
                    val displayName = if (volumeLabel.isNotBlank()) {
                        "$volumeLabel ($driveLetter)"
                    } else {
                        "Local Disk ($driveLetter)"
                    }
                    results.add(
                        MountedStorageVolume(
                            name = displayName,
                            subtitle = root.absolutePath,
                            mountPoint = root,
                            defaultVaultDir = defaultVault,
                            isCloudMount = false,
                            driveType = null,
                            freeBytes = free,
                            totalBytes = total,
                        )
                    )
                }
            }
        }

        // 2. Discover folder-mounted OneDrive accounts from active OS environment variables
        val oneDriveEnvVars = listOfNotNull(
            System.getenv("OneDrive")?.takeIf { it.isNotBlank() },
            System.getenv("OneDriveCommercial")?.takeIf { it.isNotBlank() },
            System.getenv("OneDriveConsumer")?.takeIf { it.isNotBlank() },
        ).distinct()

        for (envPath in oneDriveEnvVars) {
            val dir = File(envPath)
            if (dir.isDirectory && dir.canRead()) {
                val canonical = runCatching { dir.canonicalPath.lowercase() }
                    .getOrDefault(dir.absolutePath.lowercase())
                if (seenCanonicalPaths.add(canonical)) {
                    val suffix = dir.name.removePrefix("OneDrive").trimStart(' ', '-', '—').trim()
                    val label = if (suffix.isNotBlank()) "OneDrive ($suffix)" else "OneDrive"
                    results.add(
                        MountedStorageVolume(
                            name = label,
                            subtitle = dir.absolutePath,
                            mountPoint = dir,
                            defaultVaultDir = File(dir, "BackStageVaults"),
                            isCloudMount = true,
                            driveType = MountedDriveType.ONEDRIVE,
                            freeBytes = dir.usableSpace,
                            totalBytes = dir.totalSpace.coerceAtLeast(1L),
                        )
                    )
                }
            }
        }

        // 3. Discover Dropbox mount dynamically via %APPDATA%\Dropbox\info.json or %LOCALAPPDATA%\Dropbox\info.json
        val dropboxConfigs = listOfNotNull(
            System.getenv("APPDATA")?.let { File(it, "Dropbox/info.json") },
            System.getenv("LOCALAPPDATA")?.let { File(it, "Dropbox/info.json") },
        )
        for (cfg in dropboxConfigs) {
            if (cfg.isFile) {
                val pathMatch = runCatching {
                    Regex(""""path"\s*:\s*"([^"]+)"""").find(cfg.readText())?.groupValues?.getOrNull(1)
                        ?.replace("\\\\", "\\")
                }.getOrNull()
                if (!pathMatch.isNullOrBlank()) {
                    val dir = File(pathMatch)
                    if (dir.isDirectory && dir.canRead()) {
                        val canonical = runCatching { dir.canonicalPath.lowercase() }
                            .getOrDefault(dir.absolutePath.lowercase())
                        if (seenCanonicalPaths.add(canonical)) {
                            results.add(
                                MountedStorageVolume(
                                    name = "Dropbox",
                                    subtitle = dir.absolutePath,
                                    mountPoint = dir,
                                    defaultVaultDir = File(dir, "BackStageVaults"),
                                    isCloudMount = true,
                                    driveType = MountedDriveType.CUSTOM,
                                    freeBytes = dir.usableSpace,
                                    totalBytes = dir.totalSpace.coerceAtLeast(1L),
                                )
                            )
                        }
                    }
                }
            }
        }

        return results
    }

    private fun discoverLinuxVolumes(): List<MountedStorageVolume> {
        val results = mutableListOf<MountedStorageVolume>()
        val seenCanonicalPaths = mutableSetOf<String>()
        val home = System.getProperty("user.home").orEmpty()

        // 1. Always include primary home/root storage
        val homeDir = File(home.ifBlank { "/" })
        if (homeDir.isDirectory) {
            val canonical = runCatching { homeDir.canonicalPath }.getOrDefault(homeDir.absolutePath)
            seenCanonicalPaths.add(canonical)
            results.add(
                MountedStorageVolume(
                    name = "Home Storage",
                    subtitle = homeDir.absolutePath,
                    mountPoint = homeDir,
                    defaultVaultDir = File(homeDir, "BackStageVaults"),
                    isCloudMount = false,
                    driveType = null,
                    freeBytes = homeDir.usableSpace,
                    totalBytes = homeDir.totalSpace.coerceAtLeast(1L),
                )
            )
        }

        // 2. Parse /proc/mounts dynamically for real mounted disks and FUSE/cloud/network mounts
        val procMounts = File("/proc/mounts")
        if (procMounts.isFile && procMounts.canRead()) {
            runCatching {
                procMounts.useLines { lines ->
                    for (line in lines) {
                        val parts = line.split(' ')
                        if (parts.size < 3) continue
                        val device = parts[0]
                        val mountPointStr = parts[1]
                            .replace("\\040", " ")
                            .replace("\\011", "\t")
                        val fsType = parts[2].lowercase()

                        if (mountPointStr == "/" ||
                            mountPointStr.startsWith("/proc") ||
                            mountPointStr.startsWith("/sys") ||
                            mountPointStr.startsWith("/dev") ||
                            mountPointStr.startsWith("/run/credentials") ||
                            mountPointStr.startsWith("/snap") ||
                            mountPointStr.startsWith("/boot")
                        ) continue

                        val isCloudFuse = fsType.contains("rclone") ||
                            fsType.contains("google-drive") ||
                            fsType.contains("onedrive") ||
                            fsType.contains("davfs") ||
                            fsType.contains("cifs") ||
                            fsType.contains("smb") ||
                            fsType.contains("nfs") ||
                            fsType.contains("sshfs")

                        val isExternalDiskMount = device.startsWith("/dev/") &&
                            (mountPointStr.startsWith("/mnt/") ||
                                mountPointStr.startsWith("/media/") ||
                                mountPointStr.startsWith("/run/media/"))

                        if (!isCloudFuse && !isExternalDiskMount) continue

                        val dir = File(mountPointStr)
                        if (!dir.isDirectory || !dir.canRead() || dir.totalSpace <= 0L) continue
                        val canonical = runCatching { dir.canonicalPath }.getOrDefault(dir.absolutePath)
                        if (!seenCanonicalPaths.add(canonical)) continue

                        val driveType = when {
                            fsType.contains("google") || device.contains("gdrive", ignoreCase = true) ->
                                MountedDriveType.GOOGLE_DRIVE
                            fsType.contains("onedrive") || device.contains("onedrive", ignoreCase = true) ->
                                MountedDriveType.ONEDRIVE
                            fsType.contains("cifs") || fsType.contains("smb") ->
                                MountedDriveType.NAS_CIFS
                            fsType.contains("nfs") ->
                                MountedDriveType.NAS_NFS
                            else -> if (isCloudFuse) MountedDriveType.CUSTOM else null
                        }

                        results.add(
                            MountedStorageVolume(
                                name = dir.name.ifBlank { mountPointStr },
                                subtitle = dir.absolutePath,
                                mountPoint = dir,
                                defaultVaultDir = File(dir, "BackStageVaults"),
                                isCloudMount = isCloudFuse,
                                driveType = driveType,
                                freeBytes = dir.usableSpace,
                                totalBytes = dir.totalSpace.coerceAtLeast(1L),
                            )
                        )
                    }
                }
            }
        }

        // 3. Inspect /run/user/<uid>/gvfs for active GVFS cloud mounts
        val runUserDir = File("/run/user")
        runUserDir.listFiles()?.forEach { uidDir ->
            val gvfs = File(uidDir, "gvfs")
            gvfs.listFiles()?.forEach { mountDir ->
                if (mountDir.isDirectory && mountDir.canRead()) {
                    val canonical = runCatching { mountDir.canonicalPath }.getOrDefault(mountDir.absolutePath)
                    if (seenCanonicalPaths.add(canonical)) {
                        val (label, type) = when {
                            mountDir.name.startsWith("google-drive:") ->
                                "Google Drive (${mountDir.name.substringAfter("user=", "").substringBefore(",")})" to MountedDriveType.GOOGLE_DRIVE
                            mountDir.name.startsWith("onedrive:") ->
                                "OneDrive" to MountedDriveType.ONEDRIVE
                            mountDir.name.startsWith("smb-share:") ->
                                "Network Share (${mountDir.name.substringAfter("share=", "").substringBefore(",")})" to MountedDriveType.NAS_CIFS
                            else -> mountDir.name to MountedDriveType.CUSTOM
                        }
                        results.add(
                            MountedStorageVolume(
                                name = label,
                                subtitle = mountDir.absolutePath,
                                mountPoint = mountDir,
                                defaultVaultDir = File(mountDir, "BackStageVaults"),
                                isCloudMount = true,
                                driveType = type,
                                freeBytes = mountDir.usableSpace,
                                totalBytes = mountDir.totalSpace.coerceAtLeast(1L),
                            )
                        )
                    }
                }
            }
        }

        return results
    }

    private fun discoverMacOsVolumes(): List<MountedStorageVolume> {
        val results = mutableListOf<MountedStorageVolume>()
        val seenCanonicalPaths = mutableSetOf<String>()
        val home = System.getProperty("user.home").orEmpty()

        val homeDir = File(home.ifBlank { "/" })
        if (homeDir.isDirectory) {
            seenCanonicalPaths.add(runCatching { homeDir.canonicalPath }.getOrDefault(homeDir.absolutePath))
            results.add(
                MountedStorageVolume(
                    name = "Macintosh HD",
                    subtitle = homeDir.absolutePath,
                    mountPoint = homeDir,
                    defaultVaultDir = File(homeDir, "BackStageVaults"),
                    isCloudMount = false,
                    driveType = null,
                    freeBytes = homeDir.usableSpace,
                    totalBytes = homeDir.totalSpace.coerceAtLeast(1L),
                )
            )
        }

        val cloudStorage = File(home, "Library/CloudStorage")
        if (cloudStorage.isDirectory) {
            cloudStorage.listFiles()?.forEach { providerDir ->
                if (!providerDir.isDirectory || !providerDir.canRead()) return@forEach
                val writableDir = if (providerDir.name.startsWith("GoogleDrive-")) {
                    File(providerDir, "My Drive").takeIf { it.isDirectory } ?: providerDir
                } else {
                    providerDir
                }
                val canonical = runCatching { writableDir.canonicalPath }.getOrDefault(writableDir.absolutePath)
                if (seenCanonicalPaths.add(canonical)) {
                    val type = when {
                        providerDir.name.startsWith("GoogleDrive-") -> MountedDriveType.GOOGLE_DRIVE
                        providerDir.name.startsWith("OneDrive") -> MountedDriveType.ONEDRIVE
                        else -> MountedDriveType.CUSTOM
                    }
                    results.add(
                        MountedStorageVolume(
                            name = providerDir.name.replace('-', ' '),
                            subtitle = writableDir.absolutePath,
                            mountPoint = writableDir,
                            defaultVaultDir = File(writableDir, "BackStageVaults"),
                            isCloudMount = true,
                            driveType = type,
                            freeBytes = writableDir.usableSpace,
                            totalBytes = writableDir.totalSpace.coerceAtLeast(1L),
                        )
                    )
                }
            }
        }

        return results
    }

    private fun discoverAndroidVolumes(): List<MountedStorageVolume> {
        val home = File(System.getProperty("user.home") ?: ".")
        return if (home.isDirectory) {
            listOf(
                MountedStorageVolume(
                    name = "Device Storage",
                    subtitle = home.absolutePath,
                    mountPoint = home,
                    defaultVaultDir = File(home, "BackStageVaults"),
                    isCloudMount = false,
                    driveType = null,
                    freeBytes = home.usableSpace,
                    totalBytes = home.totalSpace.coerceAtLeast(1L),
                )
            )
        } else {
            emptyList()
        }
    }
}

object VaultBackendFactory {
    fun create(config: VaultBackendConfig, platform: Platform = currentPlatform()): VaultBackend {
        return when (config) {
            is VaultBackendConfig.LocalPath -> LocalPathBackend(
                rootDirectory = File(config.directoryPath),
                displayName = "Local (${config.directoryPath})",
            )

            is VaultBackendConfig.MountedDrive -> {
                val configuredRoot = config.platformRoots[platform]?.let { File(it) }
                val rootDir = configuredRoot
                    ?: MountedDriveDetector.detectMountRoot(config.driveType, platform)
                    ?: throw BackStageException(
                        BackStageError.MountedDriveNotFound(
                            driveName = config.driveName,
                            expectedRoot = config.platformRoots[platform] ?: config.driveType.name,
                        )
                    )
                val targetDir = if (config.relativePath.isBlank()) {
                    rootDir
                } else {
                    File(rootDir, config.relativePath)
                }
                LocalPathBackend(
                    rootDirectory = targetDir,
                    displayName = "${config.driveName} (${targetDir.path})",
                )
            }

            is VaultBackendConfig.ExternalDrive -> {
                val root = resolveExternalDriveRoot(config, platform)
                val targetDir = if (config.relativePath.isBlank()) root else File(root, config.relativePath)
                LocalPathBackend(
                    rootDirectory = targetDir,
                    displayName = "External Drive [${config.driveLabel}] (${targetDir.path})",
                )
            }

            is VaultBackendConfig.S3Compatible -> {
                throw BackStageException(
                    BackStageError.BackendIOError("S3Compatible backend (${config.endpointUrl}) requires active network credentials")
                )
            }

            is VaultBackendConfig.WebDav -> {
                throw BackStageException(
                    BackStageError.BackendIOError("WebDAV backend (${config.endpointUrl}) requires active network credentials")
                )
            }
        }
    }

    private fun resolveExternalDriveRoot(
        config: VaultBackendConfig.ExternalDrive,
        platform: Platform,
    ): File {
        when (platform) {
            Platform.LINUX -> {
                val byLabel = File("/dev/disk/by-label", config.driveLabel)
                val user = System.getProperty("user.name").orEmpty()
                val mediaCandidate = File("/media/$user/${config.driveLabel}")
                if (mediaCandidate.isDirectory) return mediaCandidate
                val runMediaCandidate = File("/run/media/$user/${config.driveLabel}")
                if (runMediaCandidate.isDirectory) return runMediaCandidate
                if (byLabel.exists()) return byLabel
            }
            Platform.WINDOWS -> {
                for (root in File.listRoots()) {
                    if (root.isDirectory && root.path.startsWith(config.driveLabel, ignoreCase = true)) {
                        return root
                    }
                }
            }
            else -> Unit
        }
        throw BackStageException(
            BackStageError.MountedDriveNotFound(
                driveName = config.driveLabel,
                expectedRoot = config.volumeSerial,
            )
        )
    }
}
