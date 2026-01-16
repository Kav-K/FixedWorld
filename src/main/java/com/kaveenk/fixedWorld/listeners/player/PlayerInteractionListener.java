package com.kaveenk.fixedWorld.listeners.player;

import com.kaveenk.fixedWorld.managers.WorldSnapshotManager;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockMultiPlaceEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;

/**
 * Listens for player interaction events that aren't covered by standard block events.
 * Handles buckets, bone meal, multi-block placements (beds, doors), sponges, etc.
 */
public class PlayerInteractionListener implements Listener {

    private final WorldSnapshotManager snapshotManager;

    public PlayerInteractionListener(WorldSnapshotManager snapshotManager) {
        this.snapshotManager = snapshotManager;
    }

    /**
     * Player emptying a bucket (placing water, lava, powder snow, etc.)
     * This creates a source block that needs to be captured.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block block = event.getBlock();
        // Capture the block that will be replaced by the liquid
        snapshotManager.captureAndScheduleRestore(block);
    }

    /**
     * Player filling a bucket (picking up water, lava, powder snow, etc.)
     * This removes a source block that needs to be captured.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Block block = event.getBlock();
        // Capture the liquid block before it's removed
        snapshotManager.captureAndScheduleRestore(block);
    }

    /**
     * Bone meal and similar fertilization effects.
     * Captures all blocks that will be changed (flowers, tall grass, etc.)
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFertilize(BlockFertilizeEvent event) {
        // Capture the original block being fertilized
        snapshotManager.captureAndScheduleRestore(event.getBlock());

        // Capture all blocks that will be affected (new plants, flowers, etc.)
        for (BlockState state : event.getBlocks()) {
            snapshotManager.captureAndScheduleRestore(state.getBlock());
        }
    }

    /**
     * Multi-block placements like beds, doors, and tall flowers.
     * BlockPlaceEvent only fires for the main block, this covers the others.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMultiBlockPlace(BlockMultiPlaceEvent event) {
        // Capture all replaced states for multi-block structures
        for (BlockState replacedState : event.getReplacedBlockStates()) {
            snapshotManager.captureAndScheduleRestore(replacedState);
        }
    }

    /**
     * Sponge absorbing water - captures all water blocks that will be removed.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpongeAbsorb(SpongeAbsorbEvent event) {
        // Capture the sponge itself (becomes wet sponge)
        snapshotManager.captureAndScheduleRestore(event.getBlock());

        // Capture all water blocks that will be absorbed
        for (BlockState state : event.getBlocks()) {
            snapshotManager.captureAndScheduleRestore(state.getBlock());
        }
    }
}
