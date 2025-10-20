package org.topiacube.topiaAntiCaveFinder.player;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.topiacube.topiaAntiCaveFinder.config.PluginConfig;
import org.topiacube.topiaAntiCaveFinder.mask.BlockKey;

import java.util.UUID;

public final class PlayerSnapshot {

    private final UUID playerId;
    private final String playerName;
    private final String worldName;
    private final int chunkX;
    private final int chunkZ;
    private final int chunkRadius;
    private final double eyeX;
    private final double eyeY;
    private final double eyeZ;
    private final Vector viewDirection;
    private final int scheduledTick;

    private PlayerSnapshot(UUID playerId,
                           String playerName,
                           String worldName,
                           int chunkX,
                           int chunkZ,
                           int chunkRadius,
                           double eyeX,
                           double eyeY,
                           double eyeZ,
                           Vector viewDirection,
                           int scheduledTick) {
        this.playerId = playerId;
        this.playerName = playerName;
        this.worldName = worldName;
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.chunkRadius = chunkRadius;
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.viewDirection = viewDirection;
        this.scheduledTick = scheduledTick;
    }

    public static PlayerSnapshot capture(Player player, PluginConfig config, int tick) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().clone();
        if (direction.lengthSquared() > 0.0) {
            direction.normalize();
        }
        Location location = player.getLocation();
        World world = player.getWorld();
        int chunkX = Math.floorDiv(location.getBlockX(), 16);
        int chunkZ = Math.floorDiv(location.getBlockZ(), 16);
        return new PlayerSnapshot(
            player.getUniqueId(),
            player.getName(),
            BlockKey.canonicalWorldName(world != null ? world.getName() : null),
            chunkX,
            chunkZ,
            config.getChunkRadius(),
            eye.getX(),
            eye.getY(),
            eye.getZ(),
            direction,
            tick
        );
    }

    public UUID playerId() {
        return playerId;
    }

    public String playerName() {
        return playerName;
    }

    public String worldName() {
        return worldName;
    }

    public int chunkX() {
        return chunkX;
    }

    public int chunkZ() {
        return chunkZ;
    }

    public int chunkRadius() {
        return chunkRadius;
    }

    public double eyeX() {
        return eyeX;
    }

    public double eyeY() {
        return eyeY;
    }

    public double eyeZ() {
        return eyeZ;
    }

    public Vector viewDirection() {
        return viewDirection;
    }

    public int scheduledTick() {
        return scheduledTick;
    }

    public double distanceSquared(BlockKey key) {
        double dx = (key.getX() + 0.5) - eyeX;
        double dy = (key.getY() + 0.5) - eyeY;
        double dz = (key.getZ() + 0.5) - eyeZ;
        return (dx * dx) + (dy * dy) + (dz * dz);
    }
}
