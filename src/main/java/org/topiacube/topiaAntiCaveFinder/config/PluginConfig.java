package org.topiacube.topiaAntiCaveFinder.config;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PluginConfig {

    private final JavaPlugin plugin;
    private int checkIntervalTicks;
    private double maxRevealDistance;
    private double minRevealDistance;
    private double revealFovHalfAngleCos;
    private double revealFovAngle;
    private int chunkRadius;
    private int maxBlocksPerPlayer;
    private double interactionRevealRadius;
    private int interactionRevealDurationTicks;
    private double interiorRevealRadius;
    private double maskActivationRadius;
    private int maskingMode;
    private Set<String> excludedWorlds = new HashSet<>();
    private Set<Material> maskableMaterials = EnumSet.noneOf(Material.class);
    private Set<EntityType> maskableEntities = EnumSet.noneOf(EntityType.class);
    private WeightedMask customDefaultMask;
    private final Map<String, WeightedMask> customWorldMasks = new HashMap<>();

    public PluginConfig(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void reload(FileConfiguration config) {
        this.checkIntervalTicks = Math.max(1, config.getInt("check-interval-ticks", 10));
        this.maxRevealDistance = Math.max(1.0, config.getDouble("max-reveal-distance", 32.0));
        this.minRevealDistance = Math.max(0.0, config.getDouble("min-reveal-distance", 1.8));

        this.revealFovAngle = clamp(config.getDouble("reveal-fov-angle-degrees", 80.0));
        double halfAngleRadians = Math.toRadians(this.revealFovAngle / 2.0);
        this.revealFovHalfAngleCos = Math.cos(halfAngleRadians);

        this.chunkRadius = Math.max(1, config.getInt("chunk-radius", 3));
        this.maxBlocksPerPlayer = Math.max(1, config.getInt("max-blocks-per-player", 1024));
        this.interactionRevealRadius = Math.max(0.0, config.getDouble("interaction-reveal-radius", 6.0));
        this.interactionRevealDurationTicks = Math.max(0, config.getInt("interaction-reveal-duration-ticks", 200));
        this.interiorRevealRadius = Math.max(0.0, config.getDouble("interior-reveal-radius", 8.0));
        this.maskActivationRadius = Math.max(1.0, config.getDouble("mask-activation-radius", 16.0));

        this.maskingMode = Math.max(1, Math.min(2, config.getInt("masking-mode", 1)));
        this.excludedWorlds = loadExcludedWorlds(config.getStringList("excluded-worlds"));
        this.maskableMaterials = loadMaterialList(config.getStringList("maskable-materials"), Collections.singletonList("STONE"));
        this.maskableEntities = loadEntityList(config.getStringList("maskable-entities"), defaultEntityList());
        loadCustomMasks(config.getConfigurationSection("custom-mask-mappings"));
    }

    private double clamp(double value) {
        return Math.max(10.0, Math.min(180.0, value));
    }

    private Set<Material> loadMaterialList(List<String> rawList, List<String> fallback) {
        List<String> source = rawList == null || rawList.isEmpty() ? fallback : rawList;
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (String raw : source) {
            Material material = Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
            if (material != null) {
                result.add(material);
            } else {
                plugin.getLogger().warning("Unknown material in config: " + raw);
            }
        }
        return result;
    }

    private Set<String> loadExcludedWorlds(List<String> rawList) {
        Set<String> result = new HashSet<>();
        if (rawList == null) {
            return result;
        }
        for (String raw : rawList) {
            if (raw == null || raw.trim().isEmpty()) {
                continue;
            }
            result.add(raw.toLowerCase(Locale.ROOT));
        }
        return result;
    }

    private void loadCustomMasks(ConfigurationSection section) {
        customDefaultMask = null;
        customWorldMasks.clear();
        if (section == null) {
            return;
        }
        for (String worldKey : section.getKeys(false)) {
            Object rawValue = section.get(worldKey);
            WeightedMask palette = parseWeightedMask(rawValue, "custom-mask-mappings." + worldKey);
            if (palette == null) {
                continue;
            }
            if (worldKey.equalsIgnoreCase("default")) {
                customDefaultMask = palette;
            } else {
                customWorldMasks.put(worldKey.toLowerCase(Locale.ROOT), palette);
            }
        }
    }

    private BlockData parseBlockData(String raw, String context) {
        try {
            Material material = Material.matchMaterial(raw.toUpperCase(Locale.ROOT));
            if (material != null) {
                return normalizeAir(Bukkit.createBlockData(material));
            }
        } catch (IllegalArgumentException ignored) {
            // try full block data string below
        }
        try {
            return normalizeAir(Bukkit.createBlockData(raw));
        } catch (IllegalArgumentException ex) {
            plugin.getLogger().warning("Invalid block data in config (" + context + "): " + raw);
            return null;
        }
    }

    private BlockData normalizeAir(BlockData data) {
        if (data == null) {
            return null;
        }
        Material type = data.getMaterial();
        if (type == Material.CAVE_AIR || type == Material.VOID_AIR) {
            try {
                return Bukkit.createBlockData(Material.AIR);
            } catch (IllegalArgumentException ignored) {
                return data;
            }
        }
        return data;
    }

    private WeightedMask parseWeightedMask(Object rawValue, String context) {
        Map<BlockData, Integer> weights = new LinkedHashMap<>();
        if (rawValue instanceof ConfigurationSection worldSection) {
            for (String blockKey : worldSection.getKeys(false)) {
                Object weightObj = worldSection.get(blockKey);
                int weight = extractWeight(weightObj);
                if (weight <= 0) {
                    plugin.getLogger().warning("Invalid weight for " + context + "." + blockKey + ": " + weightObj);
                    continue;
                }
                BlockData data = parseBlockData(blockKey, context + "." + blockKey);
                if (data != null) {
                    weights.merge(data, weight, Integer::sum);
                }
            }
        } else if (rawValue instanceof List<?> list) {
            for (Object entry : list) {
                if (entry == null) {
                    continue;
                }
                BlockData data = parseBlockData(String.valueOf(entry), context);
                if (data != null) {
                    weights.merge(data, 1, Integer::sum);
                }
            }
        } else if (rawValue instanceof String str) {
            BlockData data = parseBlockData(str, context);
            if (data != null) {
                weights.merge(data, 1, Integer::sum);
            }
        } else if (rawValue != null) {
            plugin.getLogger().warning("Unsupported custom mask entry at " + context + ": " + rawValue);
        }
        WeightedMask mask = WeightedMask.from(weights);
        if (mask == null) {
            plugin.getLogger().warning("No valid block data entries for " + context);
        }
        return mask;
    }

    private int extractWeight(Object raw) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String str) {
            try {
                return Integer.parseInt(str.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 0;
    }

    public int getCheckIntervalTicks() {
        return checkIntervalTicks;
    }

    public double getMaxRevealDistance() {
        return maxRevealDistance;
    }

    public double getMinRevealDistance() {
        return minRevealDistance;
    }

    public double getRevealFovAngle() {
        return revealFovAngle;
    }

    public double getRevealFovHalfAngleCos() {
        return revealFovHalfAngleCos;
    }

    public int getChunkRadius() {
        return chunkRadius;
    }

    public int getMaxBlocksPerPlayer() {
        return maxBlocksPerPlayer;
    }

    public double getInteractionRevealRadius() {
        return interactionRevealRadius;
    }

    public boolean isMaskable(Material material) {
        return maskableMaterials.contains(material);
    }

    public Set<Material> getMaskableMaterials() {
        return Collections.unmodifiableSet(maskableMaterials);
    }

    public boolean isMaskableEntity(EntityType type) {
        return maskableEntities.contains(type);
    }

    public Set<EntityType> getMaskableEntities() {
        return Collections.unmodifiableSet(maskableEntities);
    }

    public double getInteriorRevealRadius() {
        return interiorRevealRadius;
    }

    public double getMaskActivationRadius() {
        return maskActivationRadius;
    }

    public int getInteractionRevealDurationTicks() {
        return interactionRevealDurationTicks;
    }

    public int getMaskingMode() {
        return maskingMode;
    }

    public boolean isCustomMaskingEnabled() {
        return maskingMode == 2;
    }

    public boolean isWorldExcluded(String worldName) {
        if (worldName == null) {
            return false;
        }
        return excludedWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }

    public boolean shouldTrackBlock(World world, Material material) {
        if (world == null || material == null) {
            return false;
        }
        if (isWorldExcluded(world.getName())) {
            return false;
        }
        if (isCustomMaskingEnabled()) {
            if (material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR || material == Material.BEDROCK) {
                return false;
            }
            return findCustomMaskPalette(world.getName()) != null;
        }
        return maskableMaterials.contains(material);
    }

    public BlockData selectCustomMask(Block block) {
        if (block == null) {
            return selectCustomMask((World) null, 0, 0, 0);
        }
        return selectCustomMask(block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    public BlockData selectCustomMask(World world, int x, int y, int z) {
        if (!isCustomMaskingEnabled()) {
            return null;
        }
        WeightedMask palette = findCustomMaskPalette(world != null ? world.getName() : null);
        if (palette == null) {
            return null;
        }
        long mixed = mixCoordinates(world, x, y, z);
        BlockData data = palette.select(mixed);
        return data != null ? data.clone() : null;
    }

    private WeightedMask findCustomMaskPalette(String worldName) {
        if (worldName != null) {
            WeightedMask palette = customWorldMasks.get(worldName.toLowerCase(Locale.ROOT));
            if (palette != null) {
                return palette;
            }
        }
        return customDefaultMask;
    }

    private long mixCoordinates(World world, int x, int y, int z) {
        long seed = 0x9E3779B97F4A7C15L;
        if (world != null) {
            long worldSeed = world.getSeed();
            if (worldSeed == 0L) {
                worldSeed = hashString(world.getName());
            }
            seed ^= mix64(worldSeed);
        }
        long combined = seed
            ^ ((long) x * 0xC2B2AE3D27D4EB4FL)
            ^ ((long) y * 0x165667B19E3779F9L)
            ^ ((long) z * 0x9E3779B97F4A7C15L);
        return mix64(combined);
    }

    private long hashString(String input) {
        if (input == null) {
            return 0L;
        }
        long h = 1125899906842597L; // FNV-like
        for (int i = 0; i < input.length(); i++) {
            h ^= input.charAt(i);
            h *= 1315423911L;
        }
        return mix64(h);
    }

    private long mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return z ^ (z >>> 33);
    }

    private Set<EntityType> loadEntityList(List<String> rawList, List<String> fallback) {
        List<String> source = rawList == null || rawList.isEmpty() ? fallback : rawList;
        Set<EntityType> result = EnumSet.noneOf(EntityType.class);
        for (String raw : source) {
            try {
                EntityType type = EntityType.valueOf(raw.toUpperCase(Locale.ROOT));
                result.add(type);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Unknown maskable entity in config: " + raw);
            }
        }
        return result;
    }

    private List<String> defaultEntityList() {
        List<String> defaults = new ArrayList<>();
        Collections.addAll(defaults,
            "ARMOR_STAND",
            "ITEM_FRAME",
            "GLOW_ITEM_FRAME",
            "PAINTING",
            "CHEST_MINECART",
            "HOPPER_MINECART"
        );
        return defaults;
    }

    private static final class WeightedMask {
        private final List<BlockData> entries;
        private final int[] cumulative;
        private final int totalWeight;

        private WeightedMask(List<BlockData> entries, int[] cumulative, int totalWeight) {
            this.entries = entries;
            this.cumulative = cumulative;
            this.totalWeight = totalWeight;
        }

        static WeightedMask from(Map<BlockData, Integer> weights) {
            if (weights == null || weights.isEmpty()) {
                return null;
            }
            List<BlockData> entries = new ArrayList<>();
            List<Integer> cumulativeList = new ArrayList<>();
            int total = 0;
            for (Map.Entry<BlockData, Integer> entry : weights.entrySet()) {
                int weight = entry.getValue();
                if (weight <= 0) {
                    continue;
                }
                total += weight;
                entries.add(entry.getKey());
                cumulativeList.add(total);
            }
            if (entries.isEmpty() || total <= 0) {
                return null;
            }
            int[] cumulative = new int[cumulativeList.size()];
            for (int i = 0; i < cumulative.length; i++) {
                cumulative[i] = cumulativeList.get(i);
            }
            return new WeightedMask(entries, cumulative, total);
        }

        BlockData select(long hash) {
            if (entries.isEmpty() || totalWeight <= 0) {
                return null;
            }
            int value = (int) Math.floorMod(hash, totalWeight);
            for (int i = 0; i < cumulative.length; i++) {
                if (value < cumulative[i]) {
                    return entries.get(i);
                }
            }
            return entries.get(entries.size() - 1);
        }
    }
}
