package dev.legendsayantan.backstage.core.db

import dev.legendsayantan.backstage.core.platform.PlatformContext
import java.sql.Connection

/**
 * Provides an encrypted SQLite / SQLCipher JDBC connection for the BackStage state database.
 * The 32-byte database encryption key is stored in the OS keystore under KeyAliases.DB_KEY_ALIAS
 * and is only unlocked after Layer 1 DeviceCredentialSession passes.
 */
expect class DatabaseDriverFactory(context: PlatformContext) {
    fun openConnection(dbPath: String, keyHex: String): Connection
}
