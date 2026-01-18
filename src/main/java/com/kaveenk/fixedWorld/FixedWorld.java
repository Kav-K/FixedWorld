package com.kaveenk.fixedWorld;

import com.kaveenk.fixedWorld.commands.FixedWorldCommand;
import com.kaveenk.fixedWorld.listeners.entity.EntityInteractionListener;
import com.kaveenk.fixedWorld.listeners.player.BlockBreakListener;
import com.kaveenk.fixedWorld.listeners.player.BlockPlaceListener;
import com.kaveenk.fixedWorld.listeners.player.PlayerInteractionListener;
import com.kaveenk.fixedWorld.listeners.world.ExplosionListener;
import com.kaveenk.fixedWorld.listeners.world.NaturalEventListener;
import com.kaveenk.fixedWorld.managers.ChunkScanner;
import com.kaveenk.fixedWorld.managers.WorldSnapshotManager;
import com.kaveenk.fixedWorld.persistence.DatabaseManager;
import com.kaveenk.fixedWorld.persistence.PersistenceManager;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class FixedWorld extends JavaPlugin {

    private DatabaseManager databaseManager;
    private PersistenceManager persistenceManager;
    private WorldSnapshotManager snapshotManager;

    @Override
    public void onEnable() {
        // Initialize database
        databaseManager = new DatabaseManager(this);
        if (!databaseManager.initialize()) {
            getLogger().severe("Failed to initialize database! Plugin will continue without persistence.");
        }

        // Initialize persistence manager
        persistenceManager = new PersistenceManager(this, databaseManager);
        persistenceManager.start();

        // Initialize the snapshot manager
        snapshotManager = new WorldSnapshotManager(this, persistenceManager);

        // Initialize chunk scanner for ABSOLUTE mode (catches /fill, /setblock, plugin changes, etc.)
        ChunkScanner chunkScanner = new ChunkScanner(this, snapshotManager);
        snapshotManager.setChunkScanner(chunkScanner);

        // Load saved fixed world settings and pending restorations
        snapshotManager.loadFromPersistence();

        // Register command
        FixedWorldCommand command = new FixedWorldCommand(snapshotManager);
        getCommand("fixedworld").setExecutor(command);
        getCommand("fixedworld").setTabCompleter(command);

        // Register all listeners
        PluginManager pm = getServer().getPluginManager();
        
        // Player listeners
        pm.registerEvents(new BlockBreakListener(snapshotManager), this);
        pm.registerEvents(new BlockPlaceListener(snapshotManager), this);
        pm.registerEvents(new PlayerInteractionListener(snapshotManager), this);
        
        // World listeners
        pm.registerEvents(new ExplosionListener(snapshotManager), this);
        pm.registerEvents(new NaturalEventListener(snapshotManager), this);
        
        // Entity listeners
        pm.registerEvents(new EntityInteractionListener(snapshotManager), this);
        
        // Chunk scanner (for new chunk loads)
        pm.registerEvents(chunkScanner, this);

        getLogger().info("FixedWorld enabled! Use /fixedworld fix <seconds> to enable.");
    }

    @Override
    public void onDisable() {
        // Shutdown persistence manager (flushes pending writes)
        if (persistenceManager != null) {
            persistenceManager.shutdown();
        }

        // Clean up snapshot manager
        if (snapshotManager != null) {
            snapshotManager.shutdown();
        }

        // Close database connection
        if (databaseManager != null) {
            databaseManager.shutdown();
        }

        getLogger().info("FixedWorld disabled.");
    }

    public WorldSnapshotManager getSnapshotManager() {
        return snapshotManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }

    public PersistenceManager getPersistenceManager() {
        return persistenceManager;
    }
}
