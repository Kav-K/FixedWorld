package com.kaveenk.fixedWorld.managers;

import com.kaveenk.fixedWorld.FixedWorld;
import com.kaveenk.fixedWorld.models.BlockSnapshot;
import com.kaveenk.fixedWorld.persistence.PersistenceManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;

import java.util.List;
import java.util.Map;
import java.util.Set;
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

    // Batched restoration queue - processes blocks gradually to avoid lag spikes
    private final RestorationQueue restorationQueue;

    // Tracks recently restored blocks to prevent immediate re-capture (avoids loops)
    // Maps location key to the timestamp (ms) when the cooldown expires
    private final Map<String, Long> restorationCooldowns = new ConcurrentHashMap<>();

    // Fire suppression zones using spatial hashing for O(1) lookups
    // Key format: "worldUUID:zoneX:zoneY:zoneZ" where zone coords = blockCoord / ZONE_SIZE
    // Value: timestamp when suppression expires
    private final Map<String, Long> fireSuppressionZones = new ConcurrentHashMap<>();

    // Tracks blocks captured via ChunkScanner baseline (without tile entity data)
    // These can be overridden by event-based captures which have full data
    private final Set<String> baselineOnlyCaptures = ConcurrentHashMap.newKeySet();

    // Cooldown duration in milliseconds after restoration (250ms)
    // This prevents restored blocks from immediately triggering new events
    private static final long RESTORATION_COOLDOWN_MS = 250L;

    // Fire suppression zone size (32 blocks) - defines the virtual radius
    private static final int FIRE_ZONE_SIZE = 32;

    // Fire suppression duration (5 minutes)
    private static final long FIRE_SUPPRESSION_MS = 5 * 60 * 1000L;

    // Reference to chunk scanner for ABSOLUTE mode
    private ChunkScanner chunkScanner;

    // Whether absolute mode is enabled (ChunkScanner active)
    private boolean absoluteModeEnabled = false;

    // Persistence manager (may be null if persistence failed to initialize)
    private final PersistenceManager persistenceManager;

    public WorldSnapshotManager(FixedWorld plugin, PersistenceManager persistenceManager) {
        this.plugin = plugin;
        this.persistenceManager = persistenceManager;

        // Initialize the batched restoration queue
        this.restorationQueue = new RestorationQueue(plugin);
        this.restorationQueue.setOnRestorationComplete(this::onBlockRestored);
        this.restorationQueue.setPersistenceManager(persistenceManager);

        // Schedule periodic cleanup of expired cooldowns to prevent memory buildup
        // Runs every 5 minutes (6000 ticks)
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::cleanupExpiredCooldowns, 6000L, 6000L);
    }

    /**
     * Configures how many blocks to restore per tick.
     * Higher values = faster restoration but more lag potential.
     * Default is 50 blocks per tick.
     */
    public void setBlocksPerTick(int blocks) {
        restorationQueue.setBlocksPerTick(blocks);
    }

    /**
     * Sets the chunk scanner for ABSOLUTE mode.
     */
    public void setChunkScanner(ChunkScanner scanner) {
        this.chunkScanner = scanner;
    }

    /**
     * Gets the chunk scanner.
     */
    public ChunkScanner getChunkScanner() {
        return chunkScanner;
    }

    /**
     * Checks if absolute mode is enabled.
     */
    public boolean isAbsoluteModeEnabled() {
        return absoluteModeEnabled;
    }

    /**
     * Enables or disables absolute mode (ChunkScanner).
     * When enabled, the ChunkScanner will periodically scan for block changes
     * that bypass events (e.g., /fill, /setblock, plugin modifications).
     */
    public void setAbsoluteModeEnabled(boolean enabled) {
        this.absoluteModeEnabled = enabled;
        
        if (chunkScanner != null) {
            if (enabled) {
                // Capture baselines for all currently fixed worlds
                for (UUID worldId : fixedWorlds.keySet()) {
                    World world = plugin.getServer().getWorld(worldId);
                    if (world != null) {
                        chunkScanner.onFixedWorldEnabled(world);
                    }
                }
                plugin.getLogger().info("Absolute mode ENABLED - ChunkScanner active");
            } else {
                // Clear all baselines
                for (UUID worldId : fixedWorlds.keySet()) {
                    World world = plugin.getServer().getWorld(worldId);
                    if (world != null) {
                        chunkScanner.onFixedWorldDisabled(world);
                    }
                }
                plugin.getLogger().info("Absolute mode DISABLED - ChunkScanner inactive");
            }
        }

        // Update persistence for all fixed worlds with the new absolute mode setting
        if (persistenceManager != null) {
            for (Map.Entry<UUID, Long> entry : fixedWorlds.entrySet()) {
                World world = plugin.getServer().getWorld(entry.getKey());
                if (world != null) {
                    int seconds = (int) (entry.getValue() / 20L);
                    persistenceManager.saveFixedWorld(world.getUID(), world.getName(), seconds, absoluteModeEnabled);
                }
            }
        }
    }

    /**
     * Loads saved fixed world settings and pending restorations from the database.
     * Called during plugin startup to restore state after a server restart.
     */
    public void loadFromPersistence() {
        if (persistenceManager == null) {
            plugin.getLogger().info("Persistence disabled - skipping state restoration");
            return;
        }

        // Load fixed world settings
        List<PersistenceManager.FixedWorldSettings> settings = persistenceManager.loadFixedWorldSettings();
        for (PersistenceManager.FixedWorldSettings setting : settings) {
            World world = Bukkit.getWorld(setting.worldUuid);
            if (world != null) {
                long ticks = setting.delaySeconds * 20L;
                fixedWorlds.put(setting.worldUuid, ticks);
                
                if (setting.absoluteMode) {
                    absoluteModeEnabled = true;
                }
                
                plugin.getLogger().info("Restored fixed world: " + setting.worldName + 
                    " (" + setting.delaySeconds + "s delay" + 
                    (setting.absoluteMode ? ", absolute mode" : "") + ")");
            } else {
                plugin.getLogger().warning("Could not find world " + setting.worldName + 
                    " (UUID: " + setting.worldUuid + ") - skipping");
            }
        }

        // Load pending restorations
        List<PersistenceManager.StoredRestoration> restorations = persistenceManager.loadPendingRestorations();
        long currentTime = System.currentTimeMillis();
        int restored = 0;
        int queued = 0;

        for (PersistenceManager.StoredRestoration sr : restorations) {
            World world = Bukkit.getWorld(sr.worldUuid);
            if (world == null) continue;

            try {
                // Parse the stored block data
                BlockData blockData = Bukkit.createBlockData(sr.blockDataString);
                Location location = new Location(world, sr.x, sr.y, sr.z);
                
                // Create a snapshot from the stored data, including tile entity NBT if present
                BlockSnapshot snapshot = new BlockSnapshot(location, blockData, sr.tileEntityNbt);
                String locationKey = sr.worldUuid.toString() + ":" + sr.x + ":" + sr.y + ":" + sr.z;

                // Store in memory
                originalSnapshots.put(locationKey, snapshot);
                
                // Only mark as baselineOnly if we don't have tile entity data
                // Snapshots with full tile entity data should not be overridden
                if (!sr.hasTileEntity || sr.tileEntityNbt == null) {
                    baselineOnlyCaptures.add(locationKey);
                }

                // Check if restoration time has passed
                if (sr.restoreAtMs <= currentTime) {
                    // Restore immediately (but through the queue for batching)
                    restorationQueue.queueRestoration(locationKey, snapshot, currentTime);
                    restored++;
                } else {
                    // Schedule for later
                    restorationQueue.queueRestoration(locationKey, snapshot, sr.restoreAtMs);
                    queued++;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to restore block data: " + e.getMessage());
            }
        }

        if (restored > 0 || queued > 0) {
            plugin.getLogger().info("Loaded " + (restored + queued) + " pending restorations (" + 
                restored + " due now, " + queued + " scheduled)");
        }

        // Start chunk scanner if absolute mode was enabled
        if (absoluteModeEnabled && chunkScanner != null) {
            for (UUID worldId : fixedWorlds.keySet()) {
                World world = Bukkit.getWorld(worldId);
                if (world != null) {
                    chunkScanner.onFixedWorldEnabled(world);
                }
            }
            plugin.getLogger().info("Absolute mode restored - ChunkScanner active");
        }
    }

    /**
     * Removes expired cooldowns and fire suppression zones to prevent memory buildup.
     */
    private void cleanupExpiredCooldowns() {
        long currentTime = System.currentTimeMillis();
        restorationCooldowns.entrySet().removeIf(entry -> currentTime >= entry.getValue());
        fireSuppressionZones.entrySet().removeIf(entry -> currentTime >= entry.getValue());
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

        // Persist to database
        if (persistenceManager != null) {
            persistenceManager.saveFixedWorld(world.getUID(), world.getName(), seconds, absoluteModeEnabled);
        }

        // Notify chunk scanner to capture baselines (only if absolute mode is enabled)
        if (absoluteModeEnabled && chunkScanner != null) {
            chunkScanner.onFixedWorldEnabled(world);
        }
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
        restorationQueue.clearWorld(worldPrefix);
        restorationCooldowns.keySet().removeIf(key -> key.startsWith(worldPrefix));
        fireSuppressionZones.keySet().removeIf(key -> key.startsWith(worldPrefix));
        baselineOnlyCaptures.removeIf(key -> key.startsWith(worldPrefix));
        plugin.getLogger().info("Disabled fixed world for '" + world.getName() + "'");

        // Remove from persistence
        if (persistenceManager != null) {
            persistenceManager.removeFixedWorld(world.getUID());
            persistenceManager.clearWorld(world.getUID());
        }

        // Notify chunk scanner to clear baselines
        if (chunkScanner != null) {
            chunkScanner.onFixedWorldDisabled(world);
        }
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
     * Event-based captures have PRIORITY over ChunkScanner baseline captures
     * because they preserve tile entity data (signs, chests, etc.).
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

        // Check if we should capture
        // - If no snapshot exists, capture
        // - If only a baseline capture exists, OVERRIDE with full capture (preserves tile entity data)
        boolean shouldCapture = !originalSnapshots.containsKey(locationKey) 
                                || baselineOnlyCaptures.contains(locationKey);

        // Capture the block data BEFORE any world changes
        BlockData capturedData = block.getBlockData();

        if (shouldCapture) {
            originalSnapshots.put(locationKey, new BlockSnapshot(block));
            baselineOnlyCaptures.remove(locationKey);  // This is now a full capture
        }

        scheduleRestoration(locationKey, world);

        // Also capture linked blocks (other half of doors, beds, tall plants)
        // Pass the captured data since the block might be destroyed after this
        captureLinkedBlocks(block, capturedData);
    }

    /**
     * Capture a block state (for cases where we have the previous state, like block placement).
     * Event-based captures have PRIORITY over ChunkScanner baseline captures
     * because they preserve tile entity data (signs, chests, etc.).
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

        // Check if we should capture
        // - If no snapshot exists, capture
        // - If only a baseline capture exists, OVERRIDE with full capture (preserves tile entity data)
        boolean shouldCapture = !originalSnapshots.containsKey(locationKey) 
                                || baselineOnlyCaptures.contains(locationKey);

        // Get the block data from the state BEFORE any world changes
        BlockData capturedData = blockState.getBlockData();

        if (shouldCapture) {
            originalSnapshots.put(locationKey, new BlockSnapshot(blockState));
            baselineOnlyCaptures.remove(locationKey);  // This is now a full capture
        }

        scheduleRestoration(locationKey, world);

        // Also capture linked blocks (other half of doors, beds, tall plants)
        // Pass the captured data since the block might be destroyed after this
        captureLinkedBlocks(blockState.getBlock(), capturedData);
    }

    /**
     * Captures linked blocks for multi-block structures (doors, beds, tall plants).
     * This ensures both halves are captured and will restore together.
     *
     * @param block The primary block location
     * @param capturedData The block data that was captured (use this instead of current world state)
     */
    private void captureLinkedBlocks(Block block, BlockData capturedData) {
        // Handle bisected blocks (doors, tall flowers, tall grass, etc.)
        if (capturedData instanceof Bisected bisected) {
            Block otherHalf;
            if (bisected.getHalf() == Bisected.Half.TOP) {
                otherHalf = block.getRelative(BlockFace.DOWN);
            } else {
                otherHalf = block.getRelative(BlockFace.UP);
            }

            // Capture the other half - it should still exist at this point
            // Check if it's a bisected block of the same material
            BlockData otherData = otherHalf.getBlockData();
            if (otherData instanceof Bisected && otherData.getMaterial() == capturedData.getMaterial()) {
                captureLinkedBlockInternal(otherHalf);
            }
        }

        // Handle beds (head and foot parts)
        if (capturedData instanceof Bed bed) {
            BlockFace facing = bed.getFacing();
            Block otherPart;
            if (bed.getPart() == Bed.Part.HEAD) {
                // Head faces away from foot, so foot is behind
                otherPart = block.getRelative(facing.getOppositeFace());
            } else {
                // Foot faces toward head
                otherPart = block.getRelative(facing);
            }

            // Capture the other part if it's a bed of the same type
            BlockData otherData = otherPart.getBlockData();
            if (otherData instanceof Bed && otherData.getMaterial() == capturedData.getMaterial()) {
                captureLinkedBlockInternal(otherPart);
            }
        }
    }

    /**
     * Internal method to capture a linked block without triggering recursive linked block capture.
     */
    private void captureLinkedBlockInternal(Block block) {
        World world = block.getWorld();
        String locationKey = getLocationKey(block.getLocation());

        // Skip if in cooldown or already captured with full data
        if (isInCooldown(locationKey)) {
            return;
        }

        boolean shouldCapture = !originalSnapshots.containsKey(locationKey) 
                                || baselineOnlyCaptures.contains(locationKey);

        if (shouldCapture) {
            originalSnapshots.put(locationKey, new BlockSnapshot(block));
            baselineOnlyCaptures.remove(locationKey);
        }

        scheduleRestoration(locationKey, world);
    }

    /**
     * Capture from baseline BlockData (used by ChunkScanner when it detects changes).
     * This is LOW PRIORITY - it will NOT override event-based captures which have full tile entity data.
     * Only captures if no snapshot exists yet.
     *
     * @param location The block location
     * @param baselineData The original block data from the chunk snapshot
     */
    public void captureFromBaselineAndScheduleRestore(Location location, BlockData baselineData) {
        World world = location.getWorld();
        if (!isFixedWorld(world)) {
            return;
        }

        String locationKey = getLocationKey(location);

        // Skip if in cooldown
        if (isInCooldown(locationKey)) {
            return;
        }

        // Only capture if we don't already have this block's original state
        // NEVER override existing captures (they may have tile entity data we don't have)
        if (!originalSnapshots.containsKey(locationKey)) {
            originalSnapshots.put(locationKey, new BlockSnapshot(location, baselineData));
            baselineOnlyCaptures.add(locationKey);  // Mark as baseline-only (can be overridden)
        }

        scheduleRestoration(locationKey, world);
    }

    /**
     * Schedule a restoration for a block location.
     * Uses the batched queue to avoid lag spikes when many blocks need restoration.
     */
    private void scheduleRestoration(String locationKey, World world) {
        BlockSnapshot snapshot = originalSnapshots.get(locationKey);
        if (snapshot == null) {
            return;
        }

        // Calculate when to restore (current time + delay)
        long delayMs = getRestoreDelayTicks(world) * 50L;  // Convert ticks to ms
        long restoreTime = System.currentTimeMillis() + delayMs;

        // Queue the restoration
        restorationQueue.queueRestoration(locationKey, snapshot, restoreTime);
    }

    /**
     * Called by RestorationQueue when a block has been restored.
     * Handles post-restoration cleanup.
     */
    private void onBlockRestored(String locationKey) {
        // Remove from snapshots (the queue already restored it)
        originalSnapshots.remove(locationKey);
        baselineOnlyCaptures.remove(locationKey);

        // Set cooldown to prevent immediate re-capture
        long currentTime = System.currentTimeMillis();
        restorationCooldowns.put(locationKey, currentTime + RESTORATION_COOLDOWN_MS);

        // Get the snapshot from queue to perform post-restoration tasks
        // Note: The snapshot was already used for restoration, we just need the location
        // We can parse it from the locationKey
        Location location = parseLocationKey(locationKey);
        if (location != null) {
            Block block = location.getBlock();
            
            // Clear fire adjacent to the restored block
            clearAdjacentFire(block);

            // Mark zone as fire-suppressed
            markFireSuppressionZone(location);
        }

        // Clear the detected change from chunk scanner
        if (chunkScanner != null) {
            chunkScanner.clearDetectedChange(locationKey);
        }
    }

    /**
     * Parses a location key back into a Location.
     * Key format: "worldUUID:x:y:z"
     */
    private Location parseLocationKey(String locationKey) {
        try {
            String[] parts = locationKey.split(":");
            if (parts.length != 4) return null;

            UUID worldId = UUID.fromString(parts[0]);
            World world = plugin.getServer().getWorld(worldId);
            if (world == null) return null;

            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int z = Integer.parseInt(parts[3]);

            return new Location(world, x, y, z);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Creates a zone key for fire suppression spatial hashing.
     * Zones are 32x32x32 block cubes.
     */
    private String getZoneKey(World world, int x, int y, int z) {
        int zoneX = Math.floorDiv(x, FIRE_ZONE_SIZE);
        int zoneY = Math.floorDiv(y, FIRE_ZONE_SIZE);
        int zoneZ = Math.floorDiv(z, FIRE_ZONE_SIZE);
        return world.getUID().toString() + ":" + zoneX + ":" + zoneY + ":" + zoneZ;
    }

    /**
     * Marks a fire suppression zone around the given location.
     * The zone will suppress fire spread for 5 minutes.
     */
    private void markFireSuppressionZone(Location loc) {
        String zoneKey = getZoneKey(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        long expiry = System.currentTimeMillis() + FIRE_SUPPRESSION_MS;
        
        // Only update if this extends the suppression time
        fireSuppressionZones.merge(zoneKey, expiry, Math::max);
    }

    /**
     * Checks if a block is in a fire-suppressed zone.
     * Uses spatial hashing for O(1) lookup - checks the block's zone plus adjacent zones
     * to handle blocks near zone boundaries.
     *
     * @param block The block to check
     * @return true if fire should be suppressed at this location
     */
    public boolean isFireSuppressed(Block block) {
        World world = block.getWorld();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        // Calculate which zone this block is in
        int zoneX = Math.floorDiv(x, FIRE_ZONE_SIZE);
        int zoneY = Math.floorDiv(y, FIRE_ZONE_SIZE);
        int zoneZ = Math.floorDiv(z, FIRE_ZONE_SIZE);

        long currentTime = System.currentTimeMillis();
        UUID worldUID = world.getUID();

        // Check this zone and all 26 neighbors (3x3x3 cube of zones)
        // This ensures we catch blocks near zone boundaries
        // Total: 27 HashMap lookups = O(1) constant time
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    String zoneKey = worldUID.toString() + ":" + 
                        (zoneX + dx) + ":" + (zoneY + dy) + ":" + (zoneZ + dz);
                    Long expiry = fireSuppressionZones.get(zoneKey);
                    if (expiry != null && currentTime < expiry) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Clears fire blocks within a 3-block radius of a restored block.
     * This is O(343) = O(1) constant time, still highly scalable.
     */
    private void clearAdjacentFire(Block restoredBlock) {
        long currentTime = System.currentTimeMillis();
        
        // Check 7x7x7 cube (3 blocks in each direction)
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    Block check = restoredBlock.getRelative(dx, dy, dz);
                    Material type = check.getType();
                    if (type == Material.FIRE || type == Material.SOUL_FIRE) {
                        // Set cooldown to prevent the fire removal from being re-captured
                        String fireLocationKey = getLocationKey(check.getLocation());
                        restorationCooldowns.put(fireLocationKey, currentTime + RESTORATION_COOLDOWN_MS);
                        
                        // Remove fire without physics
                        check.setType(Material.AIR, false);
                    }
                }
            }
        }
    }

    /**
     * Cancel all pending restorations and clear all data.
     * Called on plugin disable.
     */
    public void shutdown() {
        // Shutdown restoration queue
        restorationQueue.shutdown();
        
        originalSnapshots.clear();
        fixedWorlds.clear();
        restorationCooldowns.clear();
        fireSuppressionZones.clear();
        baselineOnlyCaptures.clear();

        // Shutdown chunk scanner
        if (chunkScanner != null) {
            chunkScanner.shutdown();
        }
    }

    /**
     * Get count of pending restorations for a world.
     */
    public int getPendingCount(World world) {
        String worldPrefix = world.getUID().toString() + ":";
        return restorationQueue.getPendingCount(worldPrefix);
    }

    /**
     * Gets the restoration queue statistics.
     */
    public String getQueueStats() {
        return restorationQueue.getStats();
    }

    /**
     * Gets detailed restoration queue statistics.
     */
    public String getDetailedQueueStats() {
        return restorationQueue.getDetailedStats();
    }

    /**
     * Estimates memory used by block snapshots in MB.
     * Each snapshot includes: Location (~40 bytes), BlockData (~200 bytes), BlockState (~500 bytes avg)
     */
    public double getSnapshotsMemoryMB() {
        // Estimated bytes per snapshot (conservative estimate)
        int bytesPerSnapshot = 800;  // Location + BlockData + BlockState + Map overhead
        return (originalSnapshots.size() * bytesPerSnapshot) / (1024.0 * 1024.0);
    }

    /**
     * Gets the count of stored snapshots.
     */
    public int getSnapshotCount() {
        return originalSnapshots.size();
    }

    /**
     * Gets the count of restoration cooldowns.
     */
    public int getCooldownCount() {
        return restorationCooldowns.size();
    }

    /**
     * Gets the count of fire suppression zones.
     */
    public int getFireSuppressionZoneCount() {
        return fireSuppressionZones.size();
    }

    /**
     * Gets the persistence manager stats.
     */
    public String getPersistenceStats() {
        if (persistenceManager == null) {
            return "Persistence disabled";
        }
        return persistenceManager.getStats();
    }
}
