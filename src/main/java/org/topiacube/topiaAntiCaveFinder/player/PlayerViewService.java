package org.topiacube.topiaAntiCaveFinder.player;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.topiacube.topiaAntiCaveFinder.config.PluginConfig;
import org.topiacube.topiaAntiCaveFinder.mask.BlockKey;
import org.topiacube.topiaAntiCaveFinder.mask.CaveMaskManager;
import org.topiacube.topiaAntiCaveFinder.mask.TrackedBlock;
import org.topiacube.topiaAntiCaveFinder.mask.MaskPaletteResolver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public final class PlayerViewService {

    private static final double EPSILON = 1.0E-6;
    private static final int MAX_ASYNC_THREADS = 4;
    private static final int STALE_RESULT_TOLERANCE_TICKS = 40;
    private static final int COMPLETED_RESULT_QUEUE_CAPACITY = 128;
    private static final int MAX_RESULTS_PER_TICK = 16;
    private static final int ADDITIONAL_REVEAL_LAYERS = 2;
    private static final int[][] REVEAL_DIRECTIONS = {
        {1, 0, 0},
        {-1, 0, 0},
        {0, 1, 0},
        {0, -1, 0},
        {0, 0, 1},
        {0, 0, -1}
    };
    private static final int[][] LATERAL_OFFSETS_X = {
        {0, 1, 0},
        {0, -1, 0},
        {0, 0, 1},
        {0, 0, -1}
    };
    private static final int[][] LATERAL_OFFSETS_Y = {
        {1, 0, 0},
        {-1, 0, 0},
        {0, 0, 1},
        {0, 0, -1}
    };
    private static final int[][] LATERAL_OFFSETS_Z = {
        {1, 0, 0},
        {-1, 0, 0},
        {0, 1, 0},
        {0, -1, 0}
    };
    private static final int[][] DIAGONAL_OFFSETS_X = {
        {0, 1, 1},
        {0, 1, -1},
        {0, -1, 1},
        {0, -1, -1}
    };
    private static final int[][] DIAGONAL_OFFSETS_Y = {
        {1, 0, 1},
        {1, 0, -1},
        {-1, 0, 1},
        {-1, 0, -1}
    };
    private static final int[][] DIAGONAL_OFFSETS_Z = {
        {1, 1, 0},
        {1, -1, 0},
        {-1, 1, 0},
        {-1, -1, 0}
    };

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final CaveMaskManager maskManager;
    private final PluginConfig config;

    private final Map<UUID, PlayerViewSession> sessions = new HashMap<>();
    private final ArrayBlockingQueue<ViewComputationResult> completedResults =
        new ArrayBlockingQueue<>(COMPLETED_RESULT_QUEUE_CAPACITY);
    private final Map<BlockKey, Integer> interactionRevealExpiry = new HashMap<>();
    private final boolean entityMaskingEnabled;
    private BukkitTask task;
    private ExecutorService computationPool;
    private int currentTick;

    public PlayerViewService(org.bukkit.plugin.java.JavaPlugin plugin,
                             CaveMaskManager maskManager,
                             PluginConfig config,
                             boolean entityMaskingEnabled) {
        this.plugin = plugin;
        this.maskManager = maskManager;
        this.config = config;
        this.entityMaskingEnabled = entityMaskingEnabled;
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
            return LATERAL_OFFSETS_X;
        }
        if (direction[1] != 0) {
            return LATERAL_OFFSETS_Y;
        }
        return LATERAL_OFFSETS_Z;
    }

    private int[][] diagonalOffsetsFor(int[] direction) {
        if (direction[0] != 0) {
            return DIAGONAL_OFFSETS_X;
        }
        if (direction[1] != 0) {
            return DIAGONAL_OFFSETS_Y;
        }
        return DIAGONAL_OFFSETS_Z;
    }

    private boolean revealBlock(Player player,
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

        if (!isWithinFov(viewDirection, dx, dy, dz, distanceSquared, minDot)) {
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

    public void start() {
        if (task != null) {
            task.cancel();
        }
        if (computationPool == null || computationPool.isShutdown()) {
            int available = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
            int threads = Math.min(MAX_ASYNC_THREADS, available);
            computationPool = Executors.newFixedThreadPool(threads, new NamedThreadFactory("TopiaAntiCaveFinder-View"));
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, config.getCheckIntervalTicks(), config.getCheckIntervalTicks());
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player online : Bukkit.getOnlinePlayers()) {
            PlayerViewSession session = sessions.remove(online.getUniqueId());
            if (session != null) {
                session.clear(plugin, online);
            }
        }
        interactionRevealExpiry.clear();
        completedResults.clear();
        if (computationPool != null) {
            computationPool.shutdownNow();
            computationPool = null;
        }
    }

    public void initializePlayer(Player player) {
        sessions.computeIfAbsent(player.getUniqueId(), PlayerViewSession::new);
    }

    public void removePlayer(Player player) {
        PlayerViewSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            session.clear(plugin, player);
        }
    }

    public void resetPlayer(Player player) {
        PlayerViewSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.clear(plugin, player);
        }
    }

    public void registerExcavatedBlock(Player source, Block block) {
        if (!config.shouldTrackBlock(block.getWorld(), block.getType())) {
            return;
        }
        BlockKey key = BlockKey.from(block);
        Set<Player> recipients = new HashSet<>();
        recipients.add(source);

        double radius = config.getInteractionRevealRadius();
        if (radius > 0.0) {
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            double radiusSquared = radius * radius;
            for (Player nearby : Bukkit.getOnlinePlayers()) {
                if (nearby.equals(source)) {
                    continue;
                }
                if (nearby.getWorld() != block.getWorld()) {
                    continue;
                }
                if (nearby.getLocation().distanceSquared(center) > radiusSquared) {
                    continue;
                }
                recipients.add(nearby);
            }
        }

        Set<BlockKey> revealTargets = new HashSet<>();
        revealTargets.add(key);
        int revealDuration = config.getInteractionRevealDurationTicks();
        int expireTick = currentTick + revealDuration;
        for (Player recipient : recipients) {
            for (BlockKey revealKey : revealTargets) {
                markRevealed(recipient, revealKey);
                if (revealDuration > 0) {
                    interactionRevealExpiry.merge(revealKey, expireTick, Math::max);
                }
            }
        }
    }

    public void invalidateBlock(BlockKey key) {
        if (key == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerViewSession session = sessions.get(player.getUniqueId());
            if (session != null) {
                session.revert(player, key);
                session.markDirty();
            }
        }
        interactionRevealExpiry.remove(key);
    }

    public boolean isEntityMaskingEnabled() {
        return entityMaskingEnabled;
    }

    private void tick() {
        currentTick++;
        if (!interactionRevealExpiry.isEmpty()) {
            interactionRevealExpiry.entrySet().removeIf(entry -> entry.getValue() < currentTick);
        }
        drainCompletedResults();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isOnline()) {
                continue;
            }
            PlayerViewSession session = sessions.computeIfAbsent(player.getUniqueId(), PlayerViewSession::new);
            if (config.isWorldExcluded(player.getWorld().getName())) {
                continue;
            }
            if (!session.isComputationScheduled() && session.shouldSchedule(player, currentTick)) {
                scheduleComputation(player, session);
            }
        }
    }

    private void drainCompletedResults() {
        ViewComputationResult result;
        int processed = 0;
        while (processed < MAX_RESULTS_PER_TICK && (result = completedResults.poll()) != null) {
            processed++;
            PlayerViewSession session = result.session();
            session.markComputationFinished();
            int age = currentTick - result.snapshot().scheduledTick();
            if (age > STALE_RESULT_TOLERANCE_TICKS) {
                session.recycleComputationBuffers();
                session.markDirty();
                plugin.getLogger().log(Level.FINE,
                    "Discarded stale computation result for player " + result.snapshot().playerName()
                        + " (age=" + age + " ticks)");
                continue;
            }

            PlayerViewSession currentSession = sessions.get(result.snapshot().playerId());
            if (currentSession != session) {
                session.recycleComputationBuffers();
                continue;
            }

            Player player = Bukkit.getPlayer(result.snapshot().playerId());
            if (player == null || !player.isOnline()) {
                session.recycleComputationBuffers();
                continue;
            }
            if (!Objects.equals(player.getWorld().getName(), result.snapshot().worldName())) {
                session.recycleComputationBuffers();
                continue;
            }
            if (config.isWorldExcluded(result.snapshot().worldName())) {
                session.recycleComputationBuffers();
                continue;
            }

            applyViewResult(player, session, result, currentTick);
            session.recycleComputationBuffers();
        }
    }

    private void scheduleComputation(Player player, PlayerViewSession session) {
        if (computationPool == null) {
            return;
        }
        if (!session.tryScheduleComputation()) {
            return;
        }
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player, config, currentTick);
        ArrayList<TrackedBlock> buffer = session.borrowTrackedBlocksBuffer();
        try {
            computationPool.submit(() -> {
                try {
                    maskManager.collectNearbyBlocks(snapshot.worldName(), snapshot.chunkX(), snapshot.chunkZ(), snapshot.chunkRadius(), buffer);
                    buffer.sort((a, b) -> Double.compare(snapshot.distanceSquared(a.getKey()), snapshot.distanceSquared(b.getKey())));
                    double[] distances = session.borrowDistanceBuffer(buffer.size());
                    for (int i = 0; i < buffer.size(); i++) {
                        distances[i] = snapshot.distanceSquared(buffer.get(i).getKey());
                    }
                    ViewComputationResult result = new ViewComputationResult(session, snapshot, buffer, distances, buffer.size());
                    if (!enqueueCompletedResult(result)) {
                        handleComputationRejection(session, snapshot, "Completed results queue is full", null);
                        return;
                    }
                } catch (Throwable throwable) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to compute view update for player " + snapshot.playerName(), throwable);
                    session.recycleComputationBuffers();
                    session.markComputationFinished();
                    session.markDirty();
                }
            });
        } catch (RejectedExecutionException rejected) {
            handleComputationRejection(session, snapshot, "Failed to submit view computation task", rejected);
        }
    }

    private boolean enqueueCompletedResult(ViewComputationResult result) {
        if (completedResults.offer(result)) {
            return true;
        }

        ViewComputationResult replaced = null;
        for (ViewComputationResult queued : completedResults) {
            if (queued.session() == result.session()) {
                replaced = queued;
                break;
            }
        }

        if (replaced != null && completedResults.remove(replaced)) {
            discardQueuedResult(replaced,
                false,
                Level.FINE,
                "Replaced queued computation result with newer data");
            if (completedResults.offer(result)) {
                return true;
            }
        }

        ViewComputationResult evicted = completedResults.poll();
        if (evicted != null) {
            discardQueuedResult(evicted,
                true,
                Level.WARNING,
                "Evicted queued computation result due to capacity limit");
        }

        if (completedResults.offer(result)) {
            return true;
        }

        return false;
    }

    private void discardQueuedResult(ViewComputationResult result,
                                     boolean markSessionFinished,
                                     Level level,
                                     String message) {
        PlayerViewSession session = result.session();
        if (markSessionFinished) {
            session.markComputationFinished();
            session.markDirty();
        }
        session.recycleComputationBuffers();
        if (message != null) {
            plugin.getLogger().log(level,
                message + " (player=" + result.snapshot().playerName() + ")");
        }
    }

    private void handleComputationRejection(PlayerViewSession session,
                                            PlayerSnapshot snapshot,
                                            String message,
                                            Throwable cause) {
        session.recycleComputationBuffers();
        session.markComputationFinished();
        session.markDirty();
        if (cause == null) {
            plugin.getLogger().log(Level.WARNING, message + " for player " + snapshot.playerName());
        } else {
            plugin.getLogger().log(Level.WARNING, message + " for player " + snapshot.playerName(), cause);
        }
    }

    private void applyViewResult(Player player,
                                 PlayerViewSession session,
                                 ViewComputationResult result,
                                 int tick) {
        List<TrackedBlock> trackedBlocks = result.trackedBlocks();
        int trackedCount = result.size();

        World world = player.getWorld();
        PlayerSnapshot snapshot = result.snapshot();
        Vector viewDirection = snapshot.viewDirection();
        String worldName = BlockKey.canonicalWorldName(snapshot.worldName());
        Location eye = new Location(world, snapshot.eyeX(), snapshot.eyeY(), snapshot.eyeZ());
        double eyeX = snapshot.eyeX();
        double eyeY = snapshot.eyeY();
        double eyeZ = snapshot.eyeZ();
        Location location = player.getLocation();

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
        double maxRevealDistance = config.getMaxRevealDistance();
        double maxRevealDistanceSquared = maxRevealDistance * maxRevealDistance;
        double minRevealDistance = config.getMinRevealDistance();
        double minRevealDistanceSquared = minRevealDistance * minRevealDistance;
        double priorityDistance = (maxRevealDistance * 1.5) + 4.0;
        double priorityDistanceSquared = priorityDistance * priorityDistance;

        if (trackedCount > 0) {
            double[] distanceSquares = result.distanceSquares();
            for (int i = 0; i < trackedCount; i++) {
                TrackedBlock trackedBlock = trackedBlocks.get(i);
                BlockKey key = trackedBlock.getKey();
                if (!Objects.equals(key.getWorldName(), worldName)) {
                    continue;
                }

                double distanceSquared = distanceSquares[i];
                boolean priority = distanceSquared <= priorityDistanceSquared;
                if (processed >= maxBlocks && !(priority && extraAllowance > 0)) {
                    break;
                }

                Block worldBlock = world.getBlockAt(key.getX(), key.getY(), key.getZ());
                if (worldBlock.getBlockData().matches(trackedBlock.getOriginalData())) {
                    maskManager.untrackBlock(key);
                    invalidateBlock(key);
                    continue;
                }

                if (shouldSkipMaskForSurface(worldBlock)) {
                    session.applyReveal(player, key, tick);
                    session.markPassiveReveal(key, tick);
                    continue;
                }

                activeKeys.add(key);

                if (handleMaskCandidate(player,
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
                        tick)) {
                    if (processed < maxBlocks) {
                        processed++;
                    } else {
                        extraAllowance--;
                    }
                }
            }
        }

        InteriorRevealProcessor.process(player, session, config, location, worldName, activeKeys, tick);
        session.cleanup(player, activeKeys, candidateKey -> maskManager.get(candidateKey) != null, tick);
        activeKeys.clear();
        if (entityMaskingEnabled) {
            handleEntityMask(player, session, eye, viewDirection);
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
                                        int tick) {
        if (isInteractionRevealActive(key, tick) || session.isRevealed(key)) {
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
            boolean withinFov = isWithinFov(viewDirection, dx, dy, dz, distanceSquared, config.getRevealFovHalfAngleCos());
            if (withinFov && hasLineOfSight(player, key, eye, dx, dy, dz, distance)) {
                reveal = true;
            }
        }

        if (reveal) {
            session.applyReveal(player, key, tick);
            session.markPassiveReveal(key, tick);
            revealNeighborLayers(player,
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

    private void revealNeighborLayers(Player player,
                                      PlayerViewSession session,
                                      World world,
                                      String worldName,
                                      BlockKey origin,
                                      Set<BlockKey> activeKeys,
                                      Location eye,
                                      Vector viewDirection,
                                      double maxDistanceSquared,
                                      int tick) {
        if (world == null) {
            return;
        }
        Location eyeLocation = eye != null ? eye : player.getEyeLocation();
        if (eyeLocation == null) {
            return;
        }

        Vector directionVector = viewDirection != null ? viewDirection.clone() : eyeLocation.getDirection().clone();
        if (directionVector.lengthSquared() > EPSILON) {
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

        for (int[] direction : REVEAL_DIRECTIONS) {
            boolean blocked = false;
            for (int depth = 1; depth <= ADDITIONAL_REVEAL_LAYERS; depth++) {
                int targetX = baseX + (direction[0] * depth);
                int targetY = baseY + (direction[1] * depth);
                int targetZ = baseZ + (direction[2] * depth);
                if (targetY < minY || targetY > maxY) {
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
                if (depth == 1 || traversable) {
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

    private void handleEntityMask(Player player, PlayerViewSession session, Location eye, Vector viewDirection) {
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
                    reveal = isWithinFov(viewDirection, dx, dy, dz, distanceSquared, config.getRevealFovHalfAngleCos())
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

    private boolean isWithinFov(Vector viewDirection,
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

    private boolean hasLineOfSight(Player player,
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

    private boolean isInteractionRevealActive(BlockKey key, int tick) {
        Integer expiry = interactionRevealExpiry.get(key);
        if (expiry == null) {
            return false;
        }
        if (expiry >= tick) {
            return true;
        }
        interactionRevealExpiry.remove(key);
        return false;
    }

    private void markRevealed(Player player, BlockKey key) {
        if (player == null || key == null) {
            return;
        }
        PlayerViewSession session = sessions.computeIfAbsent(player.getUniqueId(), PlayerViewSession::new);
        session.applyReveal(player, key, currentTick);
        session.markPassiveReveal(key, currentTick);
        session.markDirty();
    }

    private static final class ViewComputationResult {
        private final PlayerViewSession session;
        private final PlayerSnapshot snapshot;
        private final List<TrackedBlock> trackedBlocks;
        private final double[] distanceSquares;
        private final int size;

        private ViewComputationResult(PlayerViewSession session,
                                      PlayerSnapshot snapshot,
                                      List<TrackedBlock> trackedBlocks,
                                      double[] distanceSquares,
                                      int size) {
            this.session = session;
            this.snapshot = snapshot;
            this.trackedBlocks = trackedBlocks;
            this.distanceSquares = distanceSquares;
            this.size = size;
        }

        PlayerViewSession session() {
            return session;
        }

        PlayerSnapshot snapshot() {
            return snapshot;
        }

        List<TrackedBlock> trackedBlocks() {
            return trackedBlocks;
        }

        double[] distanceSquares() {
            return distanceSquares;
        }

        int size() {
            return size;
        }
    }

    private static final class PlayerSnapshot {
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

        static PlayerSnapshot capture(Player player, PluginConfig config, int tick) {
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

        UUID playerId() {
            return playerId;
        }

        String playerName() {
            return playerName;
        }

        String worldName() {
            return worldName;
        }

        int chunkX() {
            return chunkX;
        }

        int chunkZ() {
            return chunkZ;
        }

        int chunkRadius() {
            return chunkRadius;
        }

        double eyeX() {
            return eyeX;
        }

        double eyeY() {
            return eyeY;
        }

        double eyeZ() {
            return eyeZ;
        }

        Vector viewDirection() {
            return viewDirection;
        }

        int scheduledTick() {
            return scheduledTick;
        }

        double distanceSquared(BlockKey key) {
            double dx = (key.getX() + 0.5) - eyeX;
            double dy = (key.getY() + 0.5) - eyeY;
            double dz = (key.getZ() + 0.5) - eyeZ;
            return (dx * dx) + (dy * dy) + (dz * dz);
        }
    }

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);
        private final String baseName;

        private NamedThreadFactory(String baseName) {
            this.baseName = baseName;
        }

        @Override
        public Thread newThread(@NotNull Runnable runnable) {
            Thread thread = new Thread(runnable, baseName + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

}
