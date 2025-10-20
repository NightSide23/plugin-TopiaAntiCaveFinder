package org.topiacube.topiaAntiCaveFinder.mask;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.concurrent.ConcurrentHashMap;

public final class BlockKey {
    private static final ConcurrentHashMap<String, String> WORLD_NAME_CACHE = new ConcurrentHashMap<>();

    private final String worldName;
    private final int x;
    private final int y;
    private final int z;

    public BlockKey(String worldName, int x, int y, int z) {
        this(worldName, x, y, z, false);
    }

    private BlockKey(String worldName, int x, int y, int z, boolean assumeInterned) {
        this.worldName = canonicalizeWorldName(worldName, assumeInterned);
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static BlockKey of(String worldName, int x, int y, int z) {
        return new BlockKey(worldName, x, y, z, false);
    }

    public static BlockKey ofInterned(String worldName, int x, int y, int z) {
        return new BlockKey(worldName, x, y, z, true);
    }

    public static String canonicalWorldName(String worldName) {
        return canonicalizeWorldName(worldName, false);
    }

    public static BlockKey from(Block block) {
        World world = block.getWorld();
        String worldName = world.getName();
        return new BlockKey(worldName, block.getX(), block.getY(), block.getZ(), false);
    }

    public String getWorldName() {
        return worldName;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public ChunkKey toChunkKey() {
        return new ChunkKey(worldName, Math.floorDiv(x, 16), Math.floorDiv(z, 16));
    }

    public Location toLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, x, y, z);
    }

    public Location toCenterLocation() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, x + 0.5, y + 0.5, z + 0.5);
    }

    public Block toBlock() {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return world.getBlockAt(x, y, z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof BlockKey blockKey)) {
            return false;
        }
        if (x != blockKey.x || y != blockKey.y || z != blockKey.z) {
            return false;
        }
        if (worldName == blockKey.worldName) {
            return true;
        }
        return worldName != null && worldName.equals(blockKey.worldName);
    }

    @Override
    public int hashCode() {
        int result = worldName != null ? worldName.hashCode() : 0;
        result = (31 * result) + x;
        result = (31 * result) + y;
        result = (31 * result) + z;
        return result;
    }

    private static String canonicalizeWorldName(String worldName, boolean assumeInterned) {
        if (worldName == null) {
            return null;
        }
        if (assumeInterned) {
            return worldName;
        }
        return WORLD_NAME_CACHE.computeIfAbsent(worldName, key -> key);
    }
}
