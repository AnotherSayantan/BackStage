package dev.legendsayantan.backstage.core.crypto

import dev.legendsayantan.backstage.core.error.BackStageError
import dev.legendsayantan.backstage.core.error.BackStageException
import dev.legendsayantan.backstage.core.platform.Platform
import dev.legendsayantan.backstage.core.platform.currentPlatform
import java.io.File

/**
 * Detects an installed system 7-Zip executable (`7z`, `7za`, `7zr`) without bundling 7-Zip.
 * Supports Windows and Linux (and macOS).
 */
object SevenZipDetector {
    private val windowsCandidatePaths = listOf(
        "C:\\Program Files\\7-Zip\\7z.exe",
        "C:\\Program Files (x86)\\7-Zip\\7z.exe",
    )

    private val linuxCandidatePaths = listOf(
        "/usr/bin/7z",
        "/usr/local/bin/7z",
        "/usr/bin/7za",
        "/usr/bin/7zr",
        "/snap/bin/7z",
    )

    private val macosCandidatePaths = listOf(
        "/opt/homebrew/bin/7z",
        "/usr/local/bin/7z",
    )

    fun findExecutable(platform: Platform = currentPlatform()): File? {
        val executableNames = when (platform) {
            Platform.WINDOWS -> listOf("7z.exe", "7za.exe")
            else -> listOf("7z", "7za", "7zr")
        }

        // 1. Search system PATH
        val pathEnv = System.getenv("PATH").orEmpty()
        val pathDirs = pathEnv.split(File.pathSeparatorChar).filter { it.isNotBlank() }
        for (dir in pathDirs) {
            for (exe in executableNames) {
                val candidate = File(dir, exe)
                if (candidate.isFile && candidate.canExecute()) {
                    return candidate
                }
            }
        }

        // 2. Check well-known installation locations per OS
        val knownPaths = when (platform) {
            Platform.WINDOWS -> windowsCandidatePaths
            Platform.LINUX -> linuxCandidatePaths
            Platform.MACOS -> macosCandidatePaths
            Platform.ANDROID -> emptyList()
        }
        for (path in knownPaths) {
            val candidate = File(path)
            if (candidate.isFile && candidate.canExecute()) {
                return candidate
            }
        }

        return null
    }

    fun ensureAvailable(platform: Platform = currentPlatform()): File =
        findExecutable(platform) ?: throw BackStageException(
            BackStageError.SevenZipNotInstalled(installHint = installInstruction(platform))
        )

    fun installInstruction(platform: Platform = currentPlatform()): String = when (platform) {
        Platform.WINDOWS -> "Install 7-Zip from https://www.7-zip.org/download.html or via 'winget install 7zip.7zip'"
        Platform.LINUX -> "Install p7zip via your package manager (e.g. 'sudo apt install p7zip-full' or 'sudo pacman -S 7zip')"
        Platform.MACOS -> "Install 7-Zip via Homebrew: 'brew install sevenzip'"
        Platform.ANDROID -> "Portable .7z creation via external 7z CLI is only supported on Desktop (Windows/Linux); use Native (.bstage) on Android."
    }
}
