package org.topiacube.topiaAntiCaveFinder.player;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;
import org.topiacube.topiaAntiCaveFinder.mask.BlockKey;
import org.topiacube.topiaAntiCaveFinder.mask.TrackedBlock;
import org.topiacube.topiaAntiCaveFinder.util.LongArrayQueue;
import org.topiacube.topiaAntiCaveFinder.util.LongHashSet;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.concurrent.atomic.AtomicBoolean;

final class PlayerViewSession {

    private final UUID playerId;
    private final Map<BlockKey, BlockDisplayState> blockStates = new HashMap<>();
    private final Set<UUID> hiddenEntities = new HashSet<>();

    private final ArrayList<TrackedBlock> trackedBlocksBuffer = new ArrayList<>();
    private double[] distanceBuffer = new double[0];
    private boolean computationBufferInUse;

    private final Set<BlockKey> activeKeyBuffer = new HashSet<>();
    private final Set<BlockKey> cleanupBuffer = new HashSet<>();
    private final LongArrayQueue interiorQueue = new LongArrayQueue();
    private final LongHashSet interiorVisited = new LongHashSet();
    private final Set<UUID> validEntityBuffer = new HashSet<>();
    private final Map<BlockKey, Integer> passiveRevealExpiry = new HashMap<>();

    private final AtomicBoolean computationScheduled = new AtomicBoolean(false);

    private double lastEyeX;
    private double lastEyeY;
    private double lastEyeZ;
    private double lastDirX;
    private double lastDirY;
    private double lastDirZ;
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    private int lastViewUpdateTick = Integer.MIN_VALUE;
    private boolean hasLastViewState;
    private boolean forceNextComputation;

    private static volatile boolean supportsPluginEntityVisibility = detectPluginEntityVisibilitySupport();
    private static final BlockChangeTransmitter BLOCK_CHANGE_TRANSMITTER = BlockChangeTransmitter.detect();
    private static final int RESEND_INTERVAL_TICKS = 5;
    private static final double MIN_MOVEMENT_DELTA_SQUARED = 0.16;
    private static final double MIN_ROTATION_DOT_THRESHOLD = Math.cos(Math.toRadians(3.0));
    private static final int MAX_IDLE_TICKS_BETWEEN_COMPUTATIONS = 40;
    private static final int PASSIVE_REVEAL_DURATION_TICKS = 30;

    PlayerViewSession(UUID playerId) {
        this.playerId = playerId;
    }

    UUID getPlayerId() {
        return playerId;
    }

    void applyMask(Player player, BlockKey key, BlockData maskData, int tick) {
        BlockDisplayState current = blockStates.get(key);
        if (current != null && current.displayState == DisplayState.MASKED && !needsRefresh(current, tick)) {
            return;
        }
        passiveRevealExpiry.remove(key);
        if (maskData == null) {
            blockStates.remove(key);
            return;
        }
        Location location;
        World world = player.getWorld();
        if (world != null && Objects.equals(world.getName(), key.getWorldName())) {
            location = new Location(world, key.getX(), key.getY(), key.getZ());
        } else {
            location = key.toLocation();
        }
        if (location == null) {
            return;
        }
        if (BLOCK_CHANGE_TRANSMITTER.send(player, location, maskData)) {
            blockStates.put(key, BlockDisplayState.masked(tick));
        }
    }

    void applyReveal(Player player, BlockKey key, int tick) {
        BlockDisplayState current = blockStates.get(key);
        if (current != null && current.displayState == DisplayState.REVEALED && !needsRefresh(current, tick)) {
            return;
        }
        passiveRevealExpiry.remove(key);
        World world = player.getWorld();
        Block block = null;
        if (world != null && Objects.equals(world.getName(), key.getWorldName())) {
            block = world.getBlockAt(key.getX(), key.getY(), key.getZ());
        }
        if (block == null) {
            block = key.toBlock();
        }
        if (block == null) {
            blockStates.remove(key);
            return;
        }
        if (BLOCK_CHANGE_TRANSMITTER.send(player, block.getLocation(), block.getBlockData())) {
            blockStates.put(key, BlockDisplayState.revealed(tick));
        } else {
            blockStates.remove(key);
        }
    }

    boolean isRevealed(BlockKey key) {
        BlockDisplayState state = blockStates.get(key);
        return state != null && state.displayState == DisplayState.REVEALED;
    }

    boolean isMasked(BlockKey key) {
        BlockDisplayState state = blockStates.get(key);
        return state != null && state.displayState == DisplayState.MASKED;
    }

    void cleanup(Player player, Set<BlockKey> validKeys, Predicate<BlockKey> keepMaskedPredicate, int tick) {
        removeExpiredPassiveReveals(tick);
        Set<BlockKey> stale = borrowCleanupBuffer();
        for (Map.Entry<BlockKey, BlockDisplayState> entry : blockStates.entrySet()) {
            BlockKey key = entry.getKey();
            BlockDisplayState state = entry.getValue();
            if (validKeys.contains(key) || isPassiveRevealActive(key, tick)) {
                continue;
            }
            if (state.displayState == DisplayState.MASKED && keepMaskedPredicate != null && keepMaskedPredicate.test(key)) {
                continue;
            }
            stale.add(key);
        }
        stale.forEach(key -> revert(player, key));
        stale.clear();
    }

    void revert(Player player, BlockKey key) {
        BlockDisplayState state = blockStates.remove(key);
        if (state == null) {
            return;
        }
        passiveRevealExpiry.remove(key);
        World world = player.getWorld();
        Block block = null;
        if (world != null && Objects.equals(world.getName(), key.getWorldName())) {
            block = world.getBlockAt(key.getX(), key.getY(), key.getZ());
        } else {
            block = key.toBlock();
        }
        if (block != null) {
            BLOCK_CHANGE_TRANSMITTER.send(player, block.getLocation(), block.getBlockData());
        }
    }

    void clear(Plugin plugin, Player player) {
        for (BlockKey key : new HashSet<>(blockStates.keySet())) {
            revert(player, key);
        }
        blockStates.clear();
        passiveRevealExpiry.clear();
        for (UUID uuid : new HashSet<>(hiddenEntities)) {
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null) {
                showEntity(plugin, player, entity);
            }
        }
        hiddenEntities.clear();
        hasLastViewState = false;
        forceNextComputation = false;
        lastChunkX = Integer.MIN_VALUE;
        lastChunkZ = Integer.MIN_VALUE;
        lastViewUpdateTick = Integer.MIN_VALUE;
    }

    private enum DisplayState {
        MASKED,
        REVEALED
    }

    private static final class BlockDisplayState {
        private final DisplayState displayState;
        private final int lastSentTick;

        private BlockDisplayState(DisplayState displayState, int lastSentTick) {
            this.displayState = displayState;
            this.lastSentTick = lastSentTick;
        }

        private static BlockDisplayState masked(int tick) {
            return new BlockDisplayState(DisplayState.MASKED, tick);
        }

        private static BlockDisplayState revealed(int tick) {
            return new BlockDisplayState(DisplayState.REVEALED, tick);
        }
    }

    void hideEntity(Plugin plugin, Player player, Entity entity) {
        UUID uuid = entity.getUniqueId();
        if (!hiddenEntities.add(uuid)) {
            return;
        }
        if (tryHideEntity(plugin, player, entity)) {
            return;
        }
        if (entity instanceof Player targetPlayer) {
            player.hidePlayer(plugin, targetPlayer);
            return;
        }
        hiddenEntities.remove(uuid);
    }

    void showEntity(Plugin plugin, Player player, Entity entity) {
        UUID uuid = entity.getUniqueId();
        if (!hiddenEntities.remove(uuid)) {
            return;
        }
        showEntityDirect(plugin, player, entity);
    }

    void cleanupEntities(Plugin plugin, Player player, Set<UUID> valid) {
        Iterator<UUID> iterator = hiddenEntities.iterator();
        while (iterator.hasNext()) {
            UUID uuid = iterator.next();
            if (valid.contains(uuid)) {
                continue;
            }
            Entity entity = Bukkit.getEntity(uuid);
            if (entity != null) {
                showEntityDirect(plugin, player, entity);
            }
            iterator.remove();
        }
    }

    ArrayList<TrackedBlock> borrowTrackedBlocksBuffer() {
        synchronized (this) {
            if (computationBufferInUse) {
                throw new IllegalStateException("Computation buffer already in use");
            }
            computationBufferInUse = true;
            trackedBlocksBuffer.clear();
            return trackedBlocksBuffer;
        }
    }

    double[] borrowDistanceBuffer(int size) {
        synchronized (this) {
            if (!computationBufferInUse) {
                throw new IllegalStateException("Distance buffer requested before tracked blocks buffer");
            }
            if (distanceBuffer.length < size) {
                int newCapacity = Math.max(size, distanceBuffer.length * 2 + 16);
                distanceBuffer = new double[newCapacity];
            }
            return distanceBuffer;
        }
    }

    void recycleComputationBuffers() {
        synchronized (this) {
            trackedBlocksBuffer.clear();
            computationBufferInUse = false;
        }
    }

    Set<BlockKey> borrowActiveKeyBuffer() {
        activeKeyBuffer.clear();
        return activeKeyBuffer;
    }

    LongArrayQueue borrowInteriorQueue() {
        interiorQueue.clear();
        return interiorQueue;
    }

    LongHashSet borrowInteriorVisited() {
        interiorVisited.clear();
        return interiorVisited;
    }

    Set<UUID> borrowValidEntityBuffer() {
        validEntityBuffer.clear();
        return validEntityBuffer;
    }

    Set<BlockKey> borrowCleanupBuffer() {
        cleanupBuffer.clear();
        return cleanupBuffer;
    }

    boolean tryScheduleComputation() {
        return computationScheduled.compareAndSet(false, true);
    }

    void markComputationFinished() {
        computationScheduled.set(false);
    }

    boolean isComputationScheduled() {
        return computationScheduled.get();
    }

    boolean shouldSchedule(Player player, int tick) {
        if (forceNextComputation) {
            return true;
        }
        if (!hasLastViewState) {
            return true;
        }
        if ((tick - lastViewUpdateTick) >= MAX_IDLE_TICKS_BETWEEN_COMPUTATIONS) {
            return true;
        }

        Location location = player.getLocation();
        int chunkX = location.getBlockX() >> 4;
        int chunkZ = location.getBlockZ() >> 4;
        if (chunkX != lastChunkX || chunkZ != lastChunkZ) {
            return true;
        }

        Location eye = player.getEyeLocation();
        double eyeX = eye.getX();
        double eyeY = eye.getY();
        double eyeZ = eye.getZ();

        double dx = eyeX - lastEyeX;
        double dy = eyeY - lastEyeY;
        double dz = eyeZ - lastEyeZ;
        double movementSquared = (dx * dx) + (dy * dy) + (dz * dz);
        if (movementSquared >= MIN_MOVEMENT_DELTA_SQUARED) {
            return true;
        }

        Vector viewDirection = eye.getDirection();
        double dot = (viewDirection.getX() * lastDirX)
            + (viewDirection.getY() * lastDirY)
            + (viewDirection.getZ() * lastDirZ);
        return dot < MIN_ROTATION_DOT_THRESHOLD;
    }

    void updateViewState(double eyeX,
                         double eyeY,
                         double eyeZ,
                         Vector direction,
                         int chunkX,
                         int chunkZ,
                         int tick) {
        lastEyeX = eyeX;
        lastEyeY = eyeY;
        lastEyeZ = eyeZ;
        lastDirX = direction.getX();
        lastDirY = direction.getY();
        lastDirZ = direction.getZ();
        lastChunkX = chunkX;
        lastChunkZ = chunkZ;
        lastViewUpdateTick = tick;
        hasLastViewState = true;
        forceNextComputation = false;
    }

    void markDirty() {
        forceNextComputation = true;
    }

    void markPassiveReveal(BlockKey key, int tick) {
        if (key == null) {
            return;
        }
        passiveRevealExpiry.put(key, tick + PASSIVE_REVEAL_DURATION_TICKS);
    }

    private boolean isPassiveRevealActive(BlockKey key, int tick) {
        Integer expiry = passiveRevealExpiry.get(key);
        if (expiry == null) {
            return false;
        }
        if (expiry >= tick) {
            return true;
        }
        passiveRevealExpiry.remove(key);
        return false;
    }

    private void removeExpiredPassiveReveals(int tick) {
        if (passiveRevealExpiry.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<BlockKey, Integer>> iterator = passiveRevealExpiry.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<BlockKey, Integer> entry = iterator.next();
            if (entry.getValue() < tick) {
                iterator.remove();
            }
        }
    }

    private boolean hasPluginHideSupport() {
        return supportsPluginEntityVisibility;
    }

    private static boolean detectPluginEntityVisibilitySupport() {
        try {
            Player.class.getMethod("hideEntity", Plugin.class, Entity.class);
            Player.class.getMethod("showEntity", Plugin.class, Entity.class);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private boolean needsRefresh(BlockDisplayState state, int currentTick) {
        int delta = currentTick - state.lastSentTick;
        return delta >= RESEND_INTERVAL_TICKS || delta < 0;
    }

    private boolean tryHideEntity(Plugin plugin, Player player, Entity entity) {
        if (!hasPluginHideSupport()) {
            return false;
        }
        try {
            player.hideEntity(plugin, entity);
            return true;
        } catch (NoSuchMethodError error) {
            supportsPluginEntityVisibility = false;
            return false;
        }
    }

    private boolean tryShowEntity(Plugin plugin, Player player, Entity entity) {
        if (!hasPluginHideSupport()) {
            return false;
        }
        try {
            player.showEntity(plugin, entity);
            return true;
        } catch (NoSuchMethodError error) {
            supportsPluginEntityVisibility = false;
            return false;
        }
    }

    private void showEntityDirect(Plugin plugin, Player player, Entity entity) {
        if (tryShowEntity(plugin, player, entity)) {
            return;
        }
        if (entity instanceof Player targetPlayer) {
            player.showPlayer(plugin, targetPlayer);
        }
    }

    private static final class BlockChangeTransmitter {
        private final Method blockDataMethod;
        private final Method legacyMethod;

        private BlockChangeTransmitter(Method blockDataMethod, Method legacyMethod) {
            this.blockDataMethod = blockDataMethod;
            this.legacyMethod = legacyMethod;
        }

        static BlockChangeTransmitter detect() {
            Method blockData = null;
            Method legacy = null;
            try {
                blockData = Player.class.getMethod("sendBlockChange", Location.class, BlockData.class);
            } catch (NoSuchMethodException ignored) {
                try {
                    legacy = Player.class.getMethod("sendBlockChange", Location.class, Material.class, byte.class);
                } catch (NoSuchMethodException legacyEx) {
                    Bukkit.getLogger().log(Level.WARNING, "Could not find compatible sendBlockChange method; masking will be disabled.");
                }
            }
            return new BlockChangeTransmitter(blockData, legacy);
        }

        boolean send(Player player, Location location, BlockData data) {
            if (player == null || location == null || data == null) {
                return false;
            }
            if (blockDataMethod != null) {
                try {
                    blockDataMethod.invoke(player, location, data);
                    return true;
                } catch (ReflectiveOperationException ex) {
                    Bukkit.getLogger().log(Level.WARNING, "Failed to send block change using modern API.", ex);
                }
                return false;
            }
            if (legacyMethod != null) {
                try {
                    legacyMethod.invoke(player, location, data.getMaterial(), (byte) 0);
                    return true;
                } catch (ReflectiveOperationException ex) {
                    Bukkit.getLogger().log(Level.WARNING, "Failed to send block change using legacy API.", ex);
                }
            }
            return false;
        }
    }
}
