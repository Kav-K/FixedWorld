package com.kaveenk.fixedWorld.managers;

import com.kaveenk.fixedWorld.FixedWorld;
import com.kaveenk.fixedWorld.models.BlockSnapshot;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages fixed worlds and block restoration scheduling.
 * Thread-safe for async event handling.
 */
public class WorldSnapshotManager {

    private final FixedWorld plugin;

    // Maps world UUID to restore delay in ticks (20 ticks = 1 second)
    private final Map<UUID, Long> fixedWorlds = new ConcurrentHashMap<>();

    // Maps block location key to original snapshot (captured when block first changes)
    // Key format: "worldUUID:x:y:z"
    private final Map<String, BlockSnapshot> originalSnapshots = new ConcurrentHashMap<>();

    // Tracks pending restoration tasks to avoid duplicate scheduling
    private final Map<String, Integer> pendingRestorations = new ConcurrentHashMap<>();

    // Tracks recently restored blocks to prevent immediate re-capture (avoids loops)
    // Maps location key to the timestamp (ms) when the cooldown expires
    private final Map<String, Long> restorationCooldowns = new ConcurrentHashMap<>();

    // Cooldown duration in milliseconds after restoration (250ms)
    // This prevents restored blocks from immediately triggering new events
    private static final long RESTORATION_COOLDOWN_MS = 250L;

    public WorldSnapshotManager(FixedWorld plugin) {
        this.plugin = plugin;

        // Schedule periodic cleanup of expired cooldowns to prevent memory buildup
        // Runs every 5 minutes (6000 ticks)
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::cleanupExpiredCooldowns, 6000L, 6000L);
    }

    /**
     * Removes expired cooldowns from the map to prevent memory buildup.
     */
    private void cleanupExpiredCooldowns() {
        long currentTime = System.currentTimeMillis();
        restorationCooldowns.entrySet().removeIf(entry -> currentTime >= entry.getValue());
    }

    /**
     * Enable fixed world mode for a world with the specified restore delay.
     *
     * @param world   The world to fix
     * @param seconds Delay in seconds before blocks restore
     */
    public void enableFixedWorld(World world, int seconds) {
        long ticks = seconds * 20L;
        fixedWorlds.put(world.getUID(), ticks);
        plugin.getLogger().info("Enabled fixed world for '" + world.getName() + "' with " + seconds + "s restore delay");
    }

    /**
     * Disable fixed world mode for a world.
     *
     * @param world The world to unfix
     */
    public void disableFixedWorld(World world) {
        fixedWorlds.remove(world.getUID());
        // Clear all data for this world
        String worldPrefix = world.getUID().toString() + ":";
        originalSnapshots.keySet().removeIf(key -> key.startsWith(worldPrefix));
        pendingRestorations.keySet().removeIf(key -> key.startsWith(worldPrefix));
        restorationCooldowns.keySet().removeIf(key -> key.startsWith(worldPrefix));
        plugin.getLogger().info("Disabled fixed world for '" + world.getName() + "'");
    }

    /**
     * Check if a world has fixed world mode enabled.
     */
    public boolean isFixedWorld(World world) {
        return fixedWorlds.containsKey(world.getUID());
    }

    /**
     * Get the restore delay for a world in ticks.
     */
    public long getRestoreDelayTicks(World world) {
        return fixedWorlds.getOrDefault(world.getUID(), 0L);
    }

    /**
     * Get the restore delay for a world in seconds.
     */
    public int getRestoreDelaySeconds(World world) {
        return (int) (getRestoreDelayTicks(world) / 20L);
    }

    /**
     * Creates a location key for map storage.
     */
    private String getLocationKey(Location location) {
        return location.getWorld().getUID().toString() + ":" +
                location.getBlockX() + ":" +
                location.getBlockY() + ":" +
                location.getBlockZ();
    }

    /**
     * Check if a block location is in the restoration cooldown period.
     * This prevents loops where restored blocks immediately trigger new events.
     */
    private boolean isInCooldown(String locationKey) {
        Long cooldownExpiry = restorationCooldowns.get(locationKey);
        if (cooldownExpiry == null) {
            return false;
        }
        long currentTime = System.currentTimeMillis();
        if (currentTime >= cooldownExpiry) {
            // Cooldown expired, remove it
            restorationCooldowns.remove(locationKey);
            return false;
        }
        return true;
    }

    /**
     * Capture a block's state before it changes and schedule restoration.
     * If the block was already captured (pending restoration), this is a no-op
     * to preserve the original state.
     *
     * @param block The block about to change
     */
    public void captureAndScheduleRestore(Block block) {
        World world = block.getWorld();
        if (!isFixedWorld(world)) {
            return;
        }

        String locationKey = getLocationKey(block.getLocation());

        // Skip if this block was just restored (prevents loops)
        if (isInCooldown(locationKey)) {
            return;
        }

        // Only capture if we don't already have this block's original state
        // This ensures we restore to the state when fixed world was enabled,
        // not intermediate states from multiple changes
        if (!originalSnapshots.containsKey(locationKey)) {
            originalSnapshots.put(locationKey, new BlockSnapshot(block));
        }

        scheduleRestoration(locationKey, world);
    }

    /**
     * Capture a block state (for cases where we have the previous state, like block placement).
     * If the block was already captured (pending restoration), this is a no-op
     * to preserve the original state.
     *
     * @param blockState The block state to capture
     */
    public void captureAndScheduleRestore(org.bukkit.block.BlockState blockState) {
        World world = blockState.getWorld();
        if (!isFixedWorld(world)) {
            return;
        }

        String locationKey = getLocationKey(blockState.getLocation());

        // Skip if this block was just restored (prevents loops)
        if (isInCooldown(locationKey)) {
            return;
        }

        // Only capture if we don't already have this block's original state
        if (!originalSnapshots.containsKey(locationKey)) {
            originalSnapshots.put(locationKey, new BlockSnapshot(blockState));
        }

        scheduleRestoration(locationKey, world);
    }

    /**
     * Schedule a restoration task for a block location.
     */
    private void scheduleRestoration(String locationKey, World world) {
        // Cancel existing restoration task if any (block changed again before restore)
        Integer existingTaskId = pendingRestorations.get(locationKey);
        if (existingTaskId != null) {
            plugin.getServer().getScheduler().cancelTask(existingTaskId);
        }

        // Schedule new restoration
        long delayTicks = getRestoreDelayTicks(world);
        BukkitRunnable restoreTask = new BukkitRunnable() {
            @Override
            public void run() {
                restoreBlock(locationKey);
            }
        };

        int taskId = restoreTask.runTaskLater(plugin, delayTicks).getTaskId();
        pendingRestorations.put(locationKey, taskId);
    }

    /**
     * Restore a block to its original state and clean up tracking data.
     */
    private void restoreBlock(String locationKey) {
        BlockSnapshot snapshot = originalSnapshots.remove(locationKey);
        pendingRestorations.remove(locationKey);

        if (snapshot != null) {
            // Set cooldown BEFORE restoring to prevent any triggered events from re-capturing
            long currentTime = System.currentTimeMillis();
            restorationCooldowns.put(locationKey, currentTime + RESTORATION_COOLDOWN_MS);

            snapshot.restore();
        }
    }

    /**
     * Cancel all pending restorations and clear all data.
     * Called on plugin disable.
     */
    public void shutdown() {
        // Cancel all pending tasks
        for (Integer taskId : pendingRestorations.values()) {
            plugin.getServer().getScheduler().cancelTask(taskId);
        }
        pendingRestorations.clear();
        originalSnapshots.clear();
        fixedWorlds.clear();
        restorationCooldowns.clear();
    }

    /**
     * Get count of pending restorations for a world.
     */
    public int getPendingCount(World world) {
        String worldPrefix = world.getUID().toString() + ":";
        return (int) pendingRestorations.keySet().stream()
                .filter(key -> key.startsWith(worldPrefix))
                .count();
    }
}
