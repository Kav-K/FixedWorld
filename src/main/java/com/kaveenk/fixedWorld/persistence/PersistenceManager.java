package com.kaveenk.fixedWorld.persistence;

import com.kaveenk.fixedWorld.FixedWorld;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitRunnable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Manages persistence of restoration data with async batch writes.
 * 
 * Write Strategy:
 * - Pending restorations are queued in memory
 * - Every BATCH_INTERVAL_TICKS, batch write to DB asynchronously
 * - On shutdown, flush all pending writes synchronously
 * 
 * Read Strategy:
 * - On startup, load all pending restorations from DB
 * - Restorations past their time are queued immediately
 * - Future restorations are scheduled normally
 */
public class PersistenceManager {

    private final FixedWorld plugin;
    private final DatabaseManager database;

    // Queue of pending database operations
    private final ConcurrentLinkedQueue<PendingWrite> writeQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> deleteQueue = new ConcurrentLinkedQueue<>();  // Location keys to delete

    // Batch write configuration
    private static final int BATCH_INTERVAL_TICKS = 100;  // 5 seconds
    private static final int MAX_BATCH_SIZE = 500;        // Max writes per batch

    // Background task
    private BukkitRunnable batchWriteTask;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    // Statistics
    private final AtomicInteger totalWrites = new AtomicInteger(0);
    private final AtomicInteger totalDeletes = new AtomicInteger(0);
    private final AtomicInteger totalLoaded = new AtomicInteger(0);

    public PersistenceManager(FixedWorld plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
    }

    /**
     * Starts the background batch write task.
     */
    public void start() {
        if (isRunning.getAndSet(true)) {
            return;  // Already running
        }

        batchWriteTask = new BukkitRunnable() {
            @Override
            public void run() {
                processBatchWrites();
            }
        };
        batchWriteTask.runTaskTimerAsynchronously(plugin, BATCH_INTERVAL_TICKS, BATCH_INTERVAL_TICKS);
        
        plugin.getLogger().info("[Persistence] Started batch write task (every " + (BATCH_INTERVAL_TICKS / 20) + "s)");
    }

    /**
     * Stops the background task and flushes pending writes.
     */
    public void shutdown() {
        isRunning.set(false);
        
        if (batchWriteTask != null) {
            batchWriteTask.cancel();
            batchWriteTask = null;
        }

        // Flush remaining writes synchronously
        int remaining = writeQueue.size() + deleteQueue.size();
        if (remaining > 0) {
            plugin.getLogger().info("[Persistence] Flushing " + remaining + " pending writes...");
            processBatchWrites();
        }

        plugin.getLogger().info("[Persistence] Shutdown complete. Total writes: " + totalWrites.get() + 
                               ", deletes: " + totalDeletes.get());
    }

    // ==================== WRITE OPERATIONS ====================

    /**
     * Queues a pending restoration for database persistence.
     */
    public void queueWrite(UUID worldUuid, int x, int y, int z, BlockData blockData, 
                          boolean hasTileEntity, byte[] tileEntityNbt, long restoreAtMs) {
        writeQueue.offer(new PendingWrite(
            worldUuid, x, y, z,
            blockData.getAsString(),
            hasTileEntity,
            tileEntityNbt,
            restoreAtMs,
            System.currentTimeMillis()
        ));
    }

    /**
     * Queues a restoration deletion (after block is restored).
     */
    public void queueDelete(UUID worldUuid, int x, int y, int z) {
        deleteQueue.offer(worldUuid.toString() + ":" + x + ":" + y + ":" + z);
    }

    /**
     * Processes batch writes asynchronously.
     */
    private void processBatchWrites() {
        if (!database.isConnected()) {
            return;
        }

        int writesProcessed = 0;
        int deletesProcessed = 0;

        try {
            database.beginTransaction();

            // Process deletes first (UPSERT will handle conflicts anyway)
            try (PreparedStatement deleteStmt = database.prepareStatement(
                    "DELETE FROM pending_restorations WHERE world_uuid = ? AND x = ? AND y = ? AND z = ?")) {
                
                String deleteKey;
                while ((deleteKey = deleteQueue.poll()) != null && deletesProcessed < MAX_BATCH_SIZE) {
                    String[] parts = deleteKey.split(":");
                    if (parts.length == 4) {
                        deleteStmt.setString(1, parts[0]);
                        deleteStmt.setInt(2, Integer.parseInt(parts[1]));
                        deleteStmt.setInt(3, Integer.parseInt(parts[2]));
                        deleteStmt.setInt(4, Integer.parseInt(parts[3]));
                        deleteStmt.addBatch();
                        deletesProcessed++;
                    }
                }
                
                if (deletesProcessed > 0) {
                    deleteStmt.executeBatch();
                }
            }

            // Process writes (UPSERT - insert or replace)
            try (PreparedStatement insertStmt = database.prepareStatement("""
                    INSERT OR REPLACE INTO pending_restorations 
                    (world_uuid, x, y, z, block_data, has_tile_entity, tile_entity_nbt, restore_at_ms, created_at_ms)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
                
                PendingWrite write;
                while ((write = writeQueue.poll()) != null && writesProcessed < MAX_BATCH_SIZE) {
                    insertStmt.setString(1, write.worldUuid.toString());
                    insertStmt.setInt(2, write.x);
                    insertStmt.setInt(3, write.y);
                    insertStmt.setInt(4, write.z);
                    insertStmt.setString(5, write.blockData);
                    insertStmt.setInt(6, write.hasTileEntity ? 1 : 0);
                    insertStmt.setBytes(7, write.tileEntityNbt);
                    insertStmt.setLong(8, write.restoreAtMs);
                    insertStmt.setLong(9, write.createdAtMs);
                    insertStmt.addBatch();
                    writesProcessed++;
                }
                
                if (writesProcessed > 0) {
                    insertStmt.executeBatch();
                }
            }

            database.commitTransaction();
            
            totalWrites.addAndGet(writesProcessed);
            totalDeletes.addAndGet(deletesProcessed);

        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[Persistence] Batch write failed", e);
            database.rollbackTransaction();
        }
    }

    // ==================== READ OPERATIONS ====================

    /**
     * Loads all pending restorations from the database.
     * Returns a list of restoration data to be processed.
     */
    public List<StoredRestoration> loadPendingRestorations() {
        List<StoredRestoration> restorations = new ArrayList<>();
        
        if (!database.isConnected()) {
            plugin.getLogger().warning("[Persistence] Cannot load restorations - database not connected");
            return restorations;
        }

        try (PreparedStatement stmt = database.prepareStatement(
                "SELECT world_uuid, x, y, z, block_data, has_tile_entity, tile_entity_nbt, restore_at_ms " +
                "FROM pending_restorations ORDER BY restore_at_ms ASC")) {
            
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                try {
                    StoredRestoration restoration = new StoredRestoration(
                        UUID.fromString(rs.getString("world_uuid")),
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getInt("z"),
                        rs.getString("block_data"),
                        rs.getInt("has_tile_entity") == 1,
                        rs.getBytes("tile_entity_nbt"),
                        rs.getLong("restore_at_ms")
                    );
                    restorations.add(restoration);
                } catch (Exception e) {
                    plugin.getLogger().warning("[Persistence] Failed to parse restoration: " + e.getMessage());
                }
            }
            
            totalLoaded.set(restorations.size());
            plugin.getLogger().info("[Persistence] Loaded " + restorations.size() + " pending restorations from database");

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[Persistence] Failed to load restorations", e);
        }

        return restorations;
    }

    /**
     * Clears all pending restorations for a world.
     */
    public void clearWorld(UUID worldUuid) {
        if (!database.isConnected()) return;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement stmt = database.prepareStatement(
                    "DELETE FROM pending_restorations WHERE world_uuid = ?")) {
                stmt.setString(1, worldUuid.toString());
                int deleted = stmt.executeUpdate();
                plugin.getLogger().info("[Persistence] Cleared " + deleted + " restorations for world " + worldUuid);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[Persistence] Failed to clear world", e);
            }
        });
    }

    // ==================== FIXED WORLD SETTINGS ====================

    /**
     * Saves fixed world settings to database.
     */
    public void saveFixedWorld(UUID worldUuid, String worldName, int delaySeconds, boolean absoluteMode) {
        if (!database.isConnected()) return;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement stmt = database.prepareStatement("""
                    INSERT OR REPLACE INTO fixed_worlds 
                    (world_uuid, world_name, restore_delay_seconds, absolute_mode, enabled_at_ms)
                    VALUES (?, ?, ?, ?, ?)
                """)) {
                stmt.setString(1, worldUuid.toString());
                stmt.setString(2, worldName);
                stmt.setInt(3, delaySeconds);
                stmt.setInt(4, absoluteMode ? 1 : 0);
                stmt.setLong(5, System.currentTimeMillis());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[Persistence] Failed to save fixed world", e);
            }
        });
    }

    /**
     * Removes fixed world settings from database.
     */
    public void removeFixedWorld(UUID worldUuid) {
        if (!database.isConnected()) return;

        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try (PreparedStatement stmt = database.prepareStatement(
                    "DELETE FROM fixed_worlds WHERE world_uuid = ?")) {
                stmt.setString(1, worldUuid.toString());
                stmt.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "[Persistence] Failed to remove fixed world", e);
            }
        });
    }

    /**
     * Loads all fixed world settings from database.
     */
    public List<FixedWorldSettings> loadFixedWorldSettings() {
        List<FixedWorldSettings> settings = new ArrayList<>();
        
        if (!database.isConnected()) return settings;

        try (PreparedStatement stmt = database.prepareStatement(
                "SELECT world_uuid, world_name, restore_delay_seconds, absolute_mode FROM fixed_worlds")) {
            
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                settings.add(new FixedWorldSettings(
                    UUID.fromString(rs.getString("world_uuid")),
                    rs.getString("world_name"),
                    rs.getInt("restore_delay_seconds"),
                    rs.getInt("absolute_mode") == 1
                ));
            }
            
            plugin.getLogger().info("[Persistence] Loaded " + settings.size() + " fixed world settings");

        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[Persistence] Failed to load fixed world settings", e);
        }

        return settings;
    }

    // ==================== STATISTICS ====================

    public int getWriteQueueSize() { return writeQueue.size(); }
    public int getDeleteQueueSize() { return deleteQueue.size(); }
    public int getTotalWrites() { return totalWrites.get(); }
    public int getTotalDeletes() { return totalDeletes.get(); }
    public int getTotalLoaded() { return totalLoaded.get(); }

    public String getStats() {
        return String.format("Queue: %d writes, %d deletes | Total: %d written, %d deleted, %d loaded",
            writeQueue.size(), deleteQueue.size(), totalWrites.get(), totalDeletes.get(), totalLoaded.get());
    }

    // ==================== DATA CLASSES ====================

    /**
     * Pending write operation.
     */
    private static class PendingWrite {
        final UUID worldUuid;
        final int x, y, z;
        final String blockData;
        final boolean hasTileEntity;
        final byte[] tileEntityNbt;
        final long restoreAtMs;
        final long createdAtMs;

        PendingWrite(UUID worldUuid, int x, int y, int z, String blockData,
                    boolean hasTileEntity, byte[] tileEntityNbt, long restoreAtMs, long createdAtMs) {
            this.worldUuid = worldUuid;
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockData = blockData;
            this.hasTileEntity = hasTileEntity;
            this.tileEntityNbt = tileEntityNbt;
            this.restoreAtMs = restoreAtMs;
            this.createdAtMs = createdAtMs;
        }
    }

    /**
     * Stored restoration loaded from database.
     */
    public static class StoredRestoration {
        public final UUID worldUuid;
        public final int x, y, z;
        public final String blockDataString;
        public final boolean hasTileEntity;
        public final byte[] tileEntityNbt;
        public final long restoreAtMs;

        public StoredRestoration(UUID worldUuid, int x, int y, int z, String blockDataString,
                                boolean hasTileEntity, byte[] tileEntityNbt, long restoreAtMs) {
            this.worldUuid = worldUuid;
            this.x = x;
            this.y = y;
            this.z = z;
            this.blockDataString = blockDataString;
            this.hasTileEntity = hasTileEntity;
            this.tileEntityNbt = tileEntityNbt;
            this.restoreAtMs = restoreAtMs;
        }
    }

    /**
     * Fixed world settings loaded from database.
     */
    public static class FixedWorldSettings {
        public final UUID worldUuid;
        public final String worldName;
        public final int delaySeconds;
        public final boolean absoluteMode;

        public FixedWorldSettings(UUID worldUuid, String worldName, int delaySeconds, boolean absoluteMode) {
            this.worldUuid = worldUuid;
            this.worldName = worldName;
            this.delaySeconds = delaySeconds;
            this.absoluteMode = absoluteMode;
        }
    }
}
