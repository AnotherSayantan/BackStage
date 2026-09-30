package dev.legendsayantan.backstage.core.crypto

import dev.legendsayantan.backstage.core.platform.PlatformContext

class KeyNotFoundException(val alias: String) : Exception("Key not found in OS keystore for alias: $alias")

/**
 * Hardware/OS-backed keystore abstraction for storing per-workspace 32-byte vault_keys
 * and the local SQLCipher database key.
 *
 * - Windows: Win32 Credential Manager (CredWriteW / CredReadW) + DPAPI
 * - Linux: Freedesktop Secret Service (secret-tool / libsecret) + machine-bound AES-GCM keyring fallback
 * - Android: Android Keystore MasterKey + EncryptedSharedPreferences
 */
expect class KeyProvider(context: PlatformContext) {
    val degradedProtectionWarning: String?
    fun store(alias: String, bytes: ByteArray)
    fun retrieve(alias: String): ByteArray
    fun retrieveOrNull(alias: String): ByteArray?
    fun delete(alias: String)
    fun exists(alias: String): Boolean
}

object KeyAliases {
    const val DB_KEY_ALIAS = "dev.legendsayantan.backstage/db_key"
    fun workspaceKeyAlias(workspaceId: String): String =
        "dev.legendsayantan.backstage/ws/$workspaceId"
}
