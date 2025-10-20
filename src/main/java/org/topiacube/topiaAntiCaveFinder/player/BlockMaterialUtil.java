package org.topiacube.topiaAntiCaveFinder.player;

import org.bukkit.Material;

final class BlockMaterialUtil {
    private static final Material LIGHT_BLOCK = Material.matchMaterial("LIGHT");
    private static final Material STRUCTURE_VOID_BLOCK = Material.matchMaterial("STRUCTURE_VOID");

    private BlockMaterialUtil() {
    }

    static boolean isAirLike(Material material) {
        if (material.isAir()) {
            return true;
        }
        if (LIGHT_BLOCK != null && material == LIGHT_BLOCK) {
            return true;
        }
        return STRUCTURE_VOID_BLOCK != null && material == STRUCTURE_VOID_BLOCK;
    }

    static boolean isInteriorTraversable(Material material) {
        if (isAirLike(material)) {
            return true;
        }
        return material == Material.WATER || material == Material.BUBBLE_COLUMN;
    }
}
