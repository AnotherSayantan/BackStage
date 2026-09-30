package dev.legendsayantan.backstage.core.db

import dev.legendsayantan.backstage.core.auth.DeviceCredentialSession
import dev.legendsayantan.backstage.core.crypto.KeyAliases
import dev.legendsayantan.backstage.core.crypto.KeyProvider
import dev.legendsayantan.backstage.core.crypto.toHexString
import dev.legendsayantan.backstage.core.platform.PlatformContext
import dev.legendsayantan.backstage.core.platform.SecureRandomProvider
import java.io.File
import java.sql.Connection
import java.sql.Statement

object DatabaseSchemaInitializer {
    fun initializeSchema(stmt: Statement) {
        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS workspace_group (
                id            TEXT    NOT NULL PRIMARY KEY,
                display_name  TEXT    NOT NULL,
                color         INTEGER NOT NULL DEFAULT -10543360,
                load_strategy TEXT    NOT NULL DEFAULT 'SPACE_AWARE',
                member_order  TEXT    NOT NULL DEFAULT '[]',
                created_at    INTEGER NOT NULL
            );
            """.trimIndent()
        )

        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS workspace (
                id                       TEXT    NOT NULL PRIMARY KEY,
                display_name             TEXT    NOT NULL,
                description              TEXT    NOT NULL DEFAULT '',
                icon_emoji               TEXT,
                icon_image_path          TEXT,
                tags_json                TEXT    NOT NULL DEFAULT '[]',
                vault_backend_json       TEXT    NOT NULL,
                vault_file_name          TEXT    NOT NULL,
                local_target_path        TEXT    NOT NULL,
                platform_path_overrides  TEXT    NOT NULL DEFAULT '{}',
                vault_format_json        TEXT    NOT NULL,
                key_mode_json            TEXT,
                group_id                 TEXT    REFERENCES workspace_group(id) ON DELETE SET NULL,
                retention_json           TEXT,
                prefetch_json            TEXT,
                platform_origin          TEXT    NOT NULL,
                created_at               INTEGER NOT NULL,
                last_active_at           INTEGER,
                last_evicted_at          INTEGER,
                uncompressed_bytes       INTEGER
            );
            """.trimIndent()
        )

        stmt.execute("CREATE INDEX IF NOT EXISTS idx_workspace_group ON workspace(group_id);")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_workspace_lru ON workspace(last_active_at);")

        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS backend_credential (
                alias           TEXT    NOT NULL PRIMARY KEY,
                backend_type    TEXT    NOT NULL,
                encrypted_blob  TEXT    NOT NULL,
                created_at      INTEGER NOT NULL,
                updated_at      INTEGER NOT NULL
            );
            """.trimIndent()
        )

        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS audit_log (
                id            INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                workspace_id  TEXT    REFERENCES workspace(id) ON DELETE SET NULL,
                event_type    TEXT    NOT NULL,
                detail_json   TEXT,
                occurred_at   INTEGER NOT NULL
            );
            """.trimIndent()
        )

        stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_ws ON audit_log(workspace_id);")
        stmt.execute("CREATE INDEX IF NOT EXISTS idx_audit_time ON audit_log(occurred_at);")

        stmt.execute(
            """
            CREATE TABLE IF NOT EXISTS app_config (
                key   TEXT NOT NULL PRIMARY KEY,
                value TEXT NOT NULL
            );
            """.trimIndent()
        )

        // Populate defaults if absent
        stmt.execute("INSERT OR IGNORE INTO app_config(key, value) VALUES ('auto_unload_enabled', 'true');")
        stmt.execute("INSERT OR IGNORE INTO app_config(key, value) VALUES ('default_vault_format', 'Native');")
        stmt.execute("INSERT OR IGNORE INTO app_config(key, value) VALUES ('prefetch_on_idle_only', 'true');")
    }

    @Volatile
    var lastDatabaseRecoveryWarning: String? = null
        private set

    /**
     * Retrieves or generates the 32-byte SQLCipher database key from the OS keystore
     * and opens the database connection. Requires an active [DeviceCredentialSession].
     * If the SQLite file is corrupted, backs it up to `.corrupt.<timestamp>` and initializes a fresh database.
     */
    fun bootstrapDatabase(
        context: PlatformContext,
        keyProvider: KeyProvider,
        dbFile: File,
    ): Connection {
        val session = DeviceCredentialSession.requireActive()
        val keyHex = "x'${session.dbKey.toHexString()}'"
        val factory = DatabaseDriverFactory(context)
        return try {
            val conn = factory.openConnection(dbFile.absolutePath, keyHex)
            // Verify basic integrity
            conn.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA quick_check;").use { rs ->
                    if (rs.next() && !rs.getString(1).equals("ok", ignoreCase = true)) {
                        throw java.sql.SQLException("SQLite quick_check failed: ${rs.getString(1)}")
                    }
                }
            }
            conn
        } catch (e: Exception) {
            val backupFile = File(dbFile.parentFile, "${dbFile.name}.corrupt.${System.currentTimeMillis()}")
            if (dbFile.exists()) {
                runCatching { dbFile.copyTo(backupFile, overwrite = true) }
                runCatching { dbFile.delete() }
                File("${dbFile.absolutePath}-wal").delete()
                File("${dbFile.absolutePath}-shm").delete()
            }
            lastDatabaseRecoveryWarning =
                "State database was corrupted (${e.message}) and has been backed up to ${backupFile.name} and re-initialized."
            factory.openConnection(dbFile.absolutePath, keyHex)
        }
    }

    /**
     * Loads or creates the 32-byte database encryption key from the OS keystore.
     */
    fun getOrCreateDatabaseKey(keyProvider: KeyProvider): ByteArray {
        val existing = keyProvider.retrieveOrNull(KeyAliases.DB_KEY_ALIAS)
        if (existing != null && existing.size == 32) {
            return existing
        }
        val created = SecureRandomProvider.nextBytes(32)
        keyProvider.store(KeyAliases.DB_KEY_ALIAS, created)
        return created
    }
}
