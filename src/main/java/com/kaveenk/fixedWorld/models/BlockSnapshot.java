package com.kaveenk.fixedWorld.models;

import org.bukkit.Location;
import org.bukkit.Material;
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

        // Special handling for leaves
        if (dataToApply instanceof Leaves leaves) {
            // Check if this was a natural tree leaf (persistent=false) before we modify it
            // Natural tree leaves: persistent=false (will decay without logs)
            // Player-placed leaves: persistent=true (never decay)
            boolean wasNaturalTreeLeaf = !leaves.isPersistent();

            // Set persistent=true to prevent decay after restoration
            leaves.setPersistent(true);
            dataToApply = leaves;

            // Only clear fire for natural tree leaves
            // If player intentionally placed leaves near fire, respect their intent
            if (wasNaturalTreeLeaf && looksLikeTreeLeaf(block)) {
                clearFireAroundLeaf(block);
            }
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

    /**
     * Heuristic check to determine if a leaf block looks like it's part of a natural tree.
     * Checks for nearby logs and reasonable height from ground.
     *
     * @param block The leaf block location
     * @return true if this looks like a natural tree leaf
     */
    private boolean looksLikeTreeLeaf(Block block) {
        // Check 1: Is there a log within 6 blocks? (Minecraft's natural decay distance)
        if (hasNearbyLog(block, 6)) {
            return true;
        }

        // Check 2: Is the leaf at a reasonable tree height? (within 20 blocks of ground)
        // Natural trees rarely exceed 20 blocks in height
        if (isNearGround(block, 20)) {
            return true;
        }

        // If neither condition is met, this might be a floating player structure
        return false;
    }

    /**
     * Checks if there's a log block within the specified distance.
     * Uses a quick check of likely log positions (below and to sides) for efficiency.
     */
    private boolean hasNearbyLog(Block center, int radius) {
        // Quick check: logs are usually below or directly adjacent to leaves
        // Check immediate neighbors first (most likely positions)
        Block[] immediateChecks = {
            center.getRelative(0, -1, 0),  // Below (most common)
            center.getRelative(0, -2, 0),  // 2 below
            center.getRelative(1, 0, 0),
            center.getRelative(-1, 0, 0),
            center.getRelative(0, 0, 1),
            center.getRelative(0, 0, -1),
            center.getRelative(1, -1, 0),
            center.getRelative(-1, -1, 0),
            center.getRelative(0, -1, 1),
            center.getRelative(0, -1, -1),
        };

        for (Block check : immediateChecks) {
            if (isLogBlock(check)) {
                return true;
            }
        }

        // If not found in immediate area, do a broader but still limited search
        // Only check a sparse sample of positions to keep it O(1)-ish
        int[] offsets = {-3, 0, 3};
        for (int x : offsets) {
            for (int y : offsets) {
                for (int z : offsets) {
                    if (x == 0 && y == 0 && z == 0) continue;
                    Block check = center.getRelative(x, y, z);
                    if (isLogBlock(check)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Checks if there's solid ground within the specified distance below.
     */
    private boolean isNearGround(Block block, int maxDistance) {
        Block check = block;
        for (int i = 0; i < maxDistance; i++) {
            check = check.getRelative(0, -1, 0);
            if (check.getY() < block.getWorld().getMinHeight()) {
                return true; // Near world bottom, counts as ground
            }
            if (check.getType().isSolid() && !isLeafBlock(check)) {
                return true; // Found solid ground (not leaves)
            }
        }
        return false;
    }

    /**
     * Checks if a block is a log (any type).
     */
    private boolean isLogBlock(Block block) {
        String typeName = block.getType().name();
        return typeName.endsWith("_LOG") || typeName.endsWith("_WOOD") || 
               typeName.equals("CRIMSON_STEM") || typeName.equals("WARPED_STEM");
    }

    /**
     * Checks if a block is a leaf (any type).
     */
    private boolean isLeafBlock(Block block) {
        return block.getType().name().endsWith("_LEAVES");
    }

    /**
     * Clears fire from the block location and all 6 adjacent faces.
     * Called when restoring leaf blocks to ensure they don't immediately catch fire.
     */
    private void clearFireAroundLeaf(Block block) {
        // Clear fire at the block location itself (in case fire replaced the leaf)
        if (isFireBlock(block)) {
            block.setType(Material.AIR, false);
        }

        // Clear fire on all 6 faces
        Block[] adjacentBlocks = {
            block.getRelative(1, 0, 0),
            block.getRelative(-1, 0, 0),
            block.getRelative(0, 1, 0),
            block.getRelative(0, -1, 0),
            block.getRelative(0, 0, 1),
            block.getRelative(0, 0, -1)
        };

        for (Block adjacent : adjacentBlocks) {
            if (isFireBlock(adjacent)) {
                adjacent.setType(Material.AIR, false);
            }
        }
    }

    /**
     * Checks if a block is fire (regular or soul fire).
     */
    private boolean isFireBlock(Block block) {
        Material type = block.getType();
        return type == Material.FIRE || type == Material.SOUL_FIRE;
    }

    @Override
    public String toString() {
        return "BlockSnapshot{" +
                "location=" + location +
                ", blockData=" + blockData +
                '}';
    }
}
