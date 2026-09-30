package dev.legendsayantan.backstage.core.workspace

import dev.legendsayantan.backstage.core.crypto.VaultFormatConfig
import java.sql.Connection
import java.sql.Types

enum class AuditEventType {
    LOAD,
    EVICT,
    AUTH,
    PASSWORD_CHANGE,
    KEY_ROTATE,
    NEW_DEVICE_UNLOCK,
    STALE_KEY_DETECTED,
    FORMAT_MIGRATE,
    ERROR
}

data class AuditLogEntry(
    val id: Long,
    val workspaceId: WorkspaceId?,
    val eventType: AuditEventType,
    val detailJson: String?,
    val occurredAtEpochSeconds: Long,
)

class AuditLogRepository(
    private val connection: Connection,
) {
    @Synchronized
    fun record(
        workspaceId: WorkspaceId?,
        eventType: AuditEventType,
        detailJson: String? = null,
        occurredAtEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    ) {
        val sql = "INSERT INTO audit_log (workspace_id, event_type, detail_json, occurred_at) VALUES (?, ?, ?, ?)"
        connection.prepareStatement(sql).use { ps ->
            if (workspaceId != null) {
                ps.setString(1, workspaceId.value)
            } else {
                ps.setNull(1, Types.VARCHAR)
            }
            ps.setString(2, eventType.name)
            if (detailJson != null) {
                ps.setString(3, detailJson)
            } else {
                ps.setNull(3, Types.VARCHAR)
            }
            ps.setLong(4, occurredAtEpochSeconds)
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun findRecent(limit: Int = 50): List<AuditLogEntry> {
        val sql = "SELECT * FROM audit_log ORDER BY occurred_at DESC, id DESC LIMIT ?"
        connection.prepareStatement(sql).use { ps ->
            ps.setInt(1, limit)
            ps.executeQuery().use { rs ->
                val list = mutableListOf<AuditLogEntry>()
                while (rs.next()) {
                    val wsId = rs.getString("workspace_id")?.let { WorkspaceId(it) }
                    list.add(
                        AuditLogEntry(
                            id = rs.getLong("id"),
                            workspaceId = wsId,
                            eventType = AuditEventType.valueOf(rs.getString("event_type")),
                            detailJson = rs.getString("detail_json"),
                            occurredAtEpochSeconds = rs.getLong("occurred_at"),
                        )
                    )
                }
                return list
            }
        }
    }
}

class AppConfigRepository(
    private val connection: Connection,
) {
    @Synchronized
    fun get(key: String): String? {
        connection.prepareStatement("SELECT value FROM app_config WHERE key = ?").use { ps ->
            ps.setString(1, key)
            ps.executeQuery().use { rs ->
                return if (rs.next()) rs.getString("value") else null
            }
        }
    }

    @Synchronized
    fun set(key: String, value: String) {
        val sql = """
            INSERT INTO app_config (key, value) VALUES (?, ?)
            ON CONFLICT(key) DO UPDATE SET value = excluded.value
        """.trimIndent()
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, key)
            ps.setString(2, value)
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun getAll(): Map<String, String> {
        connection.prepareStatement("SELECT key, value FROM app_config ORDER BY key ASC").use { ps ->
            ps.executeQuery().use { rs ->
                val map = linkedMapOf<String, String>()
                while (rs.next()) {
                    map[rs.getString("key")] = rs.getString("value")
                }
                return map
            }
        }
    }

    var autoUnloadEnabled: Boolean
        get() = get(KEY_AUTO_UNLOAD)?.toBooleanStrictOrNull() ?: true
        set(value) = set(KEY_AUTO_UNLOAD, value.toString())

    var defaultVaultFormat: VaultFormatConfig
        get() = when (get(KEY_DEFAULT_VAULT_FORMAT)?.lowercase()) {
            "portable", "7z" -> VaultFormatConfig.Portable
            "plain", "plaincompressed", "bvault" -> VaultFormatConfig.PlainCompressed
            else -> VaultFormatConfig.Native
        }
        set(value) {
            val str = when (value) {
                VaultFormatConfig.Native -> "Native"
                VaultFormatConfig.Portable -> "Portable"
                VaultFormatConfig.PlainCompressed -> "PlainCompressed"
            }
            set(KEY_DEFAULT_VAULT_FORMAT, str)
        }

    var prefetchOnIdleOnly: Boolean
        get() = get(KEY_PREFETCH_ON_IDLE)?.toBooleanStrictOrNull() ?: true
        set(value) = set(KEY_PREFETCH_ON_IDLE, value.toString())

    companion object {
        const val KEY_AUTO_UNLOAD = "auto_unload_enabled"
        const val KEY_DEFAULT_VAULT_FORMAT = "default_vault_format"
        const val KEY_PREFETCH_ON_IDLE = "prefetch_on_idle_only"
    }
}
