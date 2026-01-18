package com.kaveenk.fixedWorld.models;

import com.kaveenk.fixedWorld.persistence.TileEntitySerializer;
import com.kaveenk.fixedWorld.utils.BlockUtils;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;

/**
 * Immutable snapshot of a block's state at a point in time.
 * Supports both in-memory tile entity references and serialized NBT data for persistence.
 */
public class BlockSnapshot {

    private final Location location;
    private final BlockData blockData;
    private final BlockState blockState;
    private final boolean hasTileEntity;
    
    // Serialized tile entity data for database persistence
    // Lazy-computed when needed, cached after first access
    private byte[] serializedTileEntity;
    private boolean serializedComputed = false;

    public BlockSnapshot(Block block) {
        this.location = block.getLocation().clone();
        this.blockData = block.getBlockData().clone();
        this.blockState = block.getState();
        this.hasTileEntity = blockState instanceof TileState;
    }

    public BlockSnapshot(BlockState state) {
        this.location = state.getLocation().clone();
        this.blockData = state.getBlockData().clone();
        this.blockState = state;
        this.hasTileEntity = state instanceof TileState;
    }

    /**
     * Create a snapshot from baseline data (used by ChunkScanner).
     * Note: ChunkSnapshot doesn't store tile entity data, so we can only restore basic block data.
     *
     * @param location The block location
     * @param baselineData The BlockData from the chunk snapshot
     */
    public BlockSnapshot(Location location, BlockData baselineData) {
        this.location = location.clone();
        this.blockData = baselineData.clone();
        this.blockState = null;  // No tile entity data available from ChunkSnapshot
        this.hasTileEntity = false;
    }

    /**
     * Create a snapshot from database-persisted data.
     * Used when loading pending restorations after server restart.
     *
     * @param location The block location
     * @param blockData The BlockData to restore
     * @param serializedTileEntity Serialized tile entity data (may be null)
     */
    public BlockSnapshot(Location location, BlockData blockData, byte[] serializedTileEntity) {
        this.location = location.clone();
        this.blockData = blockData.clone();
        this.blockState = null;  // Will use serialized data instead
        this.hasTileEntity = serializedTileEntity != null && serializedTileEntity.length > 0;
        this.serializedTileEntity = serializedTileEntity;
        this.serializedComputed = true;  // Already have the serialized data
    }

    public Location getLocation() {
        return location.clone();
    }

    public BlockData getBlockData() {
        return blockData.clone();
    }

    public boolean hasTileEntity() {
        return hasTileEntity;
    }

    /**
     * Gets the serialized tile entity data for database persistence.
     * Computed lazily and cached for efficiency.
     * 
     * @return Serialized tile entity data as bytes (JSON), or null if no tile entity
     */
    public byte[] getSerializedTileEntity() {
        if (serializedComputed) {
            return serializedTileEntity;
        }
        
        // Compute serialization
        if (blockState instanceof TileState) {
            serializedTileEntity = TileEntitySerializer.serialize(blockState);
        } else {
            serializedTileEntity = null;
        }
        serializedComputed = true;
        return serializedTileEntity;
    }

    /**
     * Restores the block to this snapshot's state.
     */
    public void restore() {
        Block block = location.getBlock();
        BlockData dataToApply = blockData.clone();

        // Handle leaves specially
        if (dataToApply instanceof Leaves leaves) {
            boolean wasNaturalLeaf = !leaves.isPersistent();
            leaves.setPersistent(true);
            dataToApply = leaves;

            // Clear fire around natural tree leaves
            if (wasNaturalLeaf && looksLikeNaturalTreeLeaf(block)) {
                clearFireAround(block);
            }
        }

        // Set the block data first
        block.setBlockData(dataToApply, false);

        // Restore tile entity data
        if (hasTileEntity) {
            if (blockState != null) {
                // We have a live BlockState reference - use it directly
                blockState.update(true, false);
            } else if (serializedTileEntity != null && serializedTileEntity.length > 0) {
                // We have serialized tile entity data from database - deserialize and apply
                BlockState freshState = block.getState();
                TileEntitySerializer.deserialize(freshState, serializedTileEntity);
            }
        }
    }

    /**
     * Heuristic to determine if this looks like a natural tree leaf.
     */
    private boolean looksLikeNaturalTreeLeaf(Block block) {
        // Check for nearby logs (most reliable indicator)
        if (hasNearbyLog(block)) return true;
        // Check if near ground (natural trees are grounded)
        return isNearGround(block, 20);
    }

    private boolean hasNearbyLog(Block center) {
        // Quick check of likely log positions
        int[][] offsets = {
            {0, -1, 0}, {0, -2, 0},  // Below
            {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},  // Adjacent
            {1, -1, 0}, {-1, -1, 0}, {0, -1, 1}, {0, -1, -1},  // Diagonal below
        };
        for (int[] o : offsets) {
            if (BlockUtils.isLog(center.getRelative(o[0], o[1], o[2]))) return true;
        }
        // Sparse broader check
        int[] sparse = {-3, 0, 3};
        for (int x : sparse) {
            for (int y : sparse) {
                for (int z : sparse) {
                    if ((x | y | z) != 0 && BlockUtils.isLog(center.getRelative(x, y, z))) return true;
                }
            }
        }
        return false;
    }

    private boolean isNearGround(Block block, int maxDistance) {
        Block check = block;
        for (int i = 0; i < maxDistance; i++) {
            check = check.getRelative(0, -1, 0);
            if (check.getY() < block.getWorld().getMinHeight()) return true;
            if (check.getType().isSolid() && !BlockUtils.isLeaf(check)) return true;
        }
        return false;
    }

    /**
     * Clears fire within a 3-block radius of this block.
     */
    private void clearFireAround(Block block) {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockUtils.clearFireIfPresent(block.getRelative(dx, dy, dz));
                }
            }
        }
    }

    @Override
    public String toString() {
        return "BlockSnapshot{location=" + location + ", blockData=" + blockData + '}';
    }
}
