package org.topiacube.topiaAntiCaveFinder.player;

import org.bukkit.entity.Player;
import org.topiacube.topiaAntiCaveFinder.mask.BlockKey;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class InteractionRevealTracker {

    private final Map<BlockKey, Integer> expiry = new HashMap<>();
    private final Map<UUID, PlayerViewSession> sessions;

    InteractionRevealTracker(Map<UUID, PlayerViewSession> sessions) {
        this.sessions = sessions;
    }

    void revealToPlayers(Set<Player> players, BlockKey key, int durationTicks, int currentTick) {
        int expireTick = currentTick + durationTicks;
        for (Player player : players) {
            reveal(player, key, currentTick);
            if (durationTicks > 0) {
                expiry.merge(key, expireTick, Math::max);
            }
        }
    }

    void reveal(Player player, BlockKey key, int tick) {
        if (player == null || key == null) {
            return;
        }
        PlayerViewSession session = sessions.computeIfAbsent(player.getUniqueId(), PlayerViewSession::new);
        session.applyReveal(player, key, tick);
        session.markPassiveReveal(key, tick);
        session.markDirty();
    }

    boolean isActive(BlockKey key, int tick) {
        Integer expire = expiry.get(key);
        if (expire == null) {
            return false;
        }
        if (expire >= tick) {
            return true;
        }
        expiry.remove(key);
        return false;
    }

    void cleanupExpired(int tick) {
        if (expiry.isEmpty()) {
            return;
        }
        expiry.entrySet().removeIf(entry -> entry.getValue() < tick);
    }

    void remove(BlockKey key) {
        expiry.remove(key);
    }

    void clear() {
        expiry.clear();
    }
}
