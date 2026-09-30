package dev.legendsayantan.backstage.core.platform

import java.io.File
import java.security.SecureRandom

actual fun currentPlatform(): Platform {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        osName.contains("win") -> Platform.WINDOWS
        osName.contains("linux") -> Platform.LINUX
        osName.contains("mac") || osName.contains("darwin") -> Platform.MACOS
        else -> Platform.LINUX
    }
}

actual class PlatformContext(
    val appDataDirOverride: File? = null,
) {
    constructor() : this(null)

    actual fun resolveAppDataDir(): File {
        appDataDirOverride?.let {
            it.mkdirs()
            return it
        }
        val dir = when (currentPlatform()) {
            Platform.WINDOWS -> {
                val appData = System.getenv("LOCALAPPDATA")
                    ?: "${System.getProperty("user.home")}\\AppData\\Local"
                File(appData, "dev.legendsayantan.backstage")
            }
            Platform.LINUX -> {
                val xdgData = System.getenv("XDG_DATA_HOME")
                    ?: "${System.getProperty("user.home")}/.local/share"
                File(xdgData, "dev.legendsayantan.backstage")
            }
            Platform.MACOS -> {
                File(
                    "${System.getProperty("user.home")}/Library/Application Support",
                    "dev.legendsayantan.backstage"
                )
            }
            Platform.ANDROID -> File(System.getProperty("java.io.tmpdir"), "dev.legendsayantan.backstage")
        }
        dir.mkdirs()
        return dir
    }
}

actual object SecureRandomProvider {
    private val secureRandom = SecureRandom()

    actual fun nextBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return bytes
    }
}
