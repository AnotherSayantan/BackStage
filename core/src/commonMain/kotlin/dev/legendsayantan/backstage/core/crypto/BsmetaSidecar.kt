package dev.legendsayantan.backstage.core.crypto

import dev.legendsayantan.backstage.core.platform.Platform
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BsmetaKdfInfo(
    val algorithm: String = "argon2id",
    @SerialName("m_cost") val mCost: Int = Argon2Config.M_COST,
    @SerialName("t_cost") val tCost: Int = Argon2Config.T_COST,
    val parallelism: Int = Argon2Config.PARALLELISM,
    @SerialName("salt_hex") val saltHex: String,
    @SerialName("wrapped_key_hex") val wrappedKeyHex: String? = null,
)

@Serializable
data class BsmetaManualRecovery(
    val steps: List<String> = listOf(
        "1. Read ARGON2_SALT: bytes at offset 19, length 16 from the .bstage file (or salt_hex in this .bsmeta file).",
        "2. Read WRAP_NONCE: bytes at offset 35, length 12.",
        "3. Read WRAPPED_KEY_CT: bytes at offset 47, length 32 (ciphertext).",
        "4. Read WRAPPED_KEY_TAG: bytes at offset 79, length 16 (GCM auth tag).",
        "5. Run Argon2id (m=65536, t=3, p=4, l=32) with your password and ARGON2_SALT to derive wrap_key.",
        "6. For Native (.bstage): AES-256-GCM decrypt WRAPPED_KEY_CT+TAG using wrap_key and WRAP_NONCE to obtain the 32-byte vault_key.",
        "7. For Portable (.7z): Encode the 32-byte vault_key as 64 lowercase hex characters and pass it as the 7-Zip password: 7z x <archive.7z> -p<HEX_VAULT_KEY>."
    )
)

@Serializable
data class BsmetaSidecar(
    @SerialName("backstage_version") val backstageVersion: Int = 1,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("platform_origin") val platformOrigin: Platform,
    @SerialName("vault_format") val vaultFormat: String,
    @SerialName("archive_filename") val archiveFilename: String,
    @SerialName("password_hint") val passwordHint: String? = null,
    @SerialName("header_mac_hex") val headerMacHex: String? = null,
    val kdf: BsmetaKdfInfo? = null,
    @SerialName("manual_recovery") val manualRecovery: BsmetaManualRecovery = BsmetaManualRecovery(),
) {
    companion object {
        private val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun sidecarFileName(vaultFileName: String): String = "$vaultFileName.bsmeta"

        fun encode(sidecar: BsmetaSidecar): String = json.encodeToString(sidecar)

        fun decode(rawJson: String): BsmetaSidecar = json.decodeFromString(rawJson)
    }
}

fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

fun String.hexToByteArray(): ByteArray {
    val clean = trim().removePrefix("0x").removePrefix("x'").removeSuffix("'")
    require(clean.length % 2 == 0) { "Hex string must have even length" }
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
