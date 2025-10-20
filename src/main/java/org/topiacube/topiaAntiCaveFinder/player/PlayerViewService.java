package org.topiacube.topiaAntiCaveFinder.player;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.topiacube.topiaAntiCaveFinder.config.PluginConfig;
import org.topiacube.topiaAntiCaveFinder.mask.BlockKey;
import org.topiacube.topiaAntiCaveFinder.mask.CaveMaskManager;
import org.topiacube.topiaAntiCaveFinder.mask.TrackedBlock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class PlayerViewService {

    private static final int STALE_RESULT_TOLERANCE_TICKS = 40;
    private static final int MAX_RESULTS_PER_TICK = 16;

    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final CaveMaskManager maskManager;
    private final PluginConfig config;
    private final boolean entityMaskingEnabled;

    private final Map<UUID, PlayerViewSession> sessions = new HashMap<>();
    private final ViewComputationCoordinator computationCoordinator;
    private final PlayerViewProcessor viewProcessor;
    private final PlayerViewProcessor.PendingChunkTracker chunkTracker;
    private final InteractionRevealTracker interactionTracker;
    private final PendingChunkTracker pendingChunks;

    private BukkitTask task;
    private int currentTick;

    public PlayerViewService(org.bukkit.plugin.java.JavaPlugin plugin,
                             CaveMaskManager maskManager,
                             PluginConfig config,
                             boolean entityMaskingEnabled) {
        this.plugin = plugin;
        this.maskManager = maskManager;
        this.config = config;
        this.entityMaskingEnabled = entityMaskingEnabled;
        this.computationCoordinator = new ViewComputationCoordinator(plugin, maskManager, config);
        this.viewProcessor = new PlayerViewProcessor(plugin, config, maskManager, entityMaskingEnabled);
        this.pendingChunks = new PendingChunkTracker(config);
        this.chunkTracker = new PlayerViewProcessor.PendingChunkTracker() {
            @Override
            public void registerPending(UUID playerId, String worldName, int chunkX, int chunkZ) {
                pendingChunks.register(playerId, worldName, chunkX, chunkZ);
            }

            @Override
            public void unregisterPending(UUID playerId, String worldName, int chunkX, int chunkZ) {
                pendingChunks.unregister(playerId, worldName, chunkX, chunkZ);
            }
        };
        this.interactionTracker = new InteractionRevealTracker(sessions);
    }

    public void start() {
        if (task != null) {
            task.cancel();
        }
        computationCoordinator.start();
        long interval = Math.max(1L, config.getCheckIntervalTicks());
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
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
        interactionTracker.clear();
        pendingChunks.clearAll();
        computationCoordinator.shutdown();
    }

    public void initializePlayer(Player player) {
        sessions.computeIfAbsent(player.getUniqueId(), PlayerViewSession::new);
    }

    public void removePlayer(Player player) {
        PlayerViewSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            session.clear(plugin, player);
        }
        pendingChunks.clearFor(player.getUniqueId());
    }

    public void resetPlayer(Player player) {
        PlayerViewSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            session.clear(plugin, player);
        }
        pendingChunks.clearFor(player.getUniqueId());
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
            double radiusSquared = radius * radius;
            var center = block.getLocation().add(0.5, 0.5, 0.5);
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

        int revealDuration = config.getInteractionRevealDurationTicks();
        interactionTracker.revealToPlayers(recipients, key, revealDuration, currentTick);
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
        interactionTracker.remove(key);
    }

    public boolean isEntityMaskingEnabled() {
        return entityMaskingEnabled;
    }

    public void handleChunkLoad(Chunk chunk) {
        if (chunk == null) {
            return;
        }
        String worldName = chunk.getWorld().getName();
        if (config.isWorldExcluded(worldName)) {
            return;
        }

        List<TrackedBlock> trackedBlocks = new ArrayList<>();
        maskManager.collectNearbyBlocks(worldName, chunk.getX(), chunk.getZ(), 0, trackedBlocks);
        if (!trackedBlocks.isEmpty()) {
            for (Player player : chunk.getWorld().getPlayers()) {
                PlayerViewSession session = sessions.get(player.getUniqueId());
                if (session == null) {
                    continue;
                }
                viewProcessor.applyInitialMask(player, session, trackedBlocks, currentTick);
            }
        }

        Set<UUID> waiters = pendingChunks.drain(worldName, chunk.getX(), chunk.getZ());
        if (waiters.isEmpty()) {
            return;
        }
        for (UUID playerId : waiters) {
            PlayerViewSession session = sessions.get(playerId);
            if (session == null) {
                continue;
            }
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) {
                continue;
            }
            if (!Objects.equals(player.getWorld().getName(), worldName)) {
                continue;
            }
            session.markDirty();
            scheduleComputation(player, session);
        }
    }

    private void tick() {
        currentTick++;
        interactionTracker.cleanupExpired(currentTick);
        drainCompletedResults();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isOnline()) {
                continue;
            }
            if (config.isWorldExcluded(player.getWorld().getName())) {
                continue;
            }
            PlayerViewSession session = sessions.computeIfAbsent(player.getUniqueId(), PlayerViewSession::new);
            if (!session.isComputationScheduled() && session.shouldSchedule(player, currentTick)) {
                scheduleComputation(player, session);
            }
        }
    }

    private void scheduleComputation(Player player, PlayerViewSession session) {
        computationCoordinator.schedule(player, session, currentTick);
    }

    private void drainCompletedResults() {
        int processed = 0;
        ViewComputationResult result;
        while (processed < MAX_RESULTS_PER_TICK && (result = computationCoordinator.pollResult()) != null) {
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

            viewProcessor.processResult(player,
                session,
                result,
                currentTick,
                interactionTracker::isActive,
                chunkTracker);
            session.recycleComputationBuffers();
        }
    }

}
