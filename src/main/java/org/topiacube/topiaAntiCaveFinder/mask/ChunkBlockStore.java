package org.topiacube.topiaAntiCaveFinder.mask;

import org.bukkit.block.data.BlockData;

import java.util.List;

final class ChunkBlockStore {

    private final IntIntHashMap entries = new IntIntHashMap();

    boolean upsert(int index, int paletteId) {
        return entries.put(index, paletteId) == IntIntHashMap.NO_VALUE;
    }

    int getPaletteId(int index) {
        return entries.get(index);
    }

    boolean remove(int index) {
        return entries.remove(index) != IntIntHashMap.NO_VALUE;
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    int size() {
        return entries.size();
    }

    void collect(String worldName, int chunkX, int chunkZ, BlockPalette palette, List<TrackedBlock> output) {
        entries.forEach((index, paletteId) -> {
            BlockKey key = decode(worldName, chunkX, chunkZ, index);
            BlockData data = palette.get(paletteId);
            output.add(new TrackedBlock(key, data));
        });
    }

    void forEach(IntIntHashMap.IntIntConsumer consumer) {
        entries.forEach(consumer);
    }

    private BlockKey decode(String worldName, int chunkX, int chunkZ, int index) {
        int localX = index & CaveMaskManager.LOCAL_COORD_MASK;
        int localZ = (index >>> 4) & CaveMaskManager.LOCAL_COORD_MASK;
        int y = (index >>> 8) - CaveMaskManager.BLOCK_INDEX_Y_OFFSET;
        int x = (chunkX << 4) + localX;
        int z = (chunkZ << 4) + localZ;
        return new BlockKey(worldName, x, y, z);
    }
}
