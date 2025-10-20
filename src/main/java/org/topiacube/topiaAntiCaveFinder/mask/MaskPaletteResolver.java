package org.topiacube.topiaAntiCaveFinder.mask;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.topiacube.topiaAntiCaveFinder.config.PluginConfig;

import java.util.ArrayList;
import java.util.List;

public final class MaskPaletteResolver {

    private MaskPaletteResolver() {
    }

    public static BlockData resolveFromNeighbors(Block origin, PluginConfig config) {
        if (config.isCustomMaskingEnabled()) {
            if (origin != null) {
                BlockData custom = config.selectCustomMask(origin);
                if (custom != null) {
                    return custom;
                }
            } else {
                BlockData custom = config.selectCustomMask((World) null, 0, 0, 0);
                if (custom != null) {
                    return custom;
                }
            }
        }
        if (origin == null) {
            return createDefault(config, null, 0);
        }

        List<BlockData> candidates = new ArrayList<>();
        collectNeighbor(origin.getRelative(1, 0, 0), config, candidates);
        collectNeighbor(origin.getRelative(-1, 0, 0), config, candidates);
        collectNeighbor(origin.getRelative(0, 1, 0), config, candidates);
        collectNeighbor(origin.getRelative(0, -1, 0), config, candidates);
        collectNeighbor(origin.getRelative(0, 0, 1), config, candidates);
        collectNeighbor(origin.getRelative(0, 0, -1), config, candidates);

        if (!candidates.isEmpty()) {
            return candidates.get(0);
        }

        return createDefault(config, origin.getWorld(), origin.getY());
    }

    private static void collectNeighbor(Block block, PluginConfig config, List<BlockData> result) {
        if (config.isCustomMaskingEnabled()) {
            return;
        }
        if (block == null) {
            return;
        }
        Material type = block.getType();
        if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
            return;
        }
        if (!config.isMaskable(type)) {
            return;
        }
        result.add(block.getBlockData().clone());
    }

    private static BlockData createDefault(PluginConfig config, World world, int y) {
        if (config != null) {
            BlockData fallback = config.selectCustomMask(world, 0, y, 0);
            if (fallback != null) {
                return fallback;
            }
        }

        String fallbackKey = "STONE";
        if (world != null) {
            Environment environment = world.getEnvironment();
            switch (environment) {
                case NETHER:
                    fallbackKey = "NETHERRACK";
                    break;
                case THE_END:
                    fallbackKey = "END_STONE";
                    break;
                default:
                    fallbackKey = y < 0 ? "DEEPSLATE" : "STONE";
                    break;
            }
        } else {
            fallbackKey = y < 0 ? "DEEPSLATE" : "STONE";
        }

        Material material = matchMaterial(fallbackKey);
        if (material == null) {
            material = Material.STONE;
        }

        try {
            return Bukkit.createBlockData(material);
        } catch (IllegalArgumentException ignored) {
            return Bukkit.createBlockData(Material.STONE);
        }
    }

    private static Material matchMaterial(String name) {
        return Material.matchMaterial(name);
    }
}
