package dev.legendsayantan.backstage.core.workspace

import java.sql.Connection
import java.sql.ResultSet
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class GroupRepository(
    private val connection: Connection,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Synchronized
    fun upsert(group: WorkspaceGroup) {
        val sql = """
            INSERT INTO workspace_group (id, display_name, color, load_strategy, member_order, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(id) DO UPDATE SET
                display_name = excluded.display_name,
                color = excluded.color,
                load_strategy = excluded.load_strategy,
                member_order = excluded.member_order
        """.trimIndent()

        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, group.id.value)
            ps.setString(2, group.displayName)
            ps.setLong(3, group.colorArgb)
            ps.setString(4, group.loadStrategy.name)
            ps.setString(5, json.encodeToString(group.workspaceIds.map { it.value }))
            ps.setLong(6, group.createdAtEpochSeconds)
            ps.executeUpdate()
        }
    }

    @Synchronized
    fun findById(id: GroupId): WorkspaceGroup? {
        connection.prepareStatement("SELECT * FROM workspace_group WHERE id = ?").use { ps ->
            ps.setString(1, id.value)
            ps.executeQuery().use { rs ->
                return if (rs.next()) mapRow(rs) else null
            }
        }
    }

    @Synchronized
    fun findAll(): List<WorkspaceGroup> {
        connection.prepareStatement("SELECT * FROM workspace_group ORDER BY display_name ASC").use { ps ->
            ps.executeQuery().use { rs ->
                val list = mutableListOf<WorkspaceGroup>()
                while (rs.next()) {
                    list.add(mapRow(rs))
                }
                return list
            }
        }
    }

    @Synchronized
    fun delete(id: GroupId) {
        connection.prepareStatement("DELETE FROM workspace_group WHERE id = ?").use { ps ->
            ps.setString(1, id.value)
            ps.executeUpdate()
        }
    }

    private fun mapRow(rs: ResultSet): WorkspaceGroup {
        val idsRaw: List<String> = json.decodeFromString(rs.getString("member_order"))
        return WorkspaceGroup(
            id = GroupId(rs.getString("id")),
            displayName = rs.getString("display_name"),
            colorArgb = rs.getLong("color"),
            loadStrategy = LoadStrategy.valueOf(rs.getString("load_strategy")),
            workspaceIds = idsRaw.map { WorkspaceId(it) },
            createdAtEpochSeconds = rs.getLong("created_at"),
        )
    }
}
