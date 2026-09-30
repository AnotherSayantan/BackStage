package dev.legendsayantan.backstage.core.workspace

import dev.legendsayantan.backstage.core.crypto.VaultFormatConfig
import dev.legendsayantan.backstage.core.crypto.WorkspaceKeyMode
import dev.legendsayantan.backstage.core.platform.Platform
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkspaceRepository(
    private val connection: Connection,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun upsert(config: WorkspaceConfig) {
        val sql = """
            INSERT INTO workspace (
                id, display_name, description, icon_emoji, icon_image_path,
                tags_json, vault_backend_json, vault_file_name, local_target_path,
                platform_path_overrides, vault_format_json, key_mode_json,
                group_id, retention_json, prefetch_json, platform_origin,
                created_at, last_active_at, last_evicted_at, uncompressed_bytes
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                display_name = excluded.display_name,
                description = excluded.description,
                icon_emoji = excluded.icon_emoji,
                icon_image_path = excluded.icon_image_path,
                tags_json = excluded.tags_json,
                vault_backend_json = excluded.vault_backend_json,
                vault_file_name = excluded.vault_file_name,
                local_target_path = excluded.local_target_path,
                platform_path_overrides = excluded.platform_path_overrides,
                vault_format_json = excluded.vault_format_json,
                key_mode_json = excluded.key_mode_json,
                group_id = excluded.group_id,
                retention_json = excluded.retention_json,
                prefetch_json = excluded.prefetch_json,
                platform_origin = excluded.platform_origin,
                last_active_at = excluded.last_active_at,
                last_evicted_at = excluded.last_evicted_at,
                uncompressed_bytes = excluded.uncompressed_bytes
        """.trimIndent()

        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, config.id.value)
            ps.setString(2, config.displayName)
            ps.setString(3, config.description)
            ps.setString(4, config.iconEmoji)
            ps.setString(5, config.iconImagePath)
            ps.setString(6, json.encodeToString(config.tags))
            ps.setString(7, json.encodeToString<VaultBackendConfig>(config.vaultBackend))
            ps.setString(8, config.vaultFileName)
            ps.setString(9, config.localTargetPath)
            ps.setString(10, json.encodeToString(config.platformPathOverrides))
            ps.setString(11, json.encodeToString<VaultFormatConfig>(config.vaultFormat))
            ps.setString(12, config.keyMode?.let { json.encodeToString<WorkspaceKeyMode>(it) })
            ps.setString(13, config.groupId?.value)
            ps.setString(14, config.retentionPolicy?.let { json.encodeToString<RetentionPolicy>(it) })
            ps.setString(15, config.prefetchSchedule?.let { json.encodeToString(it) })
            ps.setString(16, config.platformOrigin.name)
            ps.setLong(17, config.createdAtEpochSeconds)
            if (config.lastActiveAtEpochSeconds != null) {
                ps.setLong(18, config.lastActiveAtEpochSeconds)
            } else {
                ps.setNull(18, Types.BIGINT)
            }
            if (config.lastEvictedAtEpochSeconds != null) {
                ps.setLong(19, config.lastEvictedAtEpochSeconds)
            } else {
                ps.setNull(19, Types.BIGINT)
            }
            if (config.uncompressedSizeBytes != null) {
                ps.setLong(20, config.uncompressedSizeBytes)
            } else {
                ps.setNull(20, Types.BIGINT)
            }
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun findById(id: WorkspaceId): WorkspaceConfig? {
        connection.prepareStatement("SELECT * FROM workspace WHERE id = ?").use { ps ->
            ps.setString(1, id.value)
            ps.executeQuery().use { rs ->
                return if (rs.next()) mapRow(rs) else null
            }
        }
    }

    @Synchronized
    fun findAll(): List<WorkspaceConfig> {
        connection.prepareStatement("SELECT * FROM workspace ORDER BY display_name ASC").use { ps ->
            ps.executeQuery().use { rs ->
                val list = mutableListOf<WorkspaceConfig>()
                while (rs.next()) {
                    list.add(mapRow(rs))
                }
                return list
            }
        }
    }

    @Synchronized
    fun findByGroupId(groupId: GroupId): List<WorkspaceConfig> {
        connection.prepareStatement("SELECT * FROM workspace WHERE group_id = ? ORDER BY display_name ASC").use { ps ->
            ps.setString(1, groupId.value)
            ps.executeQuery().use { rs ->
                val list = mutableListOf<WorkspaceConfig>()
                while (rs.next()) {
                    list.add(mapRow(rs))
                }
                return list
            }
        }
    }

    /**
     * Returns workspaces ordered by least-recently-active first (for Auto-Unload arbitration).
     */
    @Synchronized
    fun findLeastRecentlyActive(): List<WorkspaceConfig> {
        val sql = "SELECT * FROM workspace ORDER BY COALESCE(last_active_at, 0) ASC"
        connection.prepareStatement(sql).use { ps ->
            ps.executeQuery().use { rs ->
                val list = mutableListOf<WorkspaceConfig>()
                while (rs.next()) {
                    list.add(mapRow(rs))
                }
                return list
            }
        }
    }

    @Synchronized
    fun updateLastActive(id: WorkspaceId, epochSeconds: Long, uncompressedBytes: Long? = null) {
        val sql = if (uncompressedBytes != null) {
            "UPDATE workspace SET last_active_at = ?, uncompressed_bytes = ? WHERE id = ?"
        } else {
            "UPDATE workspace SET last_active_at = ? WHERE id = ?"
        }
        connection.prepareStatement(sql).use { ps ->
            ps.setLong(1, epochSeconds)
            if (uncompressedBytes != null) {
                ps.setLong(2, uncompressedBytes)
                ps.setString(3, id.value)
            } else {
                ps.setString(2, id.value)
            }
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun updateLastEvicted(id: WorkspaceId, epochSeconds: Long, uncompressedBytes: Long? = null) {
        val sql = if (uncompressedBytes != null) {
            "UPDATE workspace SET last_evicted_at = ?, uncompressed_bytes = ? WHERE id = ?"
        } else {
            "UPDATE workspace SET last_evicted_at = ? WHERE id = ?"
        }
        connection.prepareStatement(sql).use { ps ->
            ps.setLong(1, epochSeconds)
            if (uncompressedBytes != null) {
                ps.setLong(2, uncompressedBytes)
                ps.setString(3, id.value)
            } else {
                ps.setString(2, id.value)
            }
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun delete(id: WorkspaceId) {
        connection.prepareStatement("DELETE FROM workspace WHERE id = ?").use { ps ->
            ps.setString(1, id.value)
            ps.executeUpdate()
        }
    }

    private fun mapRow(rs: ResultSet): WorkspaceConfig {
        val id = WorkspaceId(rs.getString("id"))
        val displayName = rs.getString("display_name")
        val description = rs.getString("description")
        val iconEmoji = rs.getString("icon_emoji")
        val iconImagePath = rs.getString("icon_image_path")
        val tags: List<String> = json.decodeFromString(rs.getString("tags_json"))
        val vaultBackend: VaultBackendConfig = json.decodeFromString(rs.getString("vault_backend_json"))
        val vaultFileName = rs.getString("vault_file_name")
        val localTargetPath = rs.getString("local_target_path")
        val overrides: Map<Platform, String> = json.decodeFromString(rs.getString("platform_path_overrides"))
        val vaultFormat: VaultFormatConfig = json.decodeFromString(rs.getString("vault_format_json"))
        val keyModeJson = rs.getString("key_mode_json")
        val keyMode: WorkspaceKeyMode? = keyModeJson?.let { json.decodeFromString(it) }
        val groupId = rs.getString("group_id")?.let { GroupId(it) }
        val retention: RetentionPolicy? = rs.getString("retention_json")?.let { json.decodeFromString(it) }
        val prefetch: PrefetchSchedule? = rs.getString("prefetch_json")?.let { json.decodeFromString(it) }
        val platformOrigin = Platform.valueOf(rs.getString("platform_origin"))
        val createdAt = rs.getLong("created_at")
        val lastActive = rs.getLong("last_active_at").takeIf { !rs.wasNull() }
        val lastEvicted = rs.getLong("last_evicted_at").takeIf { !rs.wasNull() }
        val uncompressedBytes = rs.getLong("uncompressed_bytes").takeIf { !rs.wasNull() }

        return WorkspaceConfig(
            id = id,
            displayName = displayName,
            description = description,
            iconEmoji = iconEmoji,
            iconImagePath = iconImagePath,
            tags = tags,
            vaultBackend = vaultBackend,
            vaultFileName = vaultFileName,
            localTargetPath = localTargetPath,
            platformPathOverrides = overrides,
            vaultFormat = vaultFormat,
            keyMode = keyMode,
            groupId = groupId,
            retentionPolicy = retention,
            prefetchSchedule = prefetch,
            platformOrigin = platformOrigin,
            createdAtEpochSeconds = createdAt,
            lastActiveAtEpochSeconds = lastActive,
            lastEvictedAtEpochSeconds = lastEvicted,
            uncompressedSizeBytes = uncompressedBytes,
        )
    }
}
