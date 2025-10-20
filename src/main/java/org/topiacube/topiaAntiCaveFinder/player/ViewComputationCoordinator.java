package org.topiacube.topiaAntiCaveFinder.player;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.topiacube.topiaAntiCaveFinder.config.PluginConfig;
import org.topiacube.topiaAntiCaveFinder.mask.CaveMaskManager;
import org.topiacube.topiaAntiCaveFinder.mask.TrackedBlock;

import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

final class ViewComputationCoordinator {

    private static final int MAX_ASYNC_THREADS = 4;
    private static final int COMPLETED_RESULT_QUEUE_CAPACITY = 128;

    private final JavaPlugin plugin;
    private final CaveMaskManager maskManager;
    private final PluginConfig config;
    private final ArrayBlockingQueue<ViewComputationResult> completedResults =
        new ArrayBlockingQueue<>(COMPLETED_RESULT_QUEUE_CAPACITY);

    private ExecutorService computationPool;

    ViewComputationCoordinator(JavaPlugin plugin, CaveMaskManager maskManager, PluginConfig config) {
        this.plugin = plugin;
        this.maskManager = maskManager;
        this.config = config;
    }

    void start() {
        if (computationPool != null && !computationPool.isShutdown()) {
            return;
        }
        int available = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        int threads = Math.min(MAX_ASYNC_THREADS, available);
        computationPool = Executors.newFixedThreadPool(threads, new NamedThreadFactory("TopiaAntiCaveFinder-View"));
    }

    void shutdown() {
        if (computationPool != null) {
            computationPool.shutdownNow();
            computationPool = null;
        }
        completedResults.clear();
    }

    void schedule(Player player, PlayerViewSession session, int currentTick) {
        if (computationPool == null) {
            return;
        }
        if (!session.tryScheduleComputation()) {
            return;
        }
        PlayerSnapshot snapshot = PlayerSnapshot.capture(player, config, currentTick);
        ArrayList<TrackedBlock> buffer = session.borrowTrackedBlocksBuffer();
        try {
            computationPool.submit(() -> compute(snapshot, session, buffer));
        } catch (RejectedExecutionException rejected) {
            handleComputationRejection(session, snapshot, "Failed to submit view computation task", rejected);
        }
    }

    ViewComputationResult pollResult() {
        return completedResults.poll();
    }

    private void compute(PlayerSnapshot snapshot, PlayerViewSession session, ArrayList<TrackedBlock> buffer) {
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
            }
        } catch (Throwable throwable) {
            plugin.getLogger().log(Level.SEVERE, "Failed to compute view update for player " + snapshot.playerName(), throwable);
            session.recycleComputationBuffers();
            session.markComputationFinished();
            session.markDirty();
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

        return completedResults.offer(result);
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
        if (plugin.getLogger().isLoggable(level)) {
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

    private static final class NamedThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(1);
        private final String baseName;

        private NamedThreadFactory(String baseName) {
            this.baseName = baseName;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, baseName + "-" + counter.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
