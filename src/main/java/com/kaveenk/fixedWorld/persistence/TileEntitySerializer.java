package com.kaveenk.fixedWorld.persistence;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.Banner;
import org.bukkit.block.Beacon;
import org.bukkit.block.BlockState;
import org.bukkit.block.CommandBlock;
import org.bukkit.block.Container;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.block.Jukebox;
import org.bukkit.block.Lectern;
import org.bukkit.block.Sign;
import org.bukkit.block.Skull;
import org.bukkit.block.TileState;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Serializes and deserializes tile entity data (signs, chests, spawners, etc.)
 * to/from JSON for database persistence.
 * 
 * Supports:
 * - Signs (including 1.20+ front/back text)
 * - Containers (chests, hoppers, dispensers, droppers, barrels, shulker boxes)
 * - Spawners
 * - Skulls
 * - Banners
 * - Command blocks
 * - Beacons
 * - Lecterns
 * - Jukeboxes
 */
public class TileEntitySerializer {

    private static final Gson GSON = new GsonBuilder().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Object>>() {}.getType();

    /**
     * Serializes a tile entity to bytes (JSON encoded).
     * Returns null if the block state is not a tile entity or cannot be serialized.
     */
    public static byte[] serialize(BlockState blockState) {
        if (!(blockState instanceof TileState)) {
            return null;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("type", blockState.getType().name());

        try {
            // Sign serialization (1.20+ has front/back sides)
            if (blockState instanceof Sign sign) {
                serializeSign(sign, data);
            }

            // Container serialization (chests, hoppers, etc.)
            else if (blockState instanceof Container container) {
                serializeContainer(container, data);
            }

            // Spawner serialization
            else if (blockState instanceof CreatureSpawner spawner) {
                serializeSpawner(spawner, data);
            }

            // Skull serialization
            else if (blockState instanceof Skull skull) {
                serializeSkull(skull, data);
            }

            // Banner serialization
            else if (blockState instanceof Banner banner) {
                serializeBanner(banner, data);
            }

            // Command block serialization
            else if (blockState instanceof CommandBlock cmdBlock) {
                serializeCommandBlock(cmdBlock, data);
            }

            // Beacon serialization
            else if (blockState instanceof Beacon beacon) {
                serializeBeacon(beacon, data);
            }

            // Lectern serialization
            else if (blockState instanceof Lectern lectern) {
                serializeLectern(lectern, data);
            }

            // Jukebox serialization
            else if (blockState instanceof Jukebox jukebox) {
                serializeJukebox(jukebox, data);
            }

            // If no specific handler but is a tile entity, mark as generic
            else {
                data.put("generic", true);
            }

            return GSON.toJson(data).getBytes(StandardCharsets.UTF_8);

        } catch (Exception e) {
            // If serialization fails, return null
            return null;
        }
    }

    /**
     * Deserializes tile entity data and applies it to a block state.
     * The block state should be retrieved fresh from the world after setting block data.
     */
    public static void deserialize(BlockState blockState, byte[] nbtData) {
        if (nbtData == null || nbtData.length == 0) {
            return;
        }

        if (!(blockState instanceof TileState)) {
            return;
        }

        try {
            String json = new String(nbtData, StandardCharsets.UTF_8);
            Map<String, Object> data = GSON.fromJson(json, MAP_TYPE);

            if (data == null) {
                return;
            }

            // Sign deserialization
            if (blockState instanceof Sign sign) {
                deserializeSign(sign, data);
            }

            // Container deserialization
            else if (blockState instanceof Container container) {
                deserializeContainer(container, data);
            }

            // Spawner deserialization
            else if (blockState instanceof CreatureSpawner spawner) {
                deserializeSpawner(spawner, data);
            }

            // Skull deserialization
            else if (blockState instanceof Skull skull) {
                deserializeSkull(skull, data);
            }

            // Banner deserialization
            else if (blockState instanceof Banner banner) {
                deserializeBanner(banner, data);
            }

            // Command block deserialization
            else if (blockState instanceof CommandBlock cmdBlock) {
                deserializeCommandBlock(cmdBlock, data);
            }

            // Beacon deserialization
            else if (blockState instanceof Beacon beacon) {
                deserializeBeacon(beacon, data);
            }

            // Lectern deserialization
            else if (blockState instanceof Lectern lectern) {
                deserializeLectern(lectern, data);
            }

            // Jukebox deserialization
            else if (blockState instanceof Jukebox jukebox) {
                deserializeJukebox(jukebox, data);
            }

            // Update the block state in the world
            blockState.update(true, false);

        } catch (Exception e) {
            // If deserialization fails, just skip
        }
    }

    // ==================== SIGN ====================

    private static void serializeSign(Sign sign, Map<String, Object> data) {
        // 1.20+ signs have front and back sides
        Map<String, Object> frontData = serializeSignSide(sign.getSide(Side.FRONT));
        Map<String, Object> backData = serializeSignSide(sign.getSide(Side.BACK));
        
        data.put("front", frontData);
        data.put("back", backData);
        data.put("waxed", sign.isWaxed());
    }

    private static Map<String, Object> serializeSignSide(SignSide side) {
        Map<String, Object> sideData = new HashMap<>();
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            lines.add(side.getLine(i));
        }
        sideData.put("lines", lines);
        sideData.put("glowing", side.isGlowingText());
        if (side.getColor() != null) {
            sideData.put("color", side.getColor().name());
        }
        return sideData;
    }

    @SuppressWarnings("unchecked")
    private static void deserializeSign(Sign sign, Map<String, Object> data) {
        if (data.containsKey("front")) {
            Map<String, Object> frontData = (Map<String, Object>) data.get("front");
            deserializeSignSide(sign.getSide(Side.FRONT), frontData);
        }
        if (data.containsKey("back")) {
            Map<String, Object> backData = (Map<String, Object>) data.get("back");
            deserializeSignSide(sign.getSide(Side.BACK), backData);
        }
        if (data.containsKey("waxed")) {
            sign.setWaxed((Boolean) data.get("waxed"));
        }
    }

    @SuppressWarnings("unchecked")
    private static void deserializeSignSide(SignSide side, Map<String, Object> sideData) {
        if (sideData.containsKey("lines")) {
            List<String> lines = (List<String>) sideData.get("lines");
            for (int i = 0; i < Math.min(4, lines.size()); i++) {
                side.setLine(i, lines.get(i));
            }
        }
        if (sideData.containsKey("glowing")) {
            side.setGlowingText((Boolean) sideData.get("glowing"));
        }
        if (sideData.containsKey("color")) {
            try {
                side.setColor(DyeColor.valueOf((String) sideData.get("color")));
            } catch (Exception ignored) {}
        }
    }

    // ==================== CONTAINER ====================

    private static void serializeContainer(Container container, Map<String, Object> data) {
        Inventory inv = container.getInventory();
        List<Map<String, Object>> items = new ArrayList<>();
        
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item != null && item.getType() != Material.AIR) {
                Map<String, Object> itemData = new HashMap<>();
                itemData.put("slot", i);
                itemData.put("item", serializeItemStack(item));
                items.add(itemData);
            }
        }
        
        data.put("inventory", items);
        
        if (container.getCustomName() != null) {
            data.put("customName", container.getCustomName());
        }
    }

    @SuppressWarnings("unchecked")
    private static void deserializeContainer(Container container, Map<String, Object> data) {
        if (data.containsKey("inventory")) {
            List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("inventory");
            Inventory inv = container.getInventory();
            inv.clear();
            
            for (Map<String, Object> itemData : items) {
                int slot = ((Number) itemData.get("slot")).intValue();
                String itemB64 = (String) itemData.get("item");
                ItemStack item = deserializeItemStack(itemB64);
                if (item != null && slot >= 0 && slot < inv.getSize()) {
                    inv.setItem(slot, item);
                }
            }
        }
        
        if (data.containsKey("customName")) {
            container.setCustomName((String) data.get("customName"));
        }
    }

    // ==================== SPAWNER ====================

    private static void serializeSpawner(CreatureSpawner spawner, Map<String, Object> data) {
        if (spawner.getSpawnedType() != null) {
            data.put("spawnedType", spawner.getSpawnedType().name());
        }
        data.put("delay", spawner.getDelay());
        data.put("minSpawnDelay", spawner.getMinSpawnDelay());
        data.put("maxSpawnDelay", spawner.getMaxSpawnDelay());
        data.put("spawnCount", spawner.getSpawnCount());
        data.put("maxNearbyEntities", spawner.getMaxNearbyEntities());
        data.put("requiredPlayerRange", spawner.getRequiredPlayerRange());
        data.put("spawnRange", spawner.getSpawnRange());
    }

    private static void deserializeSpawner(CreatureSpawner spawner, Map<String, Object> data) {
        if (data.containsKey("spawnedType")) {
            try {
                spawner.setSpawnedType(EntityType.valueOf((String) data.get("spawnedType")));
            } catch (Exception ignored) {}
        }
        if (data.containsKey("delay")) {
            spawner.setDelay(((Number) data.get("delay")).intValue());
        }
        if (data.containsKey("minSpawnDelay")) {
            spawner.setMinSpawnDelay(((Number) data.get("minSpawnDelay")).intValue());
        }
        if (data.containsKey("maxSpawnDelay")) {
            spawner.setMaxSpawnDelay(((Number) data.get("maxSpawnDelay")).intValue());
        }
        if (data.containsKey("spawnCount")) {
            spawner.setSpawnCount(((Number) data.get("spawnCount")).intValue());
        }
        if (data.containsKey("maxNearbyEntities")) {
            spawner.setMaxNearbyEntities(((Number) data.get("maxNearbyEntities")).intValue());
        }
        if (data.containsKey("requiredPlayerRange")) {
            spawner.setRequiredPlayerRange(((Number) data.get("requiredPlayerRange")).intValue());
        }
        if (data.containsKey("spawnRange")) {
            spawner.setSpawnRange(((Number) data.get("spawnRange")).intValue());
        }
    }

    // ==================== SKULL ====================

    private static void serializeSkull(Skull skull, Map<String, Object> data) {
        if (skull.hasOwner()) {
            if (skull.getOwningPlayer() != null) {
                data.put("owner", skull.getOwningPlayer().getUniqueId().toString());
            }
        }
        // Skull rotation is now handled via BlockData, not the deprecated getRotation()
        // The BlockData is already captured separately
    }

    private static void deserializeSkull(Skull skull, Map<String, Object> data) {
        if (data.containsKey("owner")) {
            try {
                UUID ownerId = UUID.fromString((String) data.get("owner"));
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(ownerId));
            } catch (Exception ignored) {}
        }
        // Skull rotation is restored via BlockData, not the deprecated setRotation()
    }

    // ==================== BANNER ====================

    @SuppressWarnings("deprecation")
    private static void serializeBanner(Banner banner, Map<String, Object> data) {
        if (banner.getBaseColor() != null) {
            data.put("baseColor", banner.getBaseColor().name());
        }
        
        List<Map<String, String>> patterns = new ArrayList<>();
        for (Pattern pattern : banner.getPatterns()) {
            Map<String, String> patternData = new HashMap<>();
            patternData.put("color", pattern.getColor().name());
            patternData.put("pattern", pattern.getPattern().getIdentifier());
            patterns.add(patternData);
        }
        data.put("patterns", patterns);
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    private static void deserializeBanner(Banner banner, Map<String, Object> data) {
        if (data.containsKey("baseColor")) {
            try {
                banner.setBaseColor(DyeColor.valueOf((String) data.get("baseColor")));
            } catch (Exception ignored) {}
        }
        
        if (data.containsKey("patterns")) {
            List<Pattern> patterns = new ArrayList<>();
            List<Map<String, String>> patternsList = (List<Map<String, String>>) data.get("patterns");
            
            for (Map<String, String> patternData : patternsList) {
                try {
                    DyeColor color = DyeColor.valueOf(patternData.get("color"));
                    PatternType type = PatternType.getByIdentifier(patternData.get("pattern"));
                    if (type != null) {
                        patterns.add(new Pattern(color, type));
                    }
                } catch (Exception ignored) {}
            }
            
            banner.setPatterns(patterns);
        }
    }

    // ==================== COMMAND BLOCK ====================

    private static void serializeCommandBlock(CommandBlock cmdBlock, Map<String, Object> data) {
        data.put("command", cmdBlock.getCommand());
        data.put("name", cmdBlock.getName());
    }

    private static void deserializeCommandBlock(CommandBlock cmdBlock, Map<String, Object> data) {
        if (data.containsKey("command")) {
            cmdBlock.setCommand((String) data.get("command"));
        }
        if (data.containsKey("name")) {
            cmdBlock.setName((String) data.get("name"));
        }
    }

    // ==================== BEACON ====================

    @SuppressWarnings("deprecation")
    private static void serializeBeacon(Beacon beacon, Map<String, Object> data) {
        if (beacon.getPrimaryEffect() != null) {
            data.put("primaryEffect", beacon.getPrimaryEffect().getType().getName());
        }
        if (beacon.getSecondaryEffect() != null) {
            data.put("secondaryEffect", beacon.getSecondaryEffect().getType().getName());
        }
    }

    @SuppressWarnings("deprecation")
    private static void deserializeBeacon(Beacon beacon, Map<String, Object> data) {
        // Note: Beacon effects depend on pyramid structure, so we just try to set them
        // The actual effect may not apply if pyramid is not valid
        if (data.containsKey("primaryEffect")) {
            try {
                String effectName = (String) data.get("primaryEffect");
                PotionEffectType type = PotionEffectType.getByName(effectName);
                if (type != null) {
                    beacon.setPrimaryEffect(type);
                }
            } catch (Exception ignored) {}
        }
        if (data.containsKey("secondaryEffect")) {
            try {
                String effectName = (String) data.get("secondaryEffect");
                PotionEffectType type = PotionEffectType.getByName(effectName);
                if (type != null) {
                    beacon.setSecondaryEffect(type);
                }
            } catch (Exception ignored) {}
        }
    }

    // ==================== LECTERN ====================

    private static void serializeLectern(Lectern lectern, Map<String, Object> data) {
        data.put("page", lectern.getPage());
        if (lectern.getInventory().getItem(0) != null) {
            data.put("book", serializeItemStack(lectern.getInventory().getItem(0)));
        }
    }

    private static void deserializeLectern(Lectern lectern, Map<String, Object> data) {
        if (data.containsKey("book")) {
            ItemStack book = deserializeItemStack((String) data.get("book"));
            if (book != null) {
                lectern.getInventory().setItem(0, book);
            }
        }
        if (data.containsKey("page")) {
            lectern.setPage(((Number) data.get("page")).intValue());
        }
    }

    // ==================== JUKEBOX ====================

    private static void serializeJukebox(Jukebox jukebox, Map<String, Object> data) {
        if (jukebox.getRecord() != null && jukebox.getRecord().getType() != Material.AIR) {
            data.put("record", serializeItemStack(jukebox.getRecord()));
        }
    }

    private static void deserializeJukebox(Jukebox jukebox, Map<String, Object> data) {
        if (data.containsKey("record")) {
            ItemStack record = deserializeItemStack((String) data.get("record"));
            if (record != null) {
                jukebox.setRecord(record);
            }
        }
    }

    // ==================== ITEMSTACK SERIALIZATION ====================

    /**
     * Serializes an ItemStack to a Base64 string using Bukkit's serialization.
     */
    private static String serializeItemStack(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }
        
        try {
            // Use Bukkit's built-in YAML serialization and encode to Base64
            Map<String, Object> serialized = item.serialize();
            String json = GSON.toJson(serialized);
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Deserializes an ItemStack from a Base64 string.
     */
    private static ItemStack deserializeItemStack(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return null;
        }
        
        try {
            byte[] decoded = Base64.getDecoder().decode(base64);
            String json = new String(decoded, StandardCharsets.UTF_8);
            Map<String, Object> serialized = GSON.fromJson(json, MAP_TYPE);
            return ItemStack.deserialize(serialized);
        } catch (Exception e) {
            return null;
        }
    }
}
