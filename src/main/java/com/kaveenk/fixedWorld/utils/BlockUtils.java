package com.kaveenk.fixedWorld.utils;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

/**
 * Shared utility methods for block operations.
 */
public final class BlockUtils {

    private static final BlockFace[] ADJACENT_FACES = {
        BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST
    };

    private BlockUtils() {} // Utility class

    /**
     * Gets the 6 blocks adjacent to the given block (one for each face).
     */
    public static Block[] getAdjacentBlocks(Block block) {
        Block[] adjacent = new Block[6];
        for (int i = 0; i < ADJACENT_FACES.length; i++) {
            adjacent[i] = block.getRelative(ADJACENT_FACES[i]);
        }
        return adjacent;
    }

    /**
     * Checks if a block is fire (regular or soul fire).
     */
    public static boolean isFire(Block block) {
        Material type = block.getType();
        return type == Material.FIRE || type == Material.SOUL_FIRE;
    }

    /**
     * Checks if a block is a log (any type, including stems).
     */
    public static boolean isLog(Block block) {
        return Tag.LOGS.isTagged(block.getType());
    }

    /**
     * Checks if a block is a leaf (any type).
     */
    public static boolean isLeaf(Block block) {
        return Tag.LEAVES.isTagged(block.getType());
    }

    /**
     * Clears fire from a block if present, without triggering physics.
     * @return true if fire was cleared
     */
    public static boolean clearFireIfPresent(Block block) {
        if (isFire(block)) {
            block.setType(Material.AIR, false);
            return true;
        }
        return false;
    }
}
