package org.topiacube.topiaAntiCaveFinder.player;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.topiacube.topiaAntiCaveFinder.config.PluginConfig;
import org.topiacube.topiaAntiCaveFinder.mask.BlockKey;
import org.topiacube.topiaAntiCaveFinder.mask.MaskPaletteResolver;

import java.util.Set;

final class MaskPropagator {
    private static final BlockFace[] NEIGHBOR_FACES = {
        BlockFace.UP,
        BlockFace.DOWN,
        BlockFace.NORTH,
        BlockFace.SOUTH,
        BlockFace.EAST,
        BlockFace.WEST
    };

    private MaskPropagator() {
    }

    static void propagate(Player player,
                          PlayerViewSession session,
                          String worldName,
                          Block originBlock,
                          BlockData fallbackMask,
                          PluginConfig config,
                          Set<BlockKey> activeKeys,
                          int tick) {
        if (originBlock == null) {
            return;
        }
        World world = originBlock.getWorld();
        if (world == null) {
            return;
        }
        String resolvedWorldName = worldName != null ? worldName : BlockKey.canonicalWorldName(world.getName());

        int baseX = originBlock.getX();
        int baseY = originBlock.getY();
        int baseZ = originBlock.getZ();

        for (BlockFace face : NEIGHBOR_FACES) {
            int neighborX = baseX + face.getModX();
            int neighborY = baseY + face.getModY();
            int neighborZ = baseZ + face.getModZ();
            Block neighbor = world.getBlockAt(neighborX, neighborY, neighborZ);
            if (!BlockMaterialUtil.isAirLike(neighbor.getType())) {
                continue;
            }
            BlockKey neighborKey = resolvedWorldName != null
                ? BlockKey.ofInterned(resolvedWorldName, neighborX, neighborY, neighborZ)
                : BlockKey.of(null, neighborX, neighborY, neighborZ);
            if (session.isRevealed(neighborKey)) {
                continue;
            }
            if (!activeKeys.add(neighborKey)) {
                continue;
            }
            BlockData neighborMask = MaskPaletteResolver.resolveFromNeighbors(neighbor, config);
            if (neighborMask == null) {
                neighborMask = fallbackMask;
            }
            session.applyMask(player, neighborKey, neighborMask, tick);
        }
    }
}
