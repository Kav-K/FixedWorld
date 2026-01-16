package com.kaveenk.fixedWorld.listeners;

import com.kaveenk.fixedWorld.managers.WorldSnapshotManager;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

/**
 * Listens for explosion events (TNT, creepers, beds, etc.) and schedules restoration.
 */
public class ExplosionListener implements Listener {

    private final WorldSnapshotManager snapshotManager;

    public ExplosionListener(WorldSnapshotManager snapshotManager) {
        this.snapshotManager = snapshotManager;
    }

    /**
     * Handles entity-caused explosions (creepers, TNT entities, fireballs, etc.)
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            snapshotManager.captureAndScheduleRestore(block);
        }
    }

    /**
     * Handles block-caused explosions (beds in nether, respawn anchors, etc.)
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            snapshotManager.captureAndScheduleRestore(block);
        }
    }
}
