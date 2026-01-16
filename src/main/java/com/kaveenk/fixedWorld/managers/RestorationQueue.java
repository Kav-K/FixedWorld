package com.kaveenk.fixedWorld.managers;

import com.kaveenk.fixedWorld.FixedWorld;
import com.kaveenk.fixedWorld.models.BlockSnapshot;
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
 * Designed to be async-ready for future database persistence.
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
    
    // Callback for when a block is restored
    private Consumer<String> onRestorationComplete;
    
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

    /**
     * Queues a block for restoration at a specific time.
     * If the block is already queued, updates the restoration time.
     *
     * @param locationKey The block's location key
     * @param snapshot The snapshot to restore
     * @param restoreTimeMs When to restore (System.currentTimeMillis())
     */
    public void queueRestoration(String locationKey, BlockSnapshot snapshot, long restoreTimeMs) {
        // Check if already queued
        PendingRestoration existing = pendingByLocation.get(locationKey);
        if (existing != null) {
            // Update the restoration - remove old, add new
            queue.remove(existing);
        }

        PendingRestoration pending = new PendingRestoration(locationKey, snapshot, restoreTimeMs);
        pendingByLocation.put(locationKey, pending);
        queue.offer(pending);
        totalQueued.incrementAndGet();

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
                pending.getSnapshot().restore();
                totalRestored.incrementAndGet();
                
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
        return String.format("Queued: %d, Restored: %d, Pending: %d",
            totalQueued.get(), totalRestored.get(), pendingByLocation.size());
    }

    /**
     * Represents a pending block restoration.
     */
    public static class PendingRestoration {
        private final String locationKey;
        private final BlockSnapshot snapshot;
        private final long restoreTime;

        public PendingRestoration(String locationKey, BlockSnapshot snapshot, long restoreTime) {
            this.locationKey = locationKey;
            this.snapshot = snapshot;
            this.restoreTime = restoreTime;
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
    }
}
