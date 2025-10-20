package org.topiacube.topiaAntiCaveFinder.player;

import org.topiacube.topiaAntiCaveFinder.config.PluginConfig;
import org.topiacube.topiaAntiCaveFinder.mask.BlockKey;
import org.topiacube.topiaAntiCaveFinder.mask.ChunkKey;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class PendingChunkTracker {

    private final PluginConfig config;
    private final Map<ChunkKey, Set<UUID>> pendingChunks = new HashMap<>();

    PendingChunkTracker(PluginConfig config) {
        this.config = config;
    }

    void register(UUID playerId, String worldName, int chunkX, int chunkZ) {
        if (playerId == null || worldName == null || config.isWorldExcluded(worldName)) {
            return;
        }
        ChunkKey chunkKey = key(worldName, chunkX, chunkZ);
        pendingChunks.computeIfAbsent(chunkKey, unused -> new HashSet<>()).add(playerId);
    }

    void unregister(UUID playerId, String worldName, int chunkX, int chunkZ) {
        if (playerId == null || worldName == null) {
            return;
        }
        ChunkKey chunkKey = key(worldName, chunkX, chunkZ);
        Set<UUID> waiters = pendingChunks.get(chunkKey);
        if (waiters == null) {
            return;
        }
        waiters.remove(playerId);
        if (waiters.isEmpty()) {
            pendingChunks.remove(chunkKey);
        }
    }

    Set<UUID> drain(String worldName, int chunkX, int chunkZ) {
        ChunkKey chunkKey = key(worldName, chunkX, chunkZ);
        Set<UUID> waiters = pendingChunks.remove(chunkKey);
        if (waiters == null || waiters.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(waiters);
    }

    void clearFor(UUID playerId) {
        if (pendingChunks.isEmpty() || playerId == null) {
            return;
        }
        Iterator<Map.Entry<ChunkKey, Set<UUID>>> iterator = pendingChunks.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ChunkKey, Set<UUID>> entry = iterator.next();
            Set<UUID> waiters = entry.getValue();
            waiters.remove(playerId);
            if (waiters.isEmpty()) {
                iterator.remove();
            }
        }
    }

    void clearAll() {
        pendingChunks.clear();
    }

    private ChunkKey key(String worldName, int chunkX, int chunkZ) {
        String canonicalWorld = BlockKey.canonicalWorldName(worldName);
        return new ChunkKey(canonicalWorld, chunkX, chunkZ);
    }
}
