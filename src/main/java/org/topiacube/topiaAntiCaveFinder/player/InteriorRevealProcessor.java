package org.topiacube.topiaAntiCaveFinder.player;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.topiacube.topiaAntiCaveFinder.config.PluginConfig;
import org.topiacube.topiaAntiCaveFinder.mask.BlockKey;
import org.topiacube.topiaAntiCaveFinder.util.LongArrayQueue;
import org.topiacube.topiaAntiCaveFinder.util.LongHashSet;

import java.util.Set;

final class InteriorRevealProcessor {
    private static final double INTERIOR_REVEAL_PADDING = 1.5;
    private static final BlockFace[] NEIGHBOR_FACES = {
        BlockFace.UP,
        BlockFace.DOWN,
        BlockFace.NORTH,
        BlockFace.SOUTH,
        BlockFace.EAST,
        BlockFace.WEST
    };
    private InteriorRevealProcessor() {
    }

    static void process(Player player,
                        PlayerViewSession session,
                        PluginConfig config,
                        Location origin,
                        String worldName,
                        Set<BlockKey> activeKeys,
                        int tick) {
        double radius = config.getInteriorRevealRadius();
        if (radius <= 0.0 || origin == null) {
            return;
        }
        World world = origin.getWorld();
        if (world == null) {
            return;
        }

        LongArrayQueue queue = session.borrowInteriorQueue();
        LongHashSet visited = session.borrowInteriorVisited();

        int originBlockX = origin.getBlockX();
        int originBlockY = origin.getBlockY();
        int originBlockZ = origin.getBlockZ();

        enqueueSeed(queue, visited, world, originBlockX, originBlockY, originBlockZ);
        enqueueSeed(queue, visited, world, originBlockX, originBlockY + 1, originBlockZ);
        if (queue.isEmpty()) {
            return;
        }

        double revealRadius = radius + INTERIOR_REVEAL_PADDING;
        double radiusSquared = revealRadius * revealRadius;
        double originX = origin.getX();
        double originY = origin.getY();
        double originZ = origin.getZ();
        String effectiveWorldName = worldName != null ? worldName : BlockKey.canonicalWorldName(world.getName());

        while (!queue.isEmpty()) {
            long packed = queue.pollFirst();
            int x = unpackBlockX(packed);
            int y = unpackBlockY(packed);
            int z = unpackBlockZ(packed);

            double dx = (x + 0.5) - originX;
            double dy = (y + 0.5) - originY;
            double dz = (z + 0.5) - originZ;
            if ((dx * dx) + (dy * dy) + (dz * dz) > radiusSquared) {
                continue;
            }

            Block block = world.getBlockAt(x, y, z);
            if (!BlockMaterialUtil.isInteriorTraversable(block.getType())) {
                continue;
            }

            BlockKey key = effectiveWorldName != null
                ? BlockKey.ofInterned(effectiveWorldName, x, y, z)
                : BlockKey.of(null, x, y, z);
            boolean added = activeKeys.add(key);
            if (!added && !session.isMasked(key)) {
                continue;
            }
            session.applyReveal(player, key, tick);
            session.markPassiveReveal(key, tick);
            revealBoundaryLayers(player, session, world, effectiveWorldName, block, activeKeys, tick);

            for (BlockFace face : NEIGHBOR_FACES) {
                Block neighbor = block.getRelative(face);
                if (!BlockMaterialUtil.isInteriorTraversable(neighbor.getType())) {
                    continue;
                }
                long neighborPacked = packBlockPosition(neighbor.getX(), neighbor.getY(), neighbor.getZ());
                if (visited.add(neighborPacked)) {
                    queue.addLast(neighborPacked);
                }
            }
        }
        visited.clear();
    }

    private static void revealBoundaryLayers(Player player,
                                             PlayerViewSession session,
                                             World world,
                                             String worldName,
                                             Block origin,
                                             Set<BlockKey> activeKeys,
                                             int tick) {
        for (BlockFace face : NEIGHBOR_FACES) {
            Block boundary = origin.getRelative(face);
            revealLayerBlock(player, session, boundary, worldName, activeKeys, tick);

            if (BlockMaterialUtil.isInteriorTraversable(boundary.getType())) {
                continue;
            }

            Block second = boundary.getRelative(face);
            revealLayerBlock(player, session, second, worldName, activeKeys, tick);
        }
    }

    private static void revealLayerBlock(Player player,
                                         PlayerViewSession session,
                                         Block block,
                                         String worldName,
                                         Set<BlockKey> activeKeys,
                                         int tick) {
        if (block == null) {
            return;
        }
        BlockKey key = worldName != null
            ? BlockKey.ofInterned(worldName, block.getX(), block.getY(), block.getZ())
            : BlockKey.of(null, block.getX(), block.getY(), block.getZ());
        boolean added = activeKeys.add(key);
        if (!added && !session.isMasked(key)) {
            return;
        }
        session.applyReveal(player, key, tick);
        session.markPassiveReveal(key, tick);
    }

    private static void enqueueSeed(LongArrayQueue queue, LongHashSet visited, World world, int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        if (!BlockMaterialUtil.isInteriorTraversable(block.getType())) {
            return;
        }
        long packed = packBlockPosition(x, y, z);
        if (visited.add(packed)) {
            queue.addLast(packed);
        }
    }

    private static long packBlockPosition(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((long) (y & 0xFFF));
    }

    private static int unpackBlockX(long packed) {
        return (int) (packed >> 38);
    }

    private static int unpackBlockY(long packed) {
        return (int) (packed << 52 >> 52);
    }

    private static int unpackBlockZ(long packed) {
        return (int) (packed << 26 >> 38);
    }
}
