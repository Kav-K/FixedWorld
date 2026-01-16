package com.kaveenk.fixedWorld.listeners.world;

import com.kaveenk.fixedWorld.managers.WorldSnapshotManager;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.List;

/**
 * Handles all explosion events (TNT, creepers, beds, respawn anchors, etc.)
 */
public class ExplosionListener implements Listener {

    private final WorldSnapshotManager snapshotManager;

    public ExplosionListener(WorldSnapshotManager snapshotManager) {
        this.snapshotManager = snapshotManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        captureBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        captureBlocks(event.blockList());
    }

    private void captureBlocks(List<Block> blocks) {
        blocks.forEach(snapshotManager::captureAndScheduleRestore);
    }
}
