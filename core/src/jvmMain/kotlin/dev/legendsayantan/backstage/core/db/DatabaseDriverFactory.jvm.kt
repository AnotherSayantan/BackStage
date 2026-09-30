package dev.legendsayantan.backstage.core.db

import dev.legendsayantan.backstage.core.platform.PlatformContext
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

actual class DatabaseDriverFactory actual constructor(
    private val context: PlatformContext,
) {
    actual fun openConnection(dbPath: String, keyHex: String): Connection {
        Class.forName("org.sqlite.JDBC")
        File(dbPath).parentFile?.mkdirs()
        val conn = DriverManager.getConnection("jdbc:sqlite:$dbPath")
        conn.createStatement().use { stmt ->
            // Apply SQLCipher PRAGMA key when SQLCipher extension is active; no-op on standard sqlite-jdbc
            runCatching { stmt.execute("PRAGMA key = \"$keyHex\";") }
            stmt.execute("PRAGMA foreign_keys = ON;")
            stmt.execute("PRAGMA journal_mode = WAL;")
            DatabaseSchemaInitializer.initializeSchema(stmt)
        }
        return conn
    }
}
