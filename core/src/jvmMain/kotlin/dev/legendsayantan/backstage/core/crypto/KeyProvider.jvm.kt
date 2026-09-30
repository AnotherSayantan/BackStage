package dev.legendsayantan.backstage.core.crypto

import com.sun.jna.platform.win32.Crypt32Util
import dev.legendsayantan.backstage.core.platform.Platform
import dev.legendsayantan.backstage.core.platform.PlatformContext
import dev.legendsayantan.backstage.core.platform.SecureRandomProvider
import dev.legendsayantan.backstage.core.platform.currentPlatform
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * JVM KeyProvider supporting both Windows and Linux:
 *  - Windows: Uses Win32 DPAPI (`CryptProtectData` / `CryptUnprotectData` via JNA `Crypt32Util`)
 *    bound to the current Windows user login credential, stored per-alias in `%LOCALAPPDATA%\dev.legendsayantan.backstage\keystore`.
 *  - Linux: Uses Freedesktop Secret Service (`secret-tool` / `libsecret`) when a D-Bus keyring session is active,
 *    with an automatic machine-bound AES-256-GCM encrypted keyring file fallback (`0600` permissions).
 */
actual class KeyProvider actual constructor(
    private val context: PlatformContext,
) {
    private val keystoreDir: File by lazy {
        File(context.resolveAppDataDir(), "keystore").apply { mkdirs() }
    }

    private val installationSecretFile: File by lazy {
        File(context.resolveAppDataDir().apply { mkdirs() }, "installation.secret")
    }

    @Volatile
    private var _degradedProtectionWarning: String? = null

    actual val degradedProtectionWarning: String?
        get() = _degradedProtectionWarning

    private val secretToolExecutable: String? by lazy {
        val standardPaths = listOf(
            "/usr/bin/secret-tool",
            "/usr/local/bin/secret-tool",
            "/bin/secret-tool",
        )
        standardPaths.firstOrNull { File(it).canExecute() } ?: runCatching {
            val proc = ProcessBuilder("which", "secret-tool").start()
            if (proc.waitFor(2, TimeUnit.SECONDS) && proc.exitValue() == 0) {
                proc.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() && File(it).canExecute() }
            } else {
                null
            }
        }.getOrNull()
    }

    actual fun store(alias: String, bytes: ByteArray) {
        when (currentPlatform()) {
            Platform.WINDOWS -> storeWindowsDpapi(alias, bytes)
            Platform.LINUX, Platform.MACOS, Platform.ANDROID -> storeLinuxKeyring(alias, bytes)
        }
    }

    actual fun retrieve(alias: String): ByteArray =
        retrieveOrNull(alias) ?: throw KeyNotFoundException(alias)

    actual fun retrieveOrNull(alias: String): ByteArray? =
        when (currentPlatform()) {
            Platform.WINDOWS -> retrieveWindowsDpapi(alias)
            Platform.LINUX, Platform.MACOS, Platform.ANDROID -> retrieveLinuxKeyring(alias)
        }

    actual fun delete(alias: String) {
        when (currentPlatform()) {
            Platform.WINDOWS -> aliasFile(alias).delete()
            Platform.LINUX, Platform.MACOS, Platform.ANDROID -> {
                deleteLinuxSecretTool(alias)
                aliasFile(alias).delete()
            }
        }
    }

    actual fun exists(alias: String): Boolean =
        when (currentPlatform()) {
            Platform.WINDOWS -> aliasFile(alias).isFile
            Platform.LINUX, Platform.MACOS, Platform.ANDROID ->
                aliasFile(alias).isFile || lookupLinuxSecretTool(alias) != null
        }

    private fun storeWindowsDpapi(alias: String, bytes: ByteArray) {
        val framedBytes = try {
            val dpapiBlob = Crypt32Util.cryptProtectData(bytes)
            byteArrayOf(SCHEME_DPAPI) + dpapiBlob
        } catch (e: Throwable) {
            // Fallback if running in a headless/container Windows environment without DPAPI profile
            _degradedProtectionWarning =
                "Windows DPAPI protection was unavailable (${e.message ?: "no user profile"}); keystore fell back to local-machine AES key."
            byteArrayOf(SCHEME_LOCAL_MACHINE_AES) + encryptWithLocalMachineKey(bytes)
        }
        aliasFile(alias).writeBytes(framedBytes)
    }

    private fun retrieveWindowsDpapi(alias: String): ByteArray? {
        val file = aliasFile(alias)
        if (!file.isFile) return null
        val raw = file.readBytes()
        if (raw.isEmpty()) return null

        return when (raw[0]) {
            SCHEME_DPAPI -> {
                val payload = raw.copyOfRange(1, raw.size)
                runCatching { Crypt32Util.cryptUnprotectData(payload) }.getOrNull()
            }
            SCHEME_LOCAL_MACHINE_AES -> {
                _degradedProtectionWarning =
                    "Keystore key '$alias' is protected with degraded local-machine AES instead of Windows DPAPI."
                val payload = raw.copyOfRange(1, raw.size)
                runCatching { decryptWithLocalMachineKey(payload) }.getOrNull()
            }
            else -> {
                // Legacy unframed blob compatibility
                try {
                    Crypt32Util.cryptUnprotectData(raw)
                } catch (_: Throwable) {
                    _degradedProtectionWarning =
                        "Keystore key '$alias' is protected with degraded local-machine AES instead of Windows DPAPI."
                    runCatching { decryptWithLocalMachineKey(raw) }.getOrNull()
                }
            }
        }
    }

    private fun storeLinuxKeyring(alias: String, bytes: ByteArray) {
        // Store in Freedesktop Secret Service if secret-tool is available and DBus session is active
        storeLinuxSecretTool(alias, bytes)
        // Also persist in installation-bound AES-256-GCM file (0600) so headless CLI and GUI share reliable access
        val encrypted = byteArrayOf(SCHEME_LOCAL_MACHINE_AES) + encryptWithLocalMachineKey(bytes)
        val file = aliasFile(alias)
        file.writeBytes(encrypted)
        runCatching {
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }

    private fun retrieveLinuxKeyring(alias: String): ByteArray? {
        val file = aliasFile(alias)
        if (file.isFile) {
            val raw = file.readBytes()
            val payload = if (raw.isNotEmpty() && raw[0] == SCHEME_LOCAL_MACHINE_AES) {
                raw.copyOfRange(1, raw.size)
            } else {
                raw
            }
            runCatching { decryptWithLocalMachineKey(payload) }.getOrNull()?.let { return it }
        }
        return lookupLinuxSecretTool(alias)
    }

    private fun storeLinuxSecretTool(alias: String, bytes: ByteArray): Boolean {
        val exe = secretToolExecutable ?: return false
        if (System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) {
            return false
        }
        return try {
            val proc = ProcessBuilder(
                exe,
                "store",
                "--label=BackStage ($alias)",
                "application", "dev.legendsayantan.backstage",
                "alias", alias
            ).start()
            proc.outputStream.use { out ->
                out.write(bytes.toHexString().encodeToByteArray())
            }
            proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0
        } catch (_: Exception) {
            false
        }
    }

    private fun lookupLinuxSecretTool(alias: String): ByteArray? {
        val exe = secretToolExecutable ?: return null
        if (System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) {
            return null
        }
        return try {
            val proc = ProcessBuilder(
                exe,
                "lookup",
                "application", "dev.legendsayantan.backstage",
                "alias", alias
            ).start()
            if (proc.waitFor(5, TimeUnit.SECONDS) && proc.exitValue() == 0) {
                val hex = proc.inputStream.bufferedReader().readText().trim()
                if (hex.isNotEmpty()) hex.hexToByteArray() else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun deleteLinuxSecretTool(alias: String) {
        val exe = secretToolExecutable ?: return
        if (System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) {
            return
        }
        runCatching {
            ProcessBuilder(
                exe,
                "clear",
                "application", "dev.legendsayantan.backstage",
                "alias", alias
            ).start().waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun aliasFile(alias: String): File {
        val hashHex = Blake3.hash(alias.encodeToByteArray()).toHexString()
        return File(keystoreDir, "$hashHex.key")
    }

    private fun getOrCreateInstallationSecret(): ByteArray {
        if (installationSecretFile.isFile) {
            val existing = installationSecretFile.readBytes()
            if (existing.size == 32) return existing
        }
        val secret = SecureRandomProvider.nextBytes(32)
        installationSecretFile.writeBytes(secret)
        runCatching {
            Files.setPosixFilePermissions(installationSecretFile.toPath(), PosixFilePermissions.fromString("rw-------"))
        }
        return secret
    }

    private fun deriveLocalMachineKey(): ByteArray {
        val machineId = listOf(
            File("/etc/machine-id"),
            File("/var/lib/dbus/machine-id"),
        ).firstOrNull { it.isFile }?.readText()?.trim()
            ?: System.getProperty("user.name").orEmpty()
        val installSecretHex = getOrCreateInstallationSecret().toHexString()
        val seed = "dev.legendsayantan.backstage|$machineId|${System.getProperty("user.home")}|$installSecretHex"
        return Blake3.hash(seed.encodeToByteArray())
    }

    private fun encryptWithLocalMachineKey(plaintext: ByteArray): ByteArray {
        val key = deriveLocalMachineKey()
        val nonce = SecureRandomProvider.nextBytes(AeadCipher.NONCE_BYTES)
        val ct = AeadCipher.encryptAesGcm(plaintext, key, nonce)
        key.fill(0)
        return nonce + ct
    }

    private fun decryptWithLocalMachineKey(blob: ByteArray): ByteArray {
        require(blob.size >= AeadCipher.NONCE_BYTES + AeadCipher.TAG_BYTES) { "Invalid keystore blob" }
        val key = deriveLocalMachineKey()
        val nonce = blob.copyOfRange(0, AeadCipher.NONCE_BYTES)
        val ct = blob.copyOfRange(AeadCipher.NONCE_BYTES, blob.size)
        return try {
            AeadCipher.decryptAesGcm(ct, key, nonce)
        } finally {
            key.fill(0)
        }
    }

    companion object {
        private const val SCHEME_DPAPI: Byte = 0x01
        private const val SCHEME_LOCAL_MACHINE_AES: Byte = 0x02
    }
}
