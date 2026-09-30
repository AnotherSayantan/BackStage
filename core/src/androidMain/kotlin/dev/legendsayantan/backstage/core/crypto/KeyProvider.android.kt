package dev.legendsayantan.backstage.core.crypto

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.legendsayantan.backstage.core.platform.PlatformContext

actual class KeyProvider actual constructor(
    private val context: PlatformContext,
) {
    actual val degradedProtectionWarning: String? = null

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context.androidContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .setUserAuthenticationRequired(false) // Layer 1 BiometricPrompt enforces device auth
            .build()

        EncryptedSharedPreferences.create(
            context.androidContext,
            "dev.legendsayantan.backstage.keystore",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    actual fun store(alias: String, bytes: ByteArray) {
        prefs.edit().putString(alias, bytes.toHexString()).apply()
    }

    actual fun retrieve(alias: String): ByteArray =
        retrieveOrNull(alias) ?: throw KeyNotFoundException(alias)

    actual fun retrieveOrNull(alias: String): ByteArray? =
        prefs.getString(alias, null)?.hexToByteArray()

    actual fun delete(alias: String) {
        prefs.edit().remove(alias).apply()
    }

    actual fun exists(alias: String): Boolean = prefs.contains(alias)
}
