package com.kaveenk.fixedWorld;

import com.kaveenk.fixedWorld.commands.FixedWorldCommand;
import com.kaveenk.fixedWorld.listeners.*;
import com.kaveenk.fixedWorld.managers.WorldSnapshotManager;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class FixedWorld extends JavaPlugin {

    private WorldSnapshotManager snapshotManager;

    @Override
    public void onEnable() {
        // Initialize the snapshot manager
        snapshotManager = new WorldSnapshotManager(this);

        // Register command
        FixedWorldCommand command = new FixedWorldCommand(snapshotManager);
        getCommand("fixedworld").setExecutor(command);
        getCommand("fixedworld").setTabCompleter(command);

        // Register all listeners
        PluginManager pm = getServer().getPluginManager();
        pm.registerEvents(new BlockBreakListener(snapshotManager), this);
        pm.registerEvents(new BlockPlaceListener(snapshotManager), this);
        pm.registerEvents(new ExplosionListener(snapshotManager), this);
        pm.registerEvents(new NaturalEventListener(snapshotManager), this);
        pm.registerEvents(new EntityInteractionListener(snapshotManager), this);

        getLogger().info("FixedWorld enabled! Use /fixedworld fix <seconds> to enable.");
    }

    @Override
    public void onDisable() {
        // Clean up all pending restorations
        if (snapshotManager != null) {
            snapshotManager.shutdown();
        }
        getLogger().info("FixedWorld disabled.");
    }

    public WorldSnapshotManager getSnapshotManager() {
        return snapshotManager;
    }
}
