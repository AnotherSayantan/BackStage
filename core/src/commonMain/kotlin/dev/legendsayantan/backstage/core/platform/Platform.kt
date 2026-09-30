package dev.legendsayantan.backstage.core.platform

import kotlinx.serialization.Serializable

@Serializable
enum class Platform {
    WINDOWS,
    LINUX,
    MACOS,
    ANDROID;

    val isDesktop: Boolean
        get() = this == WINDOWS || this == LINUX || this == MACOS

    val isPosix: Boolean
        get() = this == LINUX || this == MACOS || this == ANDROID
}

expect fun currentPlatform(): Platform

expect class PlatformContext {
    fun resolveAppDataDir(): java.io.File
}

expect object SecureRandomProvider {
    fun nextBytes(size: Int): ByteArray
}
