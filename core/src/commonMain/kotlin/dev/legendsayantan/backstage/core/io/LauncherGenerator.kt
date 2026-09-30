package dev.legendsayantan.backstage.core.io

import dev.legendsayantan.backstage.core.platform.Platform
import dev.legendsayantan.backstage.core.platform.currentPlatform
import dev.legendsayantan.backstage.core.workspace.WorkspaceConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/**
 * Generates in-workspace launcher scripts (`backstage.bat` on Windows, `backstage.sh` on Linux)
 * and desktop/application shortcuts (`.lnk` on Windows, `.desktop` on Linux).
 */
object LauncherGenerator {

    fun writeInFolderLauncher(
        workspace: WorkspaceConfig,
        targetDirectory: File,
        platform: Platform = currentPlatform(),
    ): File? {
        if (!targetDirectory.isDirectory) return null
        return when (platform) {
            Platform.WINDOWS -> {
                val batFile = File(targetDirectory, "backstage.bat")
                batFile.writeText(
                    """
                    @echo off
                    REM BackStage Contextual Workspace Launcher
                    REM Workspace: ${workspace.displayName} (${workspace.id.value})
                    bstage swap ${workspace.id.value}
                    """.trimIndent() + "\r\n"
                )
                batFile
            }

            Platform.LINUX, Platform.MACOS -> {
                val shFile = File(targetDirectory, "backstage.sh")
                shFile.writeText(
                    """
                    #!/usr/bin/env sh
                    # BackStage Contextual Workspace Launcher
                    # Workspace: ${workspace.displayName} (${workspace.id.value})
                    exec bstage swap "${workspace.id.value}"
                    """.trimIndent() + "\n"
                )
                runCatching {
                    val perms = Files.getPosixFilePermissions(shFile.toPath()).toMutableSet()
                    perms.add(PosixFilePermission.OWNER_EXECUTE)
                    perms.add(PosixFilePermission.GROUP_EXECUTE)
                    Files.setPosixFilePermissions(shFile.toPath(), perms)
                }.onFailure {
                    shFile.setExecutable(true)
                }
                shFile
            }

            Platform.ANDROID -> null
        }
    }

    fun createDesktopShortcut(
        workspace: WorkspaceConfig,
        shortcutDirectory: File,
        bstageExecutablePath: String = "bstage",
        platform: Platform = currentPlatform(),
    ): File? {
        shortcutDirectory.mkdirs()
        return when (platform) {
            Platform.LINUX -> {
                val desktopFile = File(shortcutDirectory, "backstage-${workspace.id.value}.desktop")
                desktopFile.writeText(
                    """
                    [Desktop Entry]
                    Type=Application
                    Version=1.0
                    Name=BackStage: ${workspace.displayName}
                    Comment=Swap and open workspace ${workspace.displayName}
                    Exec=$bstageExecutablePath swap ${workspace.id.value} --headless
                    Icon=${workspace.iconImagePath ?: "folder"}
                    Terminal=false
                    Categories=Development;Utility;
                    """.trimIndent() + "\n"
                )
                desktopFile.setExecutable(true)
                desktopFile
            }

            Platform.WINDOWS -> {
                val cmdShortcut = File(shortcutDirectory, "BackStage - ${workspace.displayName}.cmd")
                cmdShortcut.writeText(
                    """
                    @echo off
                    "$bstageExecutablePath" swap ${workspace.id.value} --headless
                    start "" "${workspace.resolvedLocalPath(Platform.WINDOWS)}"
                    """.trimIndent() + "\r\n"
                )
                cmdShortcut
            }

            else -> null
        }
    }

    /**
     * Registers a background OS scheduled task (Windows Task Scheduler `schtasks` or Linux `systemd --user` timer)
     * to stage encrypted prefetches for [workspace].
     */
    fun registerScheduledPrefetch(
        workspace: WorkspaceConfig,
        bstageExecutablePath: String = "bstage",
        platform: Platform = currentPlatform(),
        systemdUserDirOverride: File? = null,
    ): Boolean {
        val schedule = workspace.prefetchSchedule ?: return false
        return when (platform) {
            Platform.WINDOWS -> {
                val taskName = "BackStage_Prefetch_${workspace.id.value}"
                val timeStr = parseCronToHHmm(schedule.cronExpression)
                runCatching {
                    val proc = ProcessBuilder(
                        "schtasks",
                        "/Create",
                        "/F",
                        "/SC", "DAILY",
                        "/ST", timeStr,
                        "/TN", taskName,
                        "/TR", "\"$bstageExecutablePath\" prefetch ${workspace.id.value}"
                    ).redirectErrorStream(true).start()
                    proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && proc.exitValue() == 0
                }.getOrDefault(false)
            }

            Platform.LINUX -> {
                val home = System.getProperty("user.home") ?: "."
                val systemdDir = systemdUserDirOverride ?: File(home, ".config/systemd/user")
                systemdDir.mkdirs()
                val unitBase = "backstage-prefetch-${workspace.id.value}"
                val serviceFile = File(systemdDir, "$unitBase.service")
                val timerFile = File(systemdDir, "$unitBase.timer")
                val onCalendar = parseCronToSystemdCalendar(schedule.cronExpression)

                serviceFile.writeText(
                    """
                    [Unit]
                    Description=BackStage Encrypted Prefetch for ${workspace.displayName} (${workspace.id.value})

                    [Service]
                    Type=oneshot
                    ExecStart=$bstageExecutablePath prefetch ${workspace.id.value}
                    """.trimIndent() + "\n"
                )

                timerFile.writeText(
                    """
                    [Unit]
                    Description=Schedule BackStage Prefetch for ${workspace.displayName}

                    [Timer]
                    OnCalendar=$onCalendar
                    Persistent=true

                    [Install]
                    WantedBy=timers.target
                    """.trimIndent() + "\n"
                )

                if (systemdUserDirOverride == null) {
                    runCatching {
                        ProcessBuilder("systemctl", "--user", "daemon-reload").start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                        ProcessBuilder("systemctl", "--user", "enable", "--now", "$unitBase.timer").start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                }
                true
            }

            else -> false
        }
    }

    private fun parseCronToHHmm(cron: String): String {
        val parts = cron.trim().split(Regex("\\s+"))
        val minute = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val hour = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
        return "%02d:%02d".format(hour, minute)
    }

    private fun parseCronToSystemdCalendar(cron: String): String {
        val parts = cron.trim().split(Regex("\\s+"))
        val minute = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 59) ?: 0
        val hour = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
        return "*-*-* %02d:%02d:00".format(hour, minute)
    }
}
