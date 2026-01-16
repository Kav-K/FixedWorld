package com.kaveenk.fixedWorld.managers;

import com.kaveenk.fixedWorld.FixedWorld;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.scheduler.BukkitRunnable;

import java.lang.ref.SoftReference;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * OPTIMIZED ChunkScanner v2 - Designed for servers with thousands of players.
 * 
 * Architecture:
 * 1. All chunks: Lightweight signatures (~100 bytes each)
 * 2. Active chunks: Full snapshots in SoftReference cache (GC can reclaim if needed)
 * 3. Detection: Compare signatures to detect changes cheaply
 * 4. Deep scan: Use full snapshots to find exact block changes
 * 
 * Key optimizations:
 * - Signature-based change detection (O(1) per chunk vs O(98K) for full scan)
 * - Activity-based prioritization (player-adjacent chunks first)
 * - SoftReference cache for memory pressure management
 * - Section-level granularity (only deep-scan changed sections)
 * - Y-level filtering (skip bedrock/sky)
 * 
 * Memory estimates:
 * - 10,000 chunks signatures: ~1MB
 * - 1,000 active chunk snapshots: ~16MB
 * - Total: ~17MB (vs ~160MB for all full snapshots)
 */
public class ChunkScanner implements Listener {

    private final FixedWorld plugin;
    private final WorldSnapshotManager snapshotManager;

    // Lightweight signatures for all chunks
    private final Map<UUID, Map<Long, ChunkSignature>> chunkSignatures = new ConcurrentHashMap<>();
    
    // Full snapshots cached with SoftReferences (GC can reclaim under memory pressure)
    private final Map<UUID, Map<Long, SoftReference<ChunkSnapshot>>> snapshotCache = new ConcurrentHashMap<>();

    // Detected changes tracking
    private final Set<String> detectedChanges = ConcurrentHashMap.newKeySet();

    // Queue of sections needing deep-scan
    private final Queue<ScanTask> pendingScanTasks = new ConcurrentLinkedQueue<>();

    // Activity tracking
    private final Map<Long, Long> chunkActivityTimestamps = new ConcurrentHashMap<>();

    // Tracks chunks currently undergoing async full verification (prevents duplicates)
    // Key: "worldUUID:chunkKey"
    private final Set<String> chunksBeingVerified = ConcurrentHashMap.newKeySet();

    // Scanning tasks
    private BukkitRunnable signatureScanTask;
    private BukkitRunnable deepScanTask;
    private BukkitRunnable activityTask;

    // Scanning state per world
    private final Map<UUID, Long> scanCursors = new ConcurrentHashMap<>();

    // Statistics tracking
    private long totalSignatureScans = 0;
    private long totalDeepScans = 0;
    private long totalFullVerifications = 0;
    private long totalAsyncVerifications = 0;
    private long totalChangesDetected = 0;
    private long totalSnapshotCacheMisses = 0;
    private long lastStatsLogTime = 0;
    private static final long STATS_LOG_INTERVAL_MS = 30_000;  // Log stats every 30s

    // ==================== TUNING PARAMETERS ====================
    
    // Signature scanning (lightweight)
    private static final int SIGNATURE_CHUNKS_PER_TICK = 16;
    private static final int SIGNATURE_SCAN_INTERVAL_TICKS = 10;  // 0.5s
    
    // Deep scanning (heavy)
    private static final int DEEP_SCAN_SECTIONS_PER_TICK = 8;
    private static final int DEEP_SCAN_INTERVAL_TICKS = 2;
    
    // Y-level optimization
    private static final int SCAN_Y_MIN = -64;
    private static final int SCAN_Y_MAX = 192;
    
    // Activity tracking
    private static final long ACTIVITY_RELEVANCE_MS = 60_000;
    private static final int PLAYER_CHUNK_RADIUS = 3;
    
    // Full verification (bypasses signatures - catches everything)
    private static final long FULL_VERIFY_INTERVAL_MS = 120_000;  // Every 2 minutes per chunk
    
    // Section constants
    private static final int SECTIONS_PER_CHUNK = 24;
    private static final int SECTION_HEIGHT = 16;
    private static final int MIN_WORLD_Y = -64;

    // ==================== INITIALIZATION ====================

    public ChunkScanner(FixedWorld plugin, WorldSnapshotManager snapshotManager) {
        this.plugin = plugin;
        this.snapshotManager = snapshotManager;
    }

    // ==================== FIXED WORLD LIFECYCLE ====================

    public void onFixedWorldEnabled(World world) {
        UUID worldId = world.getUID();
        
        Map<Long, ChunkSignature> worldSignatures = new ConcurrentHashMap<>();
        Map<Long, SoftReference<ChunkSnapshot>> worldCache = new ConcurrentHashMap<>();
        chunkSignatures.put(worldId, worldSignatures);
        snapshotCache.put(worldId, worldCache);

        plugin.getLogger().info("[ChunkScanner] Capturing baselines for '" + world.getName() + "'...");
        long startTime = System.currentTimeMillis();
        
        int captured = 0;
        for (Chunk chunk : world.getLoadedChunks()) {
            long chunkKey = getChunkKey(chunk.getX(), chunk.getZ());
            ChunkSnapshot snapshot = chunk.getChunkSnapshot();
            
            // Store signature (lightweight)
            worldSignatures.put(chunkKey, new ChunkSignature(chunk.getX(), chunk.getZ(), snapshot));
            
            // Cache full snapshot (will be GC'd if memory tight)
            worldCache.put(chunkKey, new SoftReference<>(snapshot));
            
            captured++;
            
            // Progress logging for large worlds
            if (captured % 500 == 0) {
                plugin.getLogger().info("[ChunkScanner] Progress: " + captured + " chunks captured...");
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        plugin.getLogger().info("[ChunkScanner] Captured " + captured + " chunk baselines in " + elapsed + "ms");
        plugin.getLogger().info("[ChunkScanner] Estimated memory: signatures=" + 
                               String.format("%.2f", getSignaturesMemoryMB()) + "MB, snapshots=" + 
                               String.format("%.2f", getSnapshotCacheMemoryMB()) + "MB");
        startScanning();
    }

    public void onFixedWorldDisabled(World world) {
        UUID worldId = world.getUID();
        
        int removedSigs = 0;
        int removedSnapshots = 0;
        Map<Long, ChunkSignature> sigs = chunkSignatures.remove(worldId);
        Map<Long, SoftReference<ChunkSnapshot>> cache = snapshotCache.remove(worldId);
        if (sigs != null) removedSigs = sigs.size();
        if (cache != null) removedSnapshots = cache.size();
        
        scanCursors.remove(worldId);

        String prefix = worldId.toString() + ":";
        int removedChanges = 0;
        for (Iterator<String> it = detectedChanges.iterator(); it.hasNext(); ) {
            if (it.next().startsWith(prefix)) {
                it.remove();
                removedChanges++;
            }
        }
        pendingScanTasks.removeIf(task -> task.worldId.equals(worldId));

        plugin.getLogger().info("[ChunkScanner] Disabled for '" + world.getName() + 
                               "': freed " + removedSigs + " signatures, " + 
                               removedSnapshots + " cached snapshots, " + 
                               removedChanges + " tracked changes");

        if (chunkSignatures.isEmpty()) {
            stopScanning();
            plugin.getLogger().info("[ChunkScanner] All worlds disabled, scanner stopped");
        }
    }

    // ==================== CHUNK EVENTS ====================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        UUID worldId = world.getUID();
        
        Map<Long, ChunkSignature> worldSigs = chunkSignatures.get(worldId);
        Map<Long, SoftReference<ChunkSnapshot>> worldCache = snapshotCache.get(worldId);
        if (worldSigs == null) return;

        Chunk chunk = event.getChunk();
        long chunkKey = getChunkKey(chunk.getX(), chunk.getZ());
        
        if (!worldSigs.containsKey(chunkKey)) {
            ChunkSnapshot snapshot = chunk.getChunkSnapshot();
            worldSigs.put(chunkKey, new ChunkSignature(chunk.getX(), chunk.getZ(), snapshot));
            worldCache.put(chunkKey, new SoftReference<>(snapshot));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        long chunkKey = getChunkKey(event.getChunk().getX(), event.getChunk().getZ());
        chunkActivityTimestamps.remove(chunkKey);
    }

    // ==================== SCANNING TASKS ====================

    private void startScanning() {
        if (signatureScanTask != null) return;

        plugin.getLogger().info("[ChunkScanner] Starting scanner tasks...");
        plugin.getLogger().info("[ChunkScanner] Config: sigChunks/tick=" + SIGNATURE_CHUNKS_PER_TICK + 
                               ", sigInterval=" + SIGNATURE_SCAN_INTERVAL_TICKS + " ticks" +
                               ", deepSections/tick=" + DEEP_SCAN_SECTIONS_PER_TICK +
                               ", deepInterval=" + DEEP_SCAN_INTERVAL_TICKS + " ticks");

        // Lightweight signature comparison
        signatureScanTask = new BukkitRunnable() {
            @Override
            public void run() {
                performSignatureScan();
            }
        };
        signatureScanTask.runTaskTimer(plugin, SIGNATURE_SCAN_INTERVAL_TICKS, SIGNATURE_SCAN_INTERVAL_TICKS);

        // Deep section scanning
        deepScanTask = new BukkitRunnable() {
            @Override
            public void run() {
                performDeepScan();
            }
        };
        deepScanTask.runTaskTimer(plugin, DEEP_SCAN_INTERVAL_TICKS, DEEP_SCAN_INTERVAL_TICKS);

        // Activity tracking
        activityTask = new BukkitRunnable() {
            @Override
            public void run() {
                updatePlayerActivity();
            }
        };
        activityTask.runTaskTimer(plugin, 20, 20);
        
        // Periodic stats logging
        new BukkitRunnable() {
            @Override
            public void run() {
                logPeriodicStats();
            }
        }.runTaskTimer(plugin, 600, 600);  // Every 30 seconds
    }

    private void stopScanning() {
        if (signatureScanTask != null) { signatureScanTask.cancel(); signatureScanTask = null; }
        if (deepScanTask != null) { deepScanTask.cancel(); deepScanTask = null; }
        if (activityTask != null) { activityTask.cancel(); activityTask = null; }
    }

    private void updatePlayerActivity() {
        long now = System.currentTimeMillis();
        
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            int cx = player.getLocation().getBlockX() >> 4;
            int cz = player.getLocation().getBlockZ() >> 4;
            
            for (int dx = -PLAYER_CHUNK_RADIUS; dx <= PLAYER_CHUNK_RADIUS; dx++) {
                for (int dz = -PLAYER_CHUNK_RADIUS; dz <= PLAYER_CHUNK_RADIUS; dz++) {
                    chunkActivityTimestamps.put(getChunkKey(cx + dx, cz + dz), now);
                }
            }
        }
        
        // Cleanup old entries
        chunkActivityTimestamps.entrySet().removeIf(e -> now - e.getValue() > ACTIVITY_RELEVANCE_MS * 2);
    }

    // ==================== SIGNATURE SCANNING ====================

    private void performSignatureScan() {
        int processed = 0;
        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, Map<Long, ChunkSignature>> worldEntry : chunkSignatures.entrySet()) {
            UUID worldId = worldEntry.getKey();
            World world = plugin.getServer().getWorld(worldId);
            if (world == null) continue;

            Map<Long, ChunkSignature> signatures = worldEntry.getValue();
            Map<Long, SoftReference<ChunkSnapshot>> cache = snapshotCache.get(worldId);
            if (signatures.isEmpty()) continue;

            // Get sorted chunk keys for round-robin scanning
            List<Long> chunkKeys = new ArrayList<>(signatures.keySet());
            
            // Sort by activity (active chunks first)
            chunkKeys.sort((a, b) -> {
                Long actA = chunkActivityTimestamps.get(a);
                Long actB = chunkActivityTimestamps.get(b);
                return Long.compare(actB != null ? actB : 0, actA != null ? actA : 0);
            });

            // Resume from last position
            Long cursor = scanCursors.get(worldId);
            int startIdx = cursor != null ? findChunkIndex(chunkKeys, cursor) : 0;

            for (int i = 0; i < chunkKeys.size() && processed < SIGNATURE_CHUNKS_PER_TICK; i++) {
                int idx = (startIdx + i) % chunkKeys.size();
                long chunkKey = chunkKeys.get(idx);
                
                ChunkSignature sig = signatures.get(chunkKey);
                if (sig == null) continue;
                
                // Skip unloaded chunks
                if (!world.isChunkLoaded(sig.chunkX, sig.chunkZ)) continue;
                
                // Throttle inactive chunks
                Long activity = chunkActivityTimestamps.get(chunkKey);
                boolean isActive = activity != null && (now - activity) < ACTIVITY_RELEVANCE_MS;
                if (!isActive && sig.lastScanned > 0 && (now - sig.lastScanned) < 10_000) {
                    continue;  // Skip - scanned recently and not active
                }

                // Get current chunk and compute current signatures
                Chunk chunk = world.getChunkAt(sig.chunkX, sig.chunkZ);
                ChunkSnapshot currentSnapshot = chunk.getChunkSnapshot();
                
                // Check if this chunk needs full verification (bypasses signatures)
                boolean needsFullVerification = (sig.lastFullVerification == 0) || 
                                               (now - sig.lastFullVerification > FULL_VERIFY_INTERVAL_MS);
                
                // Check if already being verified (avoid duplicates)
                String verifyKey = worldId + ":" + chunkKey;
                if (needsFullVerification && chunksBeingVerified.contains(verifyKey)) {
                    needsFullVerification = false;  // Skip, already in progress
                }
                
                // For signature-based detection, queue synchronous deep scans
                for (int section = 0; section < SECTIONS_PER_CHUNK; section++) {
                    int sectionY = MIN_WORLD_Y + (section * SECTION_HEIGHT);
                    if (sectionY < SCAN_Y_MIN || sectionY >= SCAN_Y_MAX) continue;
                    
                    int currentSig = computeSectionSignature(currentSnapshot, sectionY);
                    boolean signatureChanged = currentSig != sig.sectionSignatures[section];
                    
                    if (signatureChanged) {
                        // Signature changed - queue for sync deep scan
                        queueDeepScan(worldId, sig, section, sectionY, cache, chunkKey, chunk);
                    }
                    
                    // Update signature to current
                    sig.sectionSignatures[section] = currentSig;
                }
                
                // For full verification, do it async to avoid blocking main thread
                if (needsFullVerification) {
                    scheduleAsyncFullVerification(worldId, sig, cache, chunkKey, currentSnapshot);
                    sig.lastFullVerification = now;
                    totalFullVerifications++;
                }
                
                sig.lastScanned = now;
                processed++;
                totalSignatureScans++;
            }
            
            // Save cursor position
            if (!chunkKeys.isEmpty()) {
                int nextIdx = (startIdx + processed) % chunkKeys.size();
                scanCursors.put(worldId, chunkKeys.get(nextIdx));
            }
        }
    }

    private int findChunkIndex(List<Long> keys, long target) {
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i).equals(target)) return i;
        }
        return 0;
    }

    private void queueDeepScan(UUID worldId, ChunkSignature sig, int section, int sectionY,
                                Map<Long, SoftReference<ChunkSnapshot>> cache, long chunkKey,
                                Chunk currentChunk) {
        // Check for duplicate
        String taskKey = worldId + ":" + sig.chunkX + ":" + sig.chunkZ + ":" + section;
        for (ScanTask t : pendingScanTasks) {
            if (t.getKey().equals(taskKey)) return;
        }
        
        // Get baseline snapshot from cache
        SoftReference<ChunkSnapshot> ref = cache.get(chunkKey);
        ChunkSnapshot baseline = ref != null ? ref.get() : null;
        
        if (baseline == null) {
            // Snapshot was GC'd - recapture from current state
            // This means we lose history but can track future changes
            totalSnapshotCacheMisses++;
            
            // Recapture baseline from current state
            ChunkSnapshot newBaseline = currentChunk.getChunkSnapshot();
            cache.put(chunkKey, new SoftReference<>(newBaseline));
            
            // Update ALL section signatures to current state
            for (int s = 0; s < SECTIONS_PER_CHUNK; s++) {
                int secY = MIN_WORLD_Y + (s * SECTION_HEIGHT);
                sig.sectionSignatures[s] = computeSectionSignature(newBaseline, secY);
            }
            
            plugin.getLogger().warning("[ChunkScanner] Cache miss at chunk (" + sig.chunkX + "," + sig.chunkZ + 
                                      ") - recaptured baseline (changes before this point lost)");
            return;
        }
        
        pendingScanTasks.offer(new ScanTask(worldId, sig.chunkX, sig.chunkZ, section, sectionY, baseline));
    }

    // ==================== ASYNC FULL VERIFICATION ====================

    /**
     * Schedules a full verification of a chunk to run asynchronously.
     * The comparison work happens off the main thread, only queueing restorations on main thread.
     */
    private void scheduleAsyncFullVerification(UUID worldId, ChunkSignature sig,
                                                Map<Long, SoftReference<ChunkSnapshot>> cache,
                                                long chunkKey, ChunkSnapshot currentSnapshot) {
        // Get baseline snapshot
        SoftReference<ChunkSnapshot> ref = cache.get(chunkKey);
        ChunkSnapshot baseline = ref != null ? ref.get() : null;
        
        if (baseline == null) {
            // No baseline - can't verify, recapture for future
            totalSnapshotCacheMisses++;
            cache.put(chunkKey, new SoftReference<>(currentSnapshot));
            for (int s = 0; s < SECTIONS_PER_CHUNK; s++) {
                int secY = MIN_WORLD_Y + (s * SECTION_HEIGHT);
                sig.sectionSignatures[s] = computeSectionSignature(currentSnapshot, secY);
            }
            return;
        }

        // Mark as being verified (prevents duplicates)
        String verifyKey = worldId + ":" + chunkKey;
        if (!chunksBeingVerified.add(verifyKey)) {
            return;  // Already being verified
        }

        // Capture data needed for async task
        final int chunkX = sig.chunkX;
        final int chunkZ = sig.chunkZ;
        final ChunkSnapshot baselineSnapshot = baseline;

        // Run comparison async
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                List<BlockChange> changes = new ArrayList<>();
                int baseX = chunkX << 4;
                int baseZ = chunkZ << 4;

                // Compare ALL blocks in scannable sections
                for (int section = 0; section < SECTIONS_PER_CHUNK; section++) {
                    int sectionY = MIN_WORLD_Y + (section * SECTION_HEIGHT);
                    if (sectionY < SCAN_Y_MIN || sectionY >= SCAN_Y_MAX) continue;

                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            for (int dy = 0; dy < SECTION_HEIGHT; dy++) {
                                int y = sectionY + dy;
                                BlockData baselineData = baselineSnapshot.getBlockData(x, y, z);
                                BlockData currentData = currentSnapshot.getBlockData(x, y, z);

                                if (!baselineData.equals(currentData)) {
                                    changes.add(new BlockChange(baseX + x, y, baseZ + z, baselineData));
                                }
                            }
                        }
                    }
                }

                // If changes found, schedule restoration on main thread
                if (!changes.isEmpty()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        World world = plugin.getServer().getWorld(worldId);
                        if (world != null) {
                            for (BlockChange change : changes) {
                                handleBlockChange(world, change.x, change.y, change.z, change.baselineData);
                            }
                        }
                    });
                }

                totalAsyncVerifications++;
            } finally {
                // Always remove from being verified set
                chunksBeingVerified.remove(verifyKey);
            }
        });
    }

    /**
     * Simple holder for block change data (used in async verification).
     */
    private static class BlockChange {
        final int x, y, z;
        final BlockData baselineData;

        BlockChange(int x, int y, int z, BlockData baselineData) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.baselineData = baselineData;
        }
    }

    // ==================== DEEP SCANNING ====================

    private void performDeepScan() {
        int sectionsProcessed = 0;
        
        while (!pendingScanTasks.isEmpty() && sectionsProcessed < DEEP_SCAN_SECTIONS_PER_TICK) {
            ScanTask task = pendingScanTasks.poll();
            if (task == null) break;
            
            World world = plugin.getServer().getWorld(task.worldId);
            if (world == null || !world.isChunkLoaded(task.chunkX, task.chunkZ)) continue;
            
            // Get current snapshot
            Chunk chunk = world.getChunkAt(task.chunkX, task.chunkZ);
            ChunkSnapshot currentSnapshot = chunk.getChunkSnapshot();
            
            // Deep scan this section
            deepScanSection(world, task.baselineSnapshot, currentSnapshot, 
                           task.chunkX, task.chunkZ, task.sectionY);
            
            // Update signature to current state (so we don't re-scan same changes)
            Map<Long, ChunkSignature> worldSigs = chunkSignatures.get(task.worldId);
            if (worldSigs != null) {
                ChunkSignature sig = worldSigs.get(getChunkKey(task.chunkX, task.chunkZ));
                if (sig != null) {
                    sig.sectionSignatures[task.section] = computeSectionSignature(currentSnapshot, task.sectionY);
                }
            }
            
            totalDeepScans++;
            sectionsProcessed++;
        }
    }

    private void deepScanSection(World world, ChunkSnapshot baseline, ChunkSnapshot current,
                                  int chunkX, int chunkZ, int sectionY) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int dy = 0; dy < SECTION_HEIGHT; dy++) {
                    int y = sectionY + dy;
                    
                    BlockData baselineData = baseline.getBlockData(x, y, z);
                    BlockData currentData = current.getBlockData(x, y, z);
                    
                    if (!baselineData.equals(currentData)) {
                        handleBlockChange(world, baseX + x, y, baseZ + z, baselineData);
                    }
                }
            }
        }
    }

    private void handleBlockChange(World world, int x, int y, int z, BlockData baselineData) {
        String locationKey = world.getUID() + ":" + x + ":" + y + ":" + z;
        if (!detectedChanges.add(locationKey)) return;
        
        totalChangesDetected++;
        Block block = world.getBlockAt(x, y, z);
        snapshotManager.captureFromBaselineAndScheduleRestore(block.getLocation(), baselineData);
    }

    // ==================== SIGNATURE COMPUTATION ====================

    /**
     * Computes a comprehensive signature for a section.
     * Samples ~1024 blocks (25% of section) in a distributed pattern.
     * Much more thorough than sparse sampling, but still O(1024) vs O(4096).
     */
    private int computeSectionSignature(ChunkSnapshot snapshot, int sectionY) {
        int hash = 17;
        int nonAirCount = 0;
        
        // COMPREHENSIVE SAMPLING: ~1024 blocks per section (25% coverage)
        // Pattern ensures every 2x2x2 cube has at least one sample
        
        // 1. Dense grid: every 2nd block in X/Z, every 2nd in Y = 512 samples
        for (int x = 0; x < 16; x += 2) {
            for (int z = 0; z < 16; z += 2) {
                for (int dy = 0; dy < SECTION_HEIGHT; dy += 2) {
                    Material mat = snapshot.getBlockType(x, sectionY + dy, z);
                    hash = 31 * hash + mat.ordinal();
                    if (!mat.isAir()) nonAirCount++;
                }
            }
        }
        
        // 2. Offset grid: shifted by 1 in each dimension = 512 more samples
        // This fills in the gaps from the first grid
        for (int x = 1; x < 16; x += 2) {
            for (int z = 1; z < 16; z += 2) {
                for (int dy = 1; dy < SECTION_HEIGHT; dy += 2) {
                    Material mat = snapshot.getBlockType(x, sectionY + dy, z);
                    hash = 31 * hash + mat.ordinal();
                    if (!mat.isAir()) nonAirCount++;
                }
            }
        }
        
        // 3. Full perimeter scan at mid-height (catches edge changes)
        int midY = sectionY + 8;
        for (int x = 0; x < 16; x++) {
            hash = 31 * hash + snapshot.getBlockType(x, midY, 0).ordinal();
            hash = 31 * hash + snapshot.getBlockType(x, midY, 15).ordinal();
        }
        for (int z = 1; z < 15; z++) {
            hash = 31 * hash + snapshot.getBlockType(0, midY, z).ordinal();
            hash = 31 * hash + snapshot.getBlockType(15, midY, z).ordinal();
        }
        
        return hash ^ (nonAirCount << 20);
    }

    // ==================== UTILITIES ====================

    private long getChunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public void clearDetectedChange(String locationKey) {
        detectedChanges.remove(locationKey);
    }

    public void shutdown() {
        plugin.getLogger().info("[ChunkScanner] Shutting down...");
        plugin.getLogger().info("[ChunkScanner] Final stats: sigScans=" + totalSignatureScans + 
                               ", deepScans=" + totalDeepScans +
                               ", fullVerify=" + totalFullVerifications +
                               ", asyncVerify=" + totalAsyncVerifications +
                               ", changesDetected=" + totalChangesDetected +
                               ", cacheMisses=" + totalSnapshotCacheMisses);
        stopScanning();
        chunkSignatures.clear();
        snapshotCache.clear();
        detectedChanges.clear();
        pendingScanTasks.clear();
        chunkActivityTimestamps.clear();
        chunksBeingVerified.clear();
        scanCursors.clear();
    }

    public int getBaselineChunkCount(World world) {
        Map<Long, ChunkSignature> sigs = chunkSignatures.get(world.getUID());
        return sigs != null ? sigs.size() : 0;
    }
    
    public int getTotalBaselineChunkCount() {
        int total = 0;
        for (Map<Long, ChunkSignature> sigs : chunkSignatures.values()) {
            total += sigs.size();
        }
        return total;
    }

    public int getPendingScanCount() {
        return pendingScanTasks.size();
    }

    public int getCachedSnapshotCount(World world) {
        Map<Long, SoftReference<ChunkSnapshot>> cache = snapshotCache.get(world.getUID());
        if (cache == null) return 0;
        int count = 0;
        for (SoftReference<ChunkSnapshot> ref : cache.values()) {
            if (ref.get() != null) count++;
        }
        return count;
    }
    
    public int getTotalCachedSnapshotCount() {
        int total = 0;
        for (Map<Long, SoftReference<ChunkSnapshot>> cache : snapshotCache.values()) {
            for (SoftReference<ChunkSnapshot> ref : cache.values()) {
                if (ref.get() != null) total++;
            }
        }
        return total;
    }

    // ==================== MEMORY ESTIMATION ====================
    
    // Estimated sizes (based on structure analysis)
    private static final int BYTES_PER_SIGNATURE = 120;  // ~24 ints + overhead
    private static final int BYTES_PER_SNAPSHOT = 16_384;  // ~16KB per chunk snapshot
    private static final int BYTES_PER_DETECTED_CHANGE = 80;  // String key + set overhead
    private static final int BYTES_PER_ACTIVITY_ENTRY = 24;  // Long key + Long value
    private static final int BYTES_PER_SCAN_TASK = 200;  // Task object + snapshot reference

    /**
     * Estimates memory used by chunk signatures in MB.
     */
    public double getSignaturesMemoryMB() {
        int totalSigs = getTotalBaselineChunkCount();
        return (totalSigs * BYTES_PER_SIGNATURE) / (1024.0 * 1024.0);
    }

    /**
     * Estimates memory used by cached snapshots in MB.
     * Only counts snapshots that haven't been GC'd.
     */
    public double getSnapshotCacheMemoryMB() {
        int totalSnapshots = getTotalCachedSnapshotCount();
        return (totalSnapshots * BYTES_PER_SNAPSHOT) / (1024.0 * 1024.0);
    }

    /**
     * Estimates memory used by detected changes tracking in MB.
     */
    public double getDetectedChangesMemoryMB() {
        return (detectedChanges.size() * BYTES_PER_DETECTED_CHANGE) / (1024.0 * 1024.0);
    }

    /**
     * Estimates memory used by activity tracking in MB.
     */
    public double getActivityTrackingMemoryMB() {
        return (chunkActivityTimestamps.size() * BYTES_PER_ACTIVITY_ENTRY) / (1024.0 * 1024.0);
    }

    /**
     * Estimates memory used by pending scan tasks in MB.
     */
    public double getPendingTasksMemoryMB() {
        return (pendingScanTasks.size() * BYTES_PER_SCAN_TASK) / (1024.0 * 1024.0);
    }

    /**
     * Estimates total memory used by ChunkScanner in MB.
     */
    public double getTotalMemoryMB() {
        return getSignaturesMemoryMB() + 
               getSnapshotCacheMemoryMB() + 
               getDetectedChangesMemoryMB() + 
               getActivityTrackingMemoryMB() +
               getPendingTasksMemoryMB();
    }

    /**
     * Gets a detailed memory breakdown string.
     */
    public String getMemoryBreakdown() {
        return String.format(
            "Signatures: %.2fMB, Snapshots: %.2fMB, Changes: %.2fMB, Activity: %.2fMB, Tasks: %.2fMB | Total: %.2fMB",
            getSignaturesMemoryMB(),
            getSnapshotCacheMemoryMB(),
            getDetectedChangesMemoryMB(),
            getActivityTrackingMemoryMB(),
            getPendingTasksMemoryMB(),
            getTotalMemoryMB()
        );
    }

    // ==================== STATISTICS ====================

    public long getTotalSignatureScans() { return totalSignatureScans; }
    public long getTotalDeepScans() { return totalDeepScans; }
    public long getTotalFullVerifications() { return totalFullVerifications; }
    public long getTotalAsyncVerifications() { return totalAsyncVerifications; }
    public long getTotalChangesDetected() { return totalChangesDetected; }
    public long getTotalSnapshotCacheMisses() { return totalSnapshotCacheMisses; }
    public int getDetectedChangesCount() { return detectedChanges.size(); }
    public int getActiveChunksCount() { return chunkActivityTimestamps.size(); }
    public int getAsyncVerificationsInProgress() { return chunksBeingVerified.size(); }

    /**
     * Logs periodic statistics to console.
     */
    private void logPeriodicStats() {
        if (chunkSignatures.isEmpty()) return;
        
        long now = System.currentTimeMillis();
        if (now - lastStatsLogTime < STATS_LOG_INTERVAL_MS) return;
        lastStatsLogTime = now;

        int totalChunks = getTotalBaselineChunkCount();
        int cachedSnapshots = getTotalCachedSnapshotCount();
        int pendingTasks = pendingScanTasks.size();
        int activeChunks = chunkActivityTimestamps.size();
        
        plugin.getLogger().info(String.format(
            "[ChunkScanner] Stats: chunks=%d, cached=%d (%.1f%%), pending=%d, active=%d, changes=%d",
            totalChunks, 
            cachedSnapshots, 
            totalChunks > 0 ? (cachedSnapshots * 100.0 / totalChunks) : 0,
            pendingTasks,
            activeChunks,
            detectedChanges.size()
        ));
        plugin.getLogger().info("[ChunkScanner] Memory: " + getMemoryBreakdown());
        plugin.getLogger().info(String.format(
            "[ChunkScanner] Totals: sigScans=%d, deepScans=%d, fullVerify=%d (async=%d), detected=%d, cacheMisses=%d, inProgress=%d",
            totalSignatureScans, totalDeepScans, totalFullVerifications, totalAsyncVerifications, 
            totalChangesDetected, totalSnapshotCacheMisses, chunksBeingVerified.size()
        ));
    }

    // ==================== INNER CLASSES ====================

    /**
     * Lightweight chunk signature - only stores section hashes.
     * Memory: ~120 bytes per chunk (vs ~16KB for full snapshot)
     */
    private static class ChunkSignature {
        final int chunkX;
        final int chunkZ;
        final int[] sectionSignatures;
        long lastScanned;
        long lastFullVerification;  // For periodic full scans

        ChunkSignature(int chunkX, int chunkZ, ChunkSnapshot snapshot) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.sectionSignatures = new int[SECTIONS_PER_CHUNK];
            
            for (int i = 0; i < SECTIONS_PER_CHUNK; i++) {
                int sectionY = MIN_WORLD_Y + (i * SECTION_HEIGHT);
                this.sectionSignatures[i] = computeInitialSignature(snapshot, sectionY);
            }
        }
        
        /**
         * Comprehensive signature - samples ~1024 blocks (25% of section)
         */
        private static int computeInitialSignature(ChunkSnapshot snapshot, int sectionY) {
            int hash = 17;
            int nonAirCount = 0;
            
            // Dense grid: every 2nd block = 512 samples
            for (int x = 0; x < 16; x += 2) {
                for (int z = 0; z < 16; z += 2) {
                    for (int dy = 0; dy < SECTION_HEIGHT; dy += 2) {
                        Material mat = snapshot.getBlockType(x, sectionY + dy, z);
                        hash = 31 * hash + mat.ordinal();
                        if (!mat.isAir()) nonAirCount++;
                    }
                }
            }
            
            // Offset grid: shifted by 1 = 512 more samples
            for (int x = 1; x < 16; x += 2) {
                for (int z = 1; z < 16; z += 2) {
                    for (int dy = 1; dy < SECTION_HEIGHT; dy += 2) {
                        Material mat = snapshot.getBlockType(x, sectionY + dy, z);
                        hash = 31 * hash + mat.ordinal();
                        if (!mat.isAir()) nonAirCount++;
                    }
                }
            }
            
            // Full perimeter at mid-height
            int midY = sectionY + 8;
            for (int x = 0; x < 16; x++) {
                hash = 31 * hash + snapshot.getBlockType(x, midY, 0).ordinal();
                hash = 31 * hash + snapshot.getBlockType(x, midY, 15).ordinal();
            }
            for (int z = 1; z < 15; z++) {
                hash = 31 * hash + snapshot.getBlockType(0, midY, z).ordinal();
                hash = 31 * hash + snapshot.getBlockType(15, midY, z).ordinal();
            }
            
            return hash ^ (nonAirCount << 20);
        }
    }

    /**
     * Task for deep-scanning a section.
     */
    private static class ScanTask {
        final UUID worldId;
        final int chunkX;
        final int chunkZ;
        final int section;
        final int sectionY;
        final ChunkSnapshot baselineSnapshot;

        ScanTask(UUID worldId, int chunkX, int chunkZ, int section, int sectionY, ChunkSnapshot baseline) {
            this.worldId = worldId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.section = section;
            this.sectionY = sectionY;
            this.baselineSnapshot = baseline;
        }
        
        String getKey() {
            return worldId + ":" + chunkX + ":" + chunkZ + ":" + section;
        }
    }
}
