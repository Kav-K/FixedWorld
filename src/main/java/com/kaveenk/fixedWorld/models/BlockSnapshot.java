package com.kaveenk.fixedWorld.models;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;

/**
 * Immutable snapshot of a block's state at a point in time.
 * Captures material, block data (rotation, waterlogged, etc.), and tile entity data (signs, chests, etc.).
 */
public class BlockSnapshot {

    private final Location location;
    private final BlockData blockData;
    private final BlockState blockState;
    private final boolean hasTileEntity;

    public BlockSnapshot(Block block) {
        this.location = block.getLocation().clone();
        this.blockData = block.getBlockData().clone();
        // getState() creates a copy of the block state including tile entity data
        this.blockState = block.getState();
        // Check if this is a tile entity (signs, chests, banners, etc.)
        this.hasTileEntity = blockState instanceof TileState;
    }

    /**
     * Create a snapshot from an existing BlockState (useful for replaced states).
     */
    public BlockSnapshot(BlockState state) {
        this.location = state.getLocation().clone();
        this.blockData = state.getBlockData().clone();
        this.blockState = state;
        this.hasTileEntity = state instanceof TileState;
    }

    public Location getLocation() {
        return location.clone();
    }

    public BlockData getBlockData() {
        return blockData.clone();
    }

    /**
     * Restores the block to this snapshot's state.
     * Uses BlockState.update() only for tile entities to avoid issues with regular blocks.
     * Handles leaves specially by setting them to persistent to prevent decay.
     */
    public void restore() {
        Block block = location.getBlock();

        // Clone the block data so we can modify it if needed
        BlockData dataToApply = blockData.clone();

        // Special handling for leaves: set persistent=true to prevent decay after restoration
        // This is necessary because Minecraft uses random ticks to decay leaves,
        // and even with event suppression, the leaf will eventually check distance to logs
        if (dataToApply instanceof Leaves leaves) {
            leaves.setPersistent(true);
            dataToApply = leaves;
        }

        // Set the block data (material + properties) without triggering physics
        block.setBlockData(dataToApply, false);

        // Only use blockState.update() for tile entities (signs, chests, banners, etc.)
        // For regular blocks, setBlockData is sufficient and avoids potential issues
        if (hasTileEntity) {
            // Force update (true) without physics (false)
            blockState.update(true, false);
        }
    }

    @Override
    public String toString() {
        return "BlockSnapshot{" +
                "location=" + location +
                ", blockData=" + blockData +
                '}';
    }
}
