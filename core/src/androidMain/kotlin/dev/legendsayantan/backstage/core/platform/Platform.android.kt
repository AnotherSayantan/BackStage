package dev.legendsayantan.backstage.core.platform

import android.content.Context
import androidx.fragment.app.FragmentActivity
import java.io.File
import java.security.SecureRandom

actual fun currentPlatform(): Platform = Platform.ANDROID

actual class PlatformContext(
    val androidContext: Context,
    val activityProvider: () -> FragmentActivity? = { androidContext as? FragmentActivity },
) {
    actual fun resolveAppDataDir(): File = androidContext.filesDir.apply { mkdirs() }
}

actual object SecureRandomProvider {
    private val secureRandom = SecureRandom()

    actual fun nextBytes(size: Int): ByteArray {
        val bytes = ByteArray(size)
        secureRandom.nextBytes(bytes)
        return bytes
    }
}
