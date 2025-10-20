package org.topiacube.topiaAntiCaveFinder.player;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.topiacube.topiaAntiCaveFinder.config.PluginConfig;

import java.util.Set;
import java.util.UUID;

final class EntityMaskController {

    private final JavaPlugin plugin;
    private final PluginConfig config;

    EntityMaskController(JavaPlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    void update(Player player, PlayerViewSession session, Location eye, Vector viewDirection) {
        double maxRevealDistance = config.getMaxRevealDistance();
        double maxRevealDistanceSquared = maxRevealDistance * maxRevealDistance;
        double minRevealDistance = config.getMinRevealDistance();
        double minRevealDistanceSquared = minRevealDistance * minRevealDistance;
        double radius = (maxRevealDistance * 2.0) + 16.0;
        Set<UUID> valid = session.borrowValidEntityBuffer();
        player.getWorld().getNearbyEntities(eye, radius, radius, radius, entity -> config.isMaskableEntity(entity.getType()))
            .forEach(entity -> {
                UUID uuid = entity.getUniqueId();
                if (player.equals(entity)) {
                    return;
                }
                valid.add(uuid);
                Location target = entity.getLocation().add(0, entity.getHeight() * 0.5, 0);
                double distanceSquared = eye.distanceSquared(target);
                double dx = target.getX() - eye.getX();
                double dy = target.getY() - eye.getY();
                double dz = target.getZ() - eye.getZ();
                boolean reveal = false;
                if (distanceSquared <= minRevealDistanceSquared) {
                    reveal = true;
                } else if (distanceSquared <= maxRevealDistanceSquared) {
                    reveal = VisibilityUtil.isWithinFov(viewDirection, dx, dy, dz, distanceSquared, config.getRevealFovHalfAngleCos())
                        && player.hasLineOfSight(entity);
                }

                if (reveal) {
                    session.showEntity(plugin, player, entity);
                } else {
                    session.hideEntity(plugin, player, entity);
                }
            });
        session.cleanupEntities(plugin, player, valid);
        valid.clear();
    }
}
