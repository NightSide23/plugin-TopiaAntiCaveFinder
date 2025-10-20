package org.topiacube.topiaAntiCaveFinder.player;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.topiacube.topiaAntiCaveFinder.config.PluginConfig;
import org.topiacube.topiaAntiCaveFinder.mask.BlockKey;

import java.util.Set;

final class NeighborRevealer {

    private static final int ADDITIONAL_REVEAL_LAYERS = 2;

    private final PluginConfig config;

    NeighborRevealer(PluginConfig config) {
        this.config = config;
    }

    void revealLayers(Player player,
                      PlayerViewSession session,
                      World world,
                      String worldName,
                      BlockKey origin,
                      Set<BlockKey> activeKeys,
                      Location eye,
                      Vector viewDirection,
                      double maxDistanceSquared,
                      int tick) {
        if (world == null || origin == null) {
            return;
        }
        Location eyeLocation = eye != null ? eye : player.getEyeLocation();
        if (eyeLocation == null) {
            return;
        }

        Vector directionVector = viewDirection != null ? viewDirection.clone() : eyeLocation.getDirection().clone();
        if (directionVector.lengthSquared() > 0.0) {
            directionVector.normalize();
        } else {
            directionVector = new Vector(0.0, 0.0, 1.0);
        }

        int baseX = origin.getX();
        int baseY = origin.getY();
        int baseZ = origin.getZ();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;

        double minDot = config.getRevealFovHalfAngleCos();
        double lateralMinDot = Math.max(-0.10, minDot - 0.25);
        double diagonalMinDot = Math.max(-0.30, minDot - 0.40);

        for (int[] direction : NeighborOffsets.REVEAL_DIRECTIONS) {
            boolean blocked = false;
            for (int depth = 1; depth <= ADDITIONAL_REVEAL_LAYERS; depth++) {
                int targetX = baseX + (direction[0] * depth);
                int targetY = baseY + (direction[1] * depth);
                int targetZ = baseZ + (direction[2] * depth);
                if (targetY < minY || targetY > maxY) {
                    break;
                }
                if (!world.isChunkLoaded(Math.floorDiv(targetX, 16), Math.floorDiv(targetZ, 16))) {
                    break;
                }
                Block targetBlock = world.getBlockAt(targetX, targetY, targetZ);
                boolean traversable = BlockMaterialUtil.isInteriorTraversable(targetBlock.getType());
                if (!traversable && blocked) {
                    break;
                }

                boolean revealed = revealBlock(player,
                    session,
                    world,
                    worldName,
                    activeKeys,
                    targetBlock,
                    eyeLocation,
                    directionVector,
                    minDot,
                    maxDistanceSquared,
                    tick);

                if (!revealed) {
                    if (!traversable) {
                        blocked = true;
                    }
                    break;
                }

                boolean allowDiagonal = depth == 1 || traversable;
                if (allowDiagonal) {
                    revealPerpendicularNeighbors(player,
                        session,
                        world,
                        worldName,
                        activeKeys,
                        baseX,
                        baseY,
                        baseZ,
                        direction,
                        depth,
                        eyeLocation,
                        directionVector,
                        lateralMinDot,
                        diagonalMinDot,
                        maxDistanceSquared,
                        tick,
                        allowDiagonal);
                }

                if (!traversable) {
                    blocked = true;
                }
            }
        }
    }

    boolean revealBlock(Player player,
                        PlayerViewSession session,
                        World world,
                        String worldName,
                        Set<BlockKey> activeKeys,
                        Block block,
                        Location eye,
                        Vector viewDirection,
                        double minDot,
                        double maxDistanceSquared,
                        int tick) {
        if (block == null) {
            return false;
        }
        int y = block.getY();
        if (y < world.getMinHeight() || y > world.getMaxHeight() - 1) {
            return false;
        }

        double centerX = block.getX() + 0.5;
        double centerY = block.getY() + 0.5;
        double centerZ = block.getZ() + 0.5;
        double dx = centerX - eye.getX();
        double dy = centerY - eye.getY();
        double dz = centerZ - eye.getZ();
        double distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
        if (distanceSquared > maxDistanceSquared) {
            return false;
        }

        if (!VisibilityUtil.isWithinFov(viewDirection, dx, dy, dz, distanceSquared, minDot)) {
            return false;
        }

        BlockKey key = worldName != null
            ? BlockKey.ofInterned(worldName, block.getX(), block.getY(), block.getZ())
            : BlockKey.of(null, block.getX(), block.getY(), block.getZ());

        boolean alreadyActive = !activeKeys.add(key);
        if (alreadyActive && !session.isMasked(key)) {
            return true;
        }

        session.applyReveal(player, key, tick);
        session.markPassiveReveal(key, tick);
        return true;
    }

    private void revealPerpendicularNeighbors(Player player,
                                              PlayerViewSession session,
                                              World world,
                                              String worldName,
                                              Set<BlockKey> activeKeys,
                                              int baseX,
                                              int baseY,
                                              int baseZ,
                                              int[] direction,
                                              int depth,
                                              Location eye,
                                              Vector viewDirection,
                                              double lateralMinDot,
                                              double diagonalMinDot,
                                              double maxDistanceSquared,
                                              int tick,
                                              boolean includeDiagonals) {
        int targetXBase = baseX + (direction[0] * depth);
        int targetYBase = baseY + (direction[1] * depth);
        int targetZBase = baseZ + (direction[2] * depth);
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;

        int[][] lateralOffsets = lateralOffsetsFor(direction);
        for (int[] offset : lateralOffsets) {
            int targetX = targetXBase + offset[0];
            int targetY = targetYBase + offset[1];
            int targetZ = targetZBase + offset[2];
            if (targetY < minY || targetY > maxY) {
                continue;
            }
            if (!world.isChunkLoaded(Math.floorDiv(targetX, 16), Math.floorDiv(targetZ, 16))) {
                continue;
            }
            Block block = world.getBlockAt(targetX, targetY, targetZ);
            revealBlock(player,
                session,
                world,
                worldName,
                activeKeys,
                block,
                eye,
                viewDirection,
                lateralMinDot,
                maxDistanceSquared,
                tick);
        }

        if (!includeDiagonals) {
            return;
        }

        int[][] diagonalOffsets = diagonalOffsetsFor(direction);
        for (int[] offset : diagonalOffsets) {
            int targetX = targetXBase + offset[0];
            int targetY = targetYBase + offset[1];
            int targetZ = targetZBase + offset[2];
            if (targetY < minY || targetY > maxY) {
                continue;
            }
            if (!world.isChunkLoaded(Math.floorDiv(targetX, 16), Math.floorDiv(targetZ, 16))) {
                continue;
            }
            Block block = world.getBlockAt(targetX, targetY, targetZ);
            revealBlock(player,
                session,
                world,
                worldName,
                activeKeys,
                block,
                eye,
                viewDirection,
                diagonalMinDot,
                maxDistanceSquared,
                tick);
        }
    }

    private int[][] lateralOffsetsFor(int[] direction) {
        if (direction[0] != 0) {
            return NeighborOffsets.LATERAL_OFFSETS_X;
        }
        if (direction[1] != 0) {
            return NeighborOffsets.LATERAL_OFFSETS_Y;
        }
        return NeighborOffsets.LATERAL_OFFSETS_Z;
    }

    private int[][] diagonalOffsetsFor(int[] direction) {
        if (direction[0] != 0) {
            return NeighborOffsets.DIAGONAL_OFFSETS_X;
        }
        if (direction[1] != 0) {
            return NeighborOffsets.DIAGONAL_OFFSETS_Y;
        }
        return NeighborOffsets.DIAGONAL_OFFSETS_Z;
    }
}
