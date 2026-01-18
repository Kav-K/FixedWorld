package com.kaveenk.fixedWorld.persistence;

import com.kaveenk.fixedWorld.FixedWorld;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Level;

/**
 * Manages SQLite database connection and schema.
 * Uses a single connection for SQLite (connection pooling not needed for embedded DB).
 */
public class DatabaseManager {

    private final FixedWorld plugin;
    private Connection connection;
    private final String databasePath;

    // Schema version for future migrations
    private static final int SCHEMA_VERSION = 1;

    public DatabaseManager(FixedWorld plugin) {
        this.plugin = plugin;
        this.databasePath = plugin.getDataFolder().getAbsolutePath() + File.separator + "fixedworld.db";
    }

    /**
     * Initializes the database connection and creates schema if needed.
     */
    public boolean initialize() {
        try {
            // Ensure data folder exists
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }

            // Load SQLite driver
            Class.forName("org.sqlite.JDBC");

            // Connect to database
            connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            
            // Enable WAL mode for better concurrent read/write performance
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA journal_mode=WAL");
                stmt.execute("PRAGMA synchronous=NORMAL");  // Good balance of safety and speed
                stmt.execute("PRAGMA cache_size=10000");    // ~40MB cache
                stmt.execute("PRAGMA temp_store=MEMORY");
            }

            // Create schema
            createSchema();

            plugin.getLogger().info("[Database] SQLite database initialized at " + databasePath);
            return true;

        } catch (ClassNotFoundException e) {
            plugin.getLogger().severe("[Database] SQLite JDBC driver not found!");
            return false;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[Database] Failed to initialize database", e);
            return false;
        }
    }

    /**
     * Creates the database schema if it doesn't exist.
     */
    private void createSchema() throws SQLException {
        try (Statement stmt = connection.createStatement()) {
            
            // Schema version tracking
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS schema_version (
                    version INTEGER PRIMARY KEY
                )
            """);

            // Fixed world settings
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS fixed_worlds (
                    world_uuid TEXT PRIMARY KEY,
                    world_name TEXT NOT NULL,
                    restore_delay_seconds INTEGER NOT NULL,
                    absolute_mode INTEGER NOT NULL DEFAULT 0,
                    enabled_at_ms INTEGER NOT NULL
                )
            """);

            // Pending block restorations
            stmt.execute("""
                CREATE TABLE IF NOT EXISTS pending_restorations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    world_uuid TEXT NOT NULL,
                    x INTEGER NOT NULL,
                    y INTEGER NOT NULL,
                    z INTEGER NOT NULL,
                    block_data TEXT NOT NULL,
                    has_tile_entity INTEGER NOT NULL DEFAULT 0,
                    tile_entity_nbt BLOB,
                    restore_at_ms INTEGER NOT NULL,
                    created_at_ms INTEGER NOT NULL
                )
            """);

            // Create indexes for fast queries
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_pending_restore_time 
                ON pending_restorations(restore_at_ms)
            """);
            
            stmt.execute("""
                CREATE INDEX IF NOT EXISTS idx_pending_world 
                ON pending_restorations(world_uuid)
            """);
            
            stmt.execute("""
                CREATE UNIQUE INDEX IF NOT EXISTS idx_pending_location 
                ON pending_restorations(world_uuid, x, y, z)
            """);

            // Insert schema version if not exists
            stmt.execute("INSERT OR IGNORE INTO schema_version (version) VALUES (" + SCHEMA_VERSION + ")");
        }

        plugin.getLogger().info("[Database] Schema created/verified (version " + SCHEMA_VERSION + ")");
    }

    /**
     * Gets the database connection.
     * For SQLite, we use a single connection (thread-safe with WAL mode).
     */
    public Connection getConnection() {
        return connection;
    }

    /**
     * Checks if the database is connected and valid.
     */
    public boolean isConnected() {
        try {
            return connection != null && !connection.isClosed() && connection.isValid(1);
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * Reconnects to the database if disconnected.
     */
    public boolean reconnectIfNeeded() {
        if (isConnected()) {
            return true;
        }
        
        plugin.getLogger().warning("[Database] Connection lost, attempting to reconnect...");
        return initialize();
    }

    /**
     * Executes a query with automatic reconnection.
     */
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        reconnectIfNeeded();
        return connection.prepareStatement(sql);
    }

    /**
     * Begins a transaction for batch operations.
     */
    public void beginTransaction() throws SQLException {
        connection.setAutoCommit(false);
    }

    /**
     * Commits the current transaction.
     */
    public void commitTransaction() throws SQLException {
        connection.commit();
        connection.setAutoCommit(true);
    }

    /**
     * Rolls back the current transaction.
     */
    public void rollbackTransaction() {
        try {
            connection.rollback();
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[Database] Failed to rollback transaction", e);
        }
    }

    /**
     * Shuts down the database connection.
     */
    public void shutdown() {
        if (connection != null) {
            try {
                // Checkpoint WAL before closing
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("PRAGMA wal_checkpoint(TRUNCATE)");
                }
                connection.close();
                plugin.getLogger().info("[Database] Database connection closed");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[Database] Error closing database", e);
            }
        }
    }

    /**
     * Gets database statistics for debugging.
     */
    public String getStats() {
        if (!isConnected()) {
            return "Not connected";
        }
        
        try (Statement stmt = connection.createStatement()) {
            var rs = stmt.executeQuery("SELECT COUNT(*) FROM pending_restorations");
            int pendingCount = rs.next() ? rs.getInt(1) : 0;
            
            rs = stmt.executeQuery("SELECT COUNT(*) FROM fixed_worlds");
            int worldCount = rs.next() ? rs.getInt(1) : 0;
            
            return String.format("Connected, %d pending restorations, %d fixed worlds", pendingCount, worldCount);
        } catch (SQLException e) {
            return "Error: " + e.getMessage();
        }
    }
}
