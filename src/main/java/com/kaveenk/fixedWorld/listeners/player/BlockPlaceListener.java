package com.kaveenk.fixedWorld.listeners.player;

import com.kaveenk.fixedWorld.managers.WorldSnapshotManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Listens for block place events and schedules restoration.
 * This ensures placed blocks are also reverted (block returns to what was there before).
 */
public class BlockPlaceListener implements Listener {

    private final WorldSnapshotManager snapshotManager;

    public BlockPlaceListener(WorldSnapshotManager snapshotManager) {
        this.snapshotManager = snapshotManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        // Capture the replaced block state (usually air, but could be replaceable blocks like grass)
        // We use the BlockState directly since the block at that location has already changed
        snapshotManager.captureAndScheduleRestore(event.getBlockReplacedState());
    }
}
