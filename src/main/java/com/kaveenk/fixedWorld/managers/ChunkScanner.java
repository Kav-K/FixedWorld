package com.kaveenk.fixedWorld.managers;

import com.kaveenk.fixedWorld.FixedWorld;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodically scans chunks to detect block changes that bypass events.
 * This provides ABSOLUTE mode - catching /fill, /setblock, plugin modifications, etc.
 * 
 * Performance optimizations:
 * - Spreads scanning across multiple ticks (configurable chunks per tick)
 * - Uses ChunkSnapshot for fast baseline comparison
 * - Only scans loaded chunks
 * - Skips already-captured blocks
 */
public class ChunkScanner implements Listener {

    private final FixedWorld plugin;
    private final WorldSnapshotManager snapshotManager;

    // Baseline snapshots: world UUID -> (chunk key -> snapshot)
    // Chunk key = (chunkX << 32) | (chunkZ & 0xFFFFFFFFL)
    private final Map<UUID, Map<Long, ChunkSnapshot>> baselineSnapshots = new ConcurrentHashMap<>();

    // Tracks which blocks we've already detected as changed (to avoid re-processing)
    // Key format: "worldUUID:x:y:z"
    private final Set<String> detectedChanges = ConcurrentHashMap.newKeySet();

    // Scanning task
    private BukkitRunnable scanTask;

    // Performance tuning
    private static final int CHUNKS_PER_TICK = 2;  // How many chunks to scan per tick
    private static final int SCAN_INTERVAL_TICKS = 5;  // How often to run scanner (5 ticks = 0.25s)

    // Scanning state
    private final Map<UUID, Iterator<Map.Entry<Long, ChunkSnapshot>>> scanIterators = new HashMap<>();

    public ChunkScanner(FixedWorld plugin, WorldSnapshotManager snapshotManager) {
        this.plugin = plugin;
        this.snapshotManager = snapshotManager;
    }

    /**
     * Called when fixed world is enabled for a world.
     * Captures baseline snapshots of all loaded chunks.
     */
    public void onFixedWorldEnabled(World world) {
        UUID worldId = world.getUID();
        Map<Long, ChunkSnapshot> worldSnapshots = new ConcurrentHashMap<>();

        // Capture all currently loaded chunks
        for (Chunk chunk : world.getLoadedChunks()) {
            long chunkKey = getChunkKey(chunk.getX(), chunk.getZ());
            worldSnapshots.put(chunkKey, chunk.getChunkSnapshot());
        }

        baselineSnapshots.put(worldId, worldSnapshots);
        plugin.getLogger().info("[ChunkScanner] Captured " + worldSnapshots.size() + 
                               " chunk baselines for '" + world.getName() + "'");

        // Start scanning if not already running
        startScanning();
    }

    /**
     * Called when fixed world is disabled for a world.
     */
    public void onFixedWorldDisabled(World world) {
        UUID worldId = world.getUID();
        baselineSnapshots.remove(worldId);
        scanIterators.remove(worldId);

        // Clear detected changes for this world
        String prefix = worldId.toString() + ":";
        detectedChanges.removeIf(key -> key.startsWith(prefix));

        // Stop scanning if no worlds left
        if (baselineSnapshots.isEmpty()) {
            stopScanning();
        }
    }

    /**
     * Captures new chunks as they load.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        UUID worldId = world.getUID();

        Map<Long, ChunkSnapshot> worldSnapshots = baselineSnapshots.get(worldId);
        if (worldSnapshots == null) {
            return; // Not a fixed world
        }

        Chunk chunk = event.getChunk();
        long chunkKey = getChunkKey(chunk.getX(), chunk.getZ());

        // Only capture if we don't have a baseline for this chunk yet
        if (!worldSnapshots.containsKey(chunkKey)) {
            worldSnapshots.put(chunkKey, chunk.getChunkSnapshot());
        }
    }

    /**
     * Starts the periodic scanning task.
     */
    private void startScanning() {
        if (scanTask != null) {
            return; // Already running
        }

        scanTask = new BukkitRunnable() {
            @Override
            public void run() {
                performScanTick();
            }
        };
        scanTask.runTaskTimer(plugin, SCAN_INTERVAL_TICKS, SCAN_INTERVAL_TICKS);
    }

    /**
     * Stops the scanning task.
     */
    private void stopScanning() {
        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
    }

    /**
     * Performs one tick of scanning work.
     */
    private void performScanTick() {
        for (UUID worldId : baselineSnapshots.keySet()) {
            World world = plugin.getServer().getWorld(worldId);
            if (world == null) continue;

            Map<Long, ChunkSnapshot> worldSnapshots = baselineSnapshots.get(worldId);
            if (worldSnapshots == null || worldSnapshots.isEmpty()) continue;

            // Get or create iterator for this world
            Iterator<Map.Entry<Long, ChunkSnapshot>> iterator = scanIterators.get(worldId);
            if (iterator == null || !iterator.hasNext()) {
                // Restart from beginning
                iterator = worldSnapshots.entrySet().iterator();
                scanIterators.put(worldId, iterator);
            }

            // Process a few chunks this tick
            int chunksProcessed = 0;
            while (iterator.hasNext() && chunksProcessed < CHUNKS_PER_TICK) {
                Map.Entry<Long, ChunkSnapshot> entry = iterator.next();
                ChunkSnapshot baseline = entry.getValue();

                // Check if chunk is still loaded
                int chunkX = baseline.getX();
                int chunkZ = baseline.getZ();
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    continue; // Skip unloaded chunks
                }

                scanChunk(world, baseline);
                chunksProcessed++;
            }
        }
    }

    /**
     * Scans a single chunk for changes.
     */
    private void scanChunk(World world, ChunkSnapshot baseline) {
        int chunkX = baseline.getX();
        int chunkZ = baseline.getZ();
        int baseX = chunkX << 4;  // chunkX * 16
        int baseZ = chunkZ << 4;  // chunkZ * 16

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight();

        // Scan all blocks in the chunk
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    int worldX = baseX + x;
                    int worldZ = baseZ + z;

                    // Get baseline block data
                    BlockData baselineData = baseline.getBlockData(x, y, z);
                    Material baselineMaterial = baselineData.getMaterial();

                    // Get current block
                    Block currentBlock = world.getBlockAt(worldX, y, worldZ);
                    Material currentMaterial = currentBlock.getType();

                    // Quick check: material changed?
                    if (currentMaterial != baselineMaterial) {
                        handleBlockChange(world, currentBlock, baselineData);
                        continue;
                    }

                    // Detailed check: block data changed? (rotation, waterlogged, etc.)
                    BlockData currentData = currentBlock.getBlockData();
                    if (!currentData.equals(baselineData)) {
                        handleBlockChange(world, currentBlock, baselineData);
                    }
                }
            }
        }
    }

    /**
     * Handles a detected block change.
     */
    private void handleBlockChange(World world, Block currentBlock, BlockData baselineData) {
        String locationKey = world.getUID() + ":" + 
                            currentBlock.getX() + ":" + 
                            currentBlock.getY() + ":" + 
                            currentBlock.getZ();

        // Skip if already detected
        if (!detectedChanges.add(locationKey)) {
            return;
        }

        // Create a snapshot from the baseline data and schedule restoration
        snapshotManager.captureFromBaselineAndScheduleRestore(
            currentBlock.getLocation(), 
            baselineData
        );
    }

    /**
     * Creates a chunk key from coordinates.
     */
    private long getChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    /**
     * Clears detected changes for a location (called after restoration).
     */
    public void clearDetectedChange(String locationKey) {
        detectedChanges.remove(locationKey);
    }

    /**
     * Shuts down the scanner.
     */
    public void shutdown() {
        stopScanning();
        baselineSnapshots.clear();
        detectedChanges.clear();
        scanIterators.clear();
    }

    /**
     * Gets the count of baseline chunks for a world.
     */
    public int getBaselineChunkCount(World world) {
        Map<Long, ChunkSnapshot> worldSnapshots = baselineSnapshots.get(world.getUID());
        return worldSnapshots != null ? worldSnapshots.size() : 0;
    }
}
