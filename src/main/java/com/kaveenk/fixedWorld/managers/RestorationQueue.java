package com.kaveenk.fixedWorld.managers;

import com.kaveenk.fixedWorld.FixedWorld;
import com.kaveenk.fixedWorld.models.BlockSnapshot;
import com.kaveenk.fixedWorld.persistence.PersistenceManager;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Manages block restoration in batches to avoid lag spikes.
 * Uses a priority queue sorted by restoration time and processes
 * a configurable number of blocks per tick.
 * 
 * Integrates with PersistenceManager for restart-persistence.
 */
public class RestorationQueue {

    private final FixedWorld plugin;
    
    // Priority queue sorted by restoration time (earliest first)
    private final PriorityBlockingQueue<PendingRestoration> queue;
    
    // Track pending restorations by location key for deduplication and cancellation
    private final Map<String, PendingRestoration> pendingByLocation;
    
    // Processing task
    private BukkitRunnable processingTask;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    
    // Configuration
    private int blocksPerTick = 50;  // Process up to 50 blocks per tick
    private int tickInterval = 1;    // Run every tick
    private static final long RETRY_BASE_DELAY_MS = 2000L;
    private static final long RETRY_MAX_DELAY_MS = 30000L;
    private static final long VERIFY_LOG_COOLDOWN_MS = 10000L;
    private final Map<String, Long> lastVerifyLog = new ConcurrentHashMap<>();
    
    // Callback for when a block is restored
    private Consumer<String> onRestorationComplete;
    
    // Persistence manager (optional - may be null if persistence disabled)
    private PersistenceManager persistenceManager;
    
    // Statistics
    private final AtomicInteger totalRestored = new AtomicInteger(0);
    private final AtomicInteger totalQueued = new AtomicInteger(0);

    public RestorationQueue(FixedWorld plugin) {
        this.plugin = plugin;
        this.queue = new PriorityBlockingQueue<>(1000, 
            Comparator.comparingLong(PendingRestoration::getRestoreTime));
        this.pendingByLocation = new ConcurrentHashMap<>();
    }
    
    /**
     * Sets the persistence manager for database integration.
     */
    public void setPersistenceManager(PersistenceManager manager) {
        this.persistenceManager = manager;
    }

    /**
     * Sets the callback for when a restoration is complete.
     */
    public void setOnRestorationComplete(Consumer<String> callback) {
        this.onRestorationComplete = callback;
    }

    /**
     * Configures the number of blocks to restore per tick.
     */
    public void setBlocksPerTick(int blocks) {
        this.blocksPerTick = Math.max(1, blocks);
    }

    public int getBlocksPerTick() {
        return blocksPerTick;
    }

    /**
     * Configures how often the processing task runs (in ticks).
     * Lower values = more frequent processing.
     */
    public void setTickInterval(int ticks) {
        int newInterval = Math.max(1, ticks);
        if (this.tickInterval == newInterval) {
            return;
        }
        this.tickInterval = newInterval;
        // Restart processing to apply the new interval if currently running
        if (isRunning.get()) {
            stopProcessing();
            ensureProcessing();
        }
    }

    public int getTickInterval() {
        return tickInterval;
    }
    /**
     * Queues a block for restoration at a specific time.
     * If the block is already queued, updates the restoration time.
     *
     * @param locationKey The block's location key
     * @param snapshot The snapshot to restore
     * @param restoreTimeMs When to restore (System.currentTimeMillis())
     */
    public void queueRestoration(String locationKey, BlockSnapshot snapshot, long restoreTimeMs) {
        queueRestoration(locationKey, snapshot, restoreTimeMs, 0);
    }

    private void queueRestoration(String locationKey, BlockSnapshot snapshot, long restoreTimeMs, int retryCount) {
        // Check if already queued
        PendingRestoration existing = pendingByLocation.get(locationKey);
        if (existing != null) {
            // Update the restoration - remove old, add new
            queue.remove(existing);
        }

        PendingRestoration pending = new PendingRestoration(locationKey, snapshot, restoreTimeMs, retryCount);
        pendingByLocation.put(locationKey, pending);
        queue.offer(pending);
        totalQueued.incrementAndGet();

        // Persist to database (async)
        if (persistenceManager != null && snapshot.getLocation() != null) {
            persistenceManager.queueWrite(
                snapshot.getLocation().getWorld().getUID(),
                snapshot.getLocation().getBlockX(),
                snapshot.getLocation().getBlockY(),
                snapshot.getLocation().getBlockZ(),
                snapshot.getBlockData(),
                snapshot.hasTileEntity(),
                snapshot.getSerializedTileEntity(),  // Full NBT serialization for tile entities
                restoreTimeMs
            );
        }

        // Start processing if not running
        ensureProcessing();
    }

    /**
     * Cancels a pending restoration.
     */
    public void cancelRestoration(String locationKey) {
        PendingRestoration existing = pendingByLocation.remove(locationKey);
        if (existing != null) {
            queue.remove(existing);
        }
    }

    /**
     * Checks if a restoration is pending for a location.
     */
    public boolean hasPendingRestoration(String locationKey) {
        return pendingByLocation.containsKey(locationKey);
    }

    /**
     * Ensures the processing task is running.
     */
    private void ensureProcessing() {
        if (isRunning.compareAndSet(false, true)) {
            startProcessing();
        }
    }

    /**
     * Starts the processing task.
     */
    private void startProcessing() {
        processingTask = new BukkitRunnable() {
            @Override
            public void run() {
                processTick();
            }
        };
        processingTask.runTaskTimer(plugin, tickInterval, tickInterval);
    }

    /**
     * Processes one tick of restorations.
     */
    private void processTick() {
        long currentTime = System.currentTimeMillis();
        int processed = 0;

        while (processed < blocksPerTick) {
            PendingRestoration pending = queue.peek();
            
            // Nothing to process or not ready yet
            if (pending == null || pending.getRestoreTime() > currentTime) {
                break;
            }

            // Remove from queue
            queue.poll();
            pendingByLocation.remove(pending.getLocationKey());

            // Restore the block (must be on main thread)
            try {
                BlockSnapshot snapshot = pending.getSnapshot();
                snapshot.restore();

                // Verify that the block actually matches after restore
                if (!snapshot.matchesCurrentBlock()) {
                    long retryDelay = Math.min(RETRY_MAX_DELAY_MS, RETRY_BASE_DELAY_MS * (pending.getRetryCount() + 1));
                    long retryAt = System.currentTimeMillis() + retryDelay;
                    queueRestoration(pending.getLocationKey(), snapshot, retryAt, pending.getRetryCount() + 1);
                    long now = System.currentTimeMillis();
                    Long lastLog = lastVerifyLog.get(pending.getLocationKey());
                    if (lastLog == null || now - lastLog >= VERIFY_LOG_COOLDOWN_MS) {
                        lastVerifyLog.put(pending.getLocationKey(), now);
                    }
                    continue;
                }

                totalRestored.incrementAndGet();
                lastVerifyLog.remove(pending.getLocationKey());

                // Delete from database (async)
                if (persistenceManager != null && snapshot.getLocation() != null) {
                    persistenceManager.queueDelete(
                        snapshot.getLocation().getWorld().getUID(),
                        snapshot.getLocation().getBlockX(),
                        snapshot.getLocation().getBlockY(),
                        snapshot.getLocation().getBlockZ()
                    );
                }
                
                // Notify callback
                if (onRestorationComplete != null) {
                    onRestorationComplete.accept(pending.getLocationKey());
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to restore block at " + pending.getLocationKey() + ": " + e.getMessage());
            }

            processed++;
        }

        // Stop processing if queue is empty
        if (queue.isEmpty()) {
            stopProcessing();
        }
    }

    /**
     * Stops the processing task.
     */
    private void stopProcessing() {
        if (processingTask != null) {
            processingTask.cancel();
            processingTask = null;
        }
        isRunning.set(false);
    }

    /**
     * Gets the number of pending restorations.
     */
    public int getPendingCount() {
        return pendingByLocation.size();
    }

    /**
     * Gets the number of pending restorations for a specific world.
     */
    public int getPendingCount(String worldPrefix) {
        return (int) pendingByLocation.keySet().stream()
            .filter(k -> k.startsWith(worldPrefix))
            .count();
    }

    /**
     * Clears all pending restorations for a world.
     */
    public void clearWorld(String worldPrefix) {
        pendingByLocation.keySet().stream()
            .filter(k -> k.startsWith(worldPrefix))
            .toList()  // Collect to avoid concurrent modification
            .forEach(this::cancelRestoration);
    }

    /**
     * Shuts down the queue.
     */
    public void shutdown() {
        stopProcessing();
        queue.clear();
        pendingByLocation.clear();
    }

    /**
     * Gets statistics.
     */
    public String getStats() {
        int pending = pendingByLocation.size();
        int ready = countReadyToRestore();
        return String.format("%d pending (%d ready now), %d restored total, %d/tick",
            pending, ready, totalRestored.get(), blocksPerTick);
    }
    
    /**
     * Counts how many blocks are ready to restore right now.
     */
    public int countReadyToRestore() {
        long now = System.currentTimeMillis();
        int count = 0;
        for (PendingRestoration p : queue) {
            if (p.getRestoreTime() <= now) {
                count++;
            }
        }
        return count;
    }
    
    /**
     * Gets detailed stats for debugging.
     */
    public String getDetailedStats() {
        long now = System.currentTimeMillis();
        int pending = pendingByLocation.size();
        int ready = 0;
        long soonestMs = Long.MAX_VALUE;
        long latestMs = 0;
        
        for (PendingRestoration p : queue) {
            long timeUntil = p.getRestoreTime() - now;
            if (timeUntil <= 0) {
                ready++;
            } else {
                soonestMs = Math.min(soonestMs, timeUntil);
                latestMs = Math.max(latestMs, timeUntil);
            }
        }
        
        String timing = "";
        if (pending > ready && soonestMs != Long.MAX_VALUE) {
            timing = String.format(", next batch in %.1fs, last in %.1fs", 
                soonestMs / 1000.0, latestMs / 1000.0);
        }
        
        return String.format("%d pending, %d ready to restore now, %d/tick%s",
            pending, ready, blocksPerTick, timing);
    }

    /**
     * Represents a pending block restoration.
     */
    public static class PendingRestoration {
        private final String locationKey;
        private final BlockSnapshot snapshot;
        private final long restoreTime;
        private final int retryCount;

        public PendingRestoration(String locationKey, BlockSnapshot snapshot, long restoreTime, int retryCount) {
            this.locationKey = locationKey;
            this.snapshot = snapshot;
            this.restoreTime = restoreTime;
            this.retryCount = retryCount;
        }

        public String getLocationKey() {
            return locationKey;
        }

        public BlockSnapshot getSnapshot() {
            return snapshot;
        }

        public long getRestoreTime() {
            return restoreTime;
        }

        public int getRetryCount() {
            return retryCount;
        }
    }
}
