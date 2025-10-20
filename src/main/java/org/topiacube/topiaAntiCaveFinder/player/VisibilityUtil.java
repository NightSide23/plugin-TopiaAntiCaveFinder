package org.topiacube.topiaAntiCaveFinder.player;

import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.topiacube.topiaAntiCaveFinder.mask.BlockKey;

final class VisibilityUtil {

    private static final double EPSILON = 1.0E-6;

    private VisibilityUtil() {
    }

    static boolean isWithinFov(Vector viewDirection,
                               double dx,
                               double dy,
                               double dz,
                               double lengthSquared,
                               double minDot) {
        if (lengthSquared < EPSILON) {
            return true;
        }
        double invLength = 1.0 / Math.sqrt(lengthSquared);
        double dot = (viewDirection.getX() * dx)
            + (viewDirection.getY() * dy)
            + (viewDirection.getZ() * dz);
        dot *= invLength;
        return dot >= minDot;
    }

    static boolean hasLineOfSight(Player player,
                                  BlockKey key,
                                  Location eye,
                                  double dx,
                                  double dy,
                                  double dz,
                                  double maxDistance) {
        double lengthSquared = (dx * dx) + (dy * dy) + (dz * dz);
        if (lengthSquared < EPSILON) {
            return true;
        }

        double invLength = 1.0 / Math.sqrt(lengthSquared);
        Vector direction = new Vector(dx * invLength, dy * invLength, dz * invLength);
        RayTraceResult result = player.getWorld().rayTraceBlocks(eye, direction, maxDistance, FluidCollisionMode.NEVER, true);
        if (result == null) {
            return true;
        }
        Block hitBlock = result.getHitBlock();
        if (hitBlock == null) {
            return true;
        }
        return hitBlock.getX() == key.getX() && hitBlock.getY() == key.getY() && hitBlock.getZ() == key.getZ();
    }
}
