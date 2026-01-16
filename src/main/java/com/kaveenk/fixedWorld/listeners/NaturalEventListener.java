package com.kaveenk.fixedWorld.listeners;

import com.kaveenk.fixedWorld.managers.WorldSnapshotManager;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.block.BlockState;

/**
 * Listens for natural/environmental block changes and schedules restoration.
 * All natural events are captured and will restore after the configured delay.
 */
public class NaturalEventListener implements Listener {

    private final WorldSnapshotManager snapshotManager;

    public NaturalEventListener(WorldSnapshotManager snapshotManager) {
        this.snapshotManager = snapshotManager;
    }

    /**
     * Block burned by fire - capture and restore.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        snapshotManager.captureAndScheduleRestore(event.getBlock());
    }

    /**
     * Fire/grass/mycelium/etc spreading to new blocks - capture the destination.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        snapshotManager.captureAndScheduleRestore(event.getBlock());
    }

    /**
     * Fire being placed/ignited - capture and restore.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        snapshotManager.captureAndScheduleRestore(event.getBlock());
    }

    /**
     * Water/lava flowing - capture the destination block.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        snapshotManager.captureAndScheduleRestore(event.getToBlock());
    }

    /**
     * Block fading (ice melting, snow melting, coral dying, etc.)
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        snapshotManager.captureAndScheduleRestore(event.getBlock());
    }

    /**
     * Block forming (snow, ice, concrete, etc.)
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        snapshotManager.captureAndScheduleRestore(event.getBlock());
    }

    /**
     * Leaves decaying - capture the leaf block before it decays.
     * The restored leaf will be set to persistent=true to prevent immediate re-decay.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        snapshotManager.captureAndScheduleRestore(event.getBlock());
    }

    /**
     * Crops/plants growing - capture the block before growth.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        snapshotManager.captureAndScheduleRestore(event.getBlock());
    }

    /**
     * Piston extending - capture blocks being pushed.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        // Capture the piston itself
        snapshotManager.captureAndScheduleRestore(event.getBlock());
        // Capture all blocks being pushed
        for (Block block : event.getBlocks()) {
            snapshotManager.captureAndScheduleRestore(block);
            // Also capture where the block is moving to
            Block destination = block.getRelative(event.getDirection());
            snapshotManager.captureAndScheduleRestore(destination);
        }
    }

    /**
     * Piston retracting - capture blocks being pulled.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        // Capture the piston itself
        snapshotManager.captureAndScheduleRestore(event.getBlock());
        // Capture all blocks being pulled (sticky pistons)
        for (Block block : event.getBlocks()) {
            snapshotManager.captureAndScheduleRestore(block);
        }
    }

    /**
     * Structure growing (saplings into trees, mushrooms, etc.)
     * Captures all blocks that will be changed by the growth.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        // Capture all blocks that will be replaced by the structure
        for (BlockState state : event.getBlocks()) {
            snapshotManager.captureAndScheduleRestore(state.getBlock());
        }
    }
}
