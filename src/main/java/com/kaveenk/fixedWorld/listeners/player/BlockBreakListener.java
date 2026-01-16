package com.kaveenk.fixedWorld.listeners.player;

import com.kaveenk.fixedWorld.managers.WorldSnapshotManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * Listens for block break events and schedules restoration.
 */
public class BlockBreakListener implements Listener {

    private final WorldSnapshotManager snapshotManager;

    public BlockBreakListener(WorldSnapshotManager snapshotManager) {
        this.snapshotManager = snapshotManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        // Capture the block state before it's broken and schedule restoration
        snapshotManager.captureAndScheduleRestore(event.getBlock());
    }
}
