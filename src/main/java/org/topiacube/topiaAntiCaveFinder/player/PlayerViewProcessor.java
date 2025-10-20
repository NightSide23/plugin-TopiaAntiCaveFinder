package org.topiacube.topiaAntiCaveFinder.player;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.topiacube.topiaAntiCaveFinder.config.PluginConfig;
import org.topiacube.topiaAntiCaveFinder.mask.BlockKey;
import org.topiacube.topiaAntiCaveFinder.mask.CaveMaskManager;
import org.topiacube.topiaAntiCaveFinder.mask.MaskPaletteResolver;
import org.topiacube.topiaAntiCaveFinder.mask.TrackedBlock;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;

final class PlayerViewProcessor {

    interface PendingChunkTracker {
        void registerPending(java.util.UUID playerId, String worldName, int chunkX, int chunkZ);
        void unregisterPending(java.util.UUID playerId, String worldName, int chunkX, int chunkZ);
    }

    private final JavaPlugin plugin;
    private final PluginConfig config;
    private final CaveMaskManager maskManager;
    private final boolean entityMaskingEnabled;
    private final double maskActivationRadiusSquared;
    private final NeighborRevealer neighborRevealer;
    private final EntityMaskController entityMaskController;

    PlayerViewProcessor(JavaPlugin plugin,
                        PluginConfig config,
                        CaveMaskManager maskManager,
                        boolean entityMaskingEnabled) {
        this.plugin = plugin;
        this.config = config;
        this.maskManager = maskManager;
        this.entityMaskingEnabled = entityMaskingEnabled;
        double activationRadius = config.getMaskActivationRadius();
        this.maskActivationRadiusSquared = activationRadius * activationRadius;
        this.neighborRevealer = new NeighborRevealer(config);
        this.entityMaskController = new EntityMaskController(plugin, config);
    }

    void processResult(Player player,
                       PlayerViewSession session,
                       ViewComputationResult result,
                       int tick,
                       BiPredicate<BlockKey, Integer> interactionLookup,
                       PendingChunkTracker chunkTracker) {
        List<TrackedBlock> trackedBlocks = result.trackedBlocks();
        if (trackedBlocks.isEmpty()) {
            return;
        }

        PlayerSnapshot snapshot = result.snapshot();
        World world = player.getWorld();
        String worldName = BlockKey.canonicalWorldName(snapshot.worldName());
        Vector viewDirection = snapshot.viewDirection();
        Location eye = new Location(world, snapshot.eyeX(), snapshot.eyeY(), snapshot.eyeZ());
        double eyeX = snapshot.eyeX();
        double eyeY = snapshot.eyeY();
        double eyeZ = snapshot.eyeZ();

        session.updateViewState(
            eyeX,
            eyeY,
            eyeZ,
            viewDirection,
            snapshot.chunkX(),
            snapshot.chunkZ(),
            tick
        );

        Set<BlockKey> activeKeys = session.borrowActiveKeyBuffer();
        int maxBlocks = config.getMaxBlocksPerPlayer();
        int processed = 0;
        int extraAllowance = Math.max(maxBlocks * 2, 4096);
        double maxRevealDistanceSquared = sqr(config.getMaxRevealDistance());
        double minRevealDistanceSquared = sqr(config.getMinRevealDistance());
        double priorityDistanceSquared = Math.min(maskActivationRadiusSquared, sqr((config.getMaxRevealDistance() * 1.5) + 4.0));

        double[] distanceSquares = result.distanceSquares();
        int trackedCount = result.size();
        for (int i = 0; i < trackedCount; i++) {
            if (processed >= maxBlocks && extraAllowance <= 0) {
                break;
            }
            TrackedBlock trackedBlock = trackedBlocks.get(i);
            BlockKey key = trackedBlock.getKey();
            if (!Objects.equals(key.getWorldName(), worldName)) {
                continue;
            }

            double distanceSquared = distanceSquares[i];
            if (distanceSquared > maskActivationRadiusSquared) {
                continue;
            }

            int chunkX = Math.floorDiv(key.getX(), 16);
            int chunkZ = Math.floorDiv(key.getZ(), 16);
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                if (chunkTracker != null) {
                    chunkTracker.registerPending(snapshot.playerId(), worldName, chunkX, chunkZ);
                }
                continue;
            }
            if (chunkTracker != null) {
                chunkTracker.unregisterPending(snapshot.playerId(), worldName, chunkX, chunkZ);
            }

            boolean priority = distanceSquared <= priorityDistanceSquared;
            if (processed >= maxBlocks && !priority) {
                continue;
            }

            Block worldBlock = world.getBlockAt(key.getX(), key.getY(), key.getZ());
            if (worldBlock.getBlockData().matches(trackedBlock.getOriginalData())) {
                maskManager.untrackBlock(key);
                continue;
            }

            if (shouldSkipMaskForSurface(worldBlock)) {
                session.applyReveal(player, key, tick);
                session.markPassiveReveal(key, tick);
                continue;
            }

            activeKeys.add(key);
            boolean handled = handleMaskCandidate(player,
                session,
                key,
                world,
                worldBlock,
                trackedBlock,
                eye,
                viewDirection,
                eyeX,
                eyeY,
                eyeZ,
                distanceSquared,
                minRevealDistanceSquared,
                maxRevealDistanceSquared,
                worldName,
                activeKeys,
                tick,
                interactionLookup);
            if (handled) {
                if (processed < maxBlocks) {
                    processed++;
                } else {
                    extraAllowance--;
                }
            }
        }

        InteriorRevealProcessor.process(player, session, config, player.getLocation(), worldName, activeKeys, tick);
        session.cleanup(player, activeKeys, candidateKey -> maskManager.get(candidateKey) != null, tick);
        activeKeys.clear();
        if (entityMaskingEnabled) {
            entityMaskController.update(player, session, eye, viewDirection);
        }
    }

    void applyInitialMask(Player player,
                          PlayerViewSession session,
                          List<TrackedBlock> trackedBlocks,
                          int tick) {
        if (trackedBlocks.isEmpty()) {
            return;
        }
        World world = player.getWorld();
        String worldName = world != null ? world.getName() : null;
        for (TrackedBlock trackedBlock : trackedBlocks) {
            BlockKey key = trackedBlock.getKey();
            if (worldName != null && !Objects.equals(worldName, key.getWorldName())) {
                continue;
            }
            Block worldBlock = world.getBlockAt(key.getX(), key.getY(), key.getZ());
            BlockData maskData = MaskPaletteResolver.resolveFromNeighbors(worldBlock, config);
            if (maskData == null) {
                maskData = trackedBlock.getOriginalData();
            }
            session.applyMask(player, key, maskData, tick);
        }
    }

    private boolean handleMaskCandidate(Player player,
                                         PlayerViewSession session,
                                         BlockKey key,
                                         World world,
                                         Block worldBlock,
                                         TrackedBlock trackedBlock,
                                         Location eye,
                                         Vector viewDirection,
                                         double eyeX,
                                         double eyeY,
                                         double eyeZ,
                                         double distanceSquared,
                                         double minRevealDistanceSquared,
                                         double maxRevealDistanceSquared,
                                         String worldName,
                                         Set<BlockKey> activeKeys,
                                         int tick,
                                         BiPredicate<BlockKey, Integer> interactionLookup) {
        if (interactionLookup != null && interactionLookup.test(key, tick)) {
            session.applyReveal(player, key, tick);
            session.markPassiveReveal(key, tick);
            return true;
        }
        if (session.isRevealed(key)) {
            session.applyReveal(player, key, tick);
            session.markPassiveReveal(key, tick);
            return true;
        }

        boolean reveal = false;
        if (distanceSquared <= minRevealDistanceSquared) {
            reveal = true;
        } else if (distanceSquared <= maxRevealDistanceSquared) {
            double dx = (key.getX() + 0.5) - eyeX;
            double dy = (key.getY() + 0.5) - eyeY;
            double dz = (key.getZ() + 0.5) - eyeZ;
            double distance = Math.sqrt(distanceSquared);
            boolean withinFov = VisibilityUtil.isWithinFov(viewDirection, dx, dy, dz, distanceSquared, config.getRevealFovHalfAngleCos());
            if (withinFov && VisibilityUtil.hasLineOfSight(player, key, eye, dx, dy, dz, distance)) {
                reveal = true;
            }
        }

        if (reveal) {
            session.applyReveal(player, key, tick);
            session.markPassiveReveal(key, tick);
            neighborRevealer.revealLayers(player,
                session,
                world,
                worldName,
                key,
                activeKeys,
                eye,
                viewDirection,
                maxRevealDistanceSquared,
                tick);
        } else {
            BlockData maskData = MaskPaletteResolver.resolveFromNeighbors(worldBlock, config);
            if (maskData == null) {
                maskData = trackedBlock.getOriginalData();
            }
            session.applyMask(player, key, maskData, tick);
            MaskPropagator.propagate(player, session, worldName, worldBlock, maskData, config, activeKeys, tick);
        }
        return true;
    }

    private boolean shouldSkipMaskForSurface(Block block) {
        if (block == null) {
            return false;
        }
        World world = block.getWorld();
        if (world.getEnvironment() != Environment.NORMAL) {
            return false;
        }
        if (block.getLightFromSky() >= 15) {
            return true;
        }
        int highest = world.getHighestBlockYAt(block.getX(), block.getZ()) - 1;
        return block.getY() >= highest;
    }

    private double sqr(double value) {
        return value * value;
    }
}
