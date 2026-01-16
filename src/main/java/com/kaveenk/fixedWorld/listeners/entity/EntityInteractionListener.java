package com.kaveenk.fixedWorld.listeners.entity;

import com.kaveenk.fixedWorld.managers.WorldSnapshotManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;

/**
 * Listens for entity-caused block changes.
 * Covers endermen picking up/placing blocks, falling sand/gravel, silverfish, etc.
 */
public class EntityInteractionListener implements Listener {

    private final WorldSnapshotManager snapshotManager;

    public EntityInteractionListener(WorldSnapshotManager snapshotManager) {
        this.snapshotManager = snapshotManager;
    }

    /**
     * Entity changing a block (endermen, falling blocks, silverfish, etc.)
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        snapshotManager.captureAndScheduleRestore(event.getBlock());
    }
}
