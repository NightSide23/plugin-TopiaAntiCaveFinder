package org.topiacube.topiaAntiCaveFinder.mask;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Level;

public final class CaveMaskManager {

    private static final int STORAGE_MAGIC = 0x54414346; // TACF
    private static final int STORAGE_VERSION = 2;
    static final int BLOCK_INDEX_Y_OFFSET = 8192;
    static final int LOCAL_COORD_MASK = 0xF;

    private final File storageFile;
    private final File legacyStorageFile;

    private final Map<ChunkKey, ChunkBlockStore> chunkStores = new HashMap<>();
    private final BlockPalette palette = new BlockPalette();

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    private int trackedBlockCount;

    public CaveMaskManager(File dataFolder) {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            throw new IllegalStateException("Could not create plugin data folder: " + dataFolder.getAbsolutePath());
        }
        this.storageFile = new File(dataFolder, "tracked-blocks.dat");
        this.legacyStorageFile = new File(dataFolder, "tracked-blocks.yml");
    }

    public void load() {
        lock.writeLock().lock();
        try {
            chunkStores.clear();
            palette.clear();
            trackedBlockCount = 0;
            if (storageFile.exists()) {
                loadBinary(storageFile);
                return;
            }
            if (legacyStorageFile.exists()) {
                loadLegacyYaml(legacyStorageFile);
                try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(storageFile)))) {
                    writeToStream(output);
                } catch (IOException exception) {
                    Bukkit.getLogger().log(Level.SEVERE, "Failed to migrate tracked block storage", exception);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void loadBinary(File file) {
        // write lock guaranteed by caller
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            int magic = input.readInt();
            if (magic != STORAGE_MAGIC) {
                Bukkit.getLogger().warning("Tracked block storage has unknown signature, skipping load.");
                return;
            }
            int version = input.readInt();
            int chunkCount = input.readInt();
            if (version == 1) {
                loadBinaryV1(input, chunkCount);
            } else if (version == STORAGE_VERSION) {
                loadBinaryV2(input, chunkCount);
            } else {
                Bukkit.getLogger().warning("Tracked block storage version mismatch (" + version + "), skipping load.");
            }
        } catch (EOFException eof) {
            Bukkit.getLogger().log(Level.WARNING, "Tracked block storage truncated: " + file.getAbsolutePath(), eof);
        } catch (IOException exception) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to read tracked block storage", exception);
        }
    }

    private void loadBinaryV1(DataInputStream input, int chunkCount) throws IOException {
        // write lock guaranteed by caller
        for (int i = 0; i < chunkCount; i++) {
            String worldName = input.readUTF();
            int chunkX = input.readInt();
            int chunkZ = input.readInt();
            ChunkKey chunkKey = new ChunkKey(worldName, chunkX, chunkZ);
            ChunkBlockStore store = getOrCreateChunkStore(chunkKey);

            int blockCount = input.readInt();
            for (int b = 0; b < blockCount; b++) {
                int x = input.readInt();
                int y = input.readInt();
                int z = input.readInt();
                String dataString = input.readUTF();
                try {
                    BlockData data = Bukkit.createBlockData(dataString);
                    int paletteId = palette.getOrCreateId(data);
                    int index = toLocalIndex(x, y, z);
                    if (store.upsert(index, paletteId)) {
                        trackedBlockCount++;
                    }
                } catch (IllegalArgumentException ex) {
                    Bukkit.getLogger().log(Level.WARNING, "Skipping invalid block data while reading storage", ex);
                }
            }
        }
    }

    private void loadBinaryV2(DataInputStream input, int chunkCount) throws IOException {
        // write lock guaranteed by caller
        for (int i = 0; i < chunkCount; i++) {
            String worldName = input.readUTF();
            int chunkX = input.readInt();
            int chunkZ = input.readInt();
            ChunkKey chunkKey = new ChunkKey(worldName, chunkX, chunkZ);
            ChunkBlockStore store = getOrCreateChunkStore(chunkKey);

            int paletteSize = input.readInt();
            int[] globalPaletteIds = new int[paletteSize];
            for (int p = 0; p < paletteSize; p++) {
                String dataString = input.readUTF();
                try {
                    BlockData data = Bukkit.createBlockData(dataString);
                    globalPaletteIds[p] = palette.getOrCreateId(data);
                } catch (IllegalArgumentException ex) {
                    Bukkit.getLogger().log(Level.WARNING, "Skipping invalid block data while reading storage", ex);
                    globalPaletteIds[p] = -1;
                }
            }

            boolean paletteFitsInByte = input.readBoolean();
            int blockCount = input.readInt();
            for (int b = 0; b < blockCount; b++) {
                int y = input.readShort();
                int packedLocal = input.readUnsignedByte();
                int localPaletteId = paletteFitsInByte ? input.readUnsignedByte() : input.readUnsignedShort();
                if (localPaletteId < 0 || localPaletteId >= globalPaletteIds.length) {
                    continue;
                }
                int globalPaletteId = globalPaletteIds[localPaletteId];
                if (globalPaletteId == -1) {
                    continue;
                }

                int localX = packedLocal & 0xF;
                int localZ = (packedLocal >>> 4) & 0xF;
                int worldX = (chunkX << 4) + localX;
                int worldZ = (chunkZ << 4) + localZ;
                int index = toLocalIndex(worldX, y, worldZ);
                if (store.upsert(index, globalPaletteId)) {
                    trackedBlockCount++;
                }
            }
        }
    }

    private void loadLegacyYaml(File file) {
        // write lock guaranteed by caller
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to load tracked block storage", e);
            return;
        }

        List<?> entries = yaml.getList("entries");
        if (entries == null) {
            return;
        }

        for (Object rawEntry : entries) {
            if (!(rawEntry instanceof Map<?, ?> entry)) {
                continue;
            }
            Object worldObj = entry.get("world");
            Object xObj = entry.get("x");
            Object yObj = entry.get("y");
            Object zObj = entry.get("z");
            Object dataObj = entry.get("blockData");
            if (!(worldObj instanceof String worldName) || !(xObj instanceof Number) || !(yObj instanceof Number)
                || !(zObj instanceof Number) || !(dataObj instanceof String)) {
                continue;
            }

            int x = ((Number) xObj).intValue();
            int y = ((Number) yObj).intValue();
            int z = ((Number) zObj).intValue();
            BlockData blockData;
            try {
                blockData = Bukkit.createBlockData((String) dataObj);
            } catch (IllegalArgumentException exception) {
                Bukkit.getLogger().log(Level.WARNING, "Skipping invalid block data entry: " + dataObj, exception);
                continue;
            }

            ChunkKey chunkKey = new ChunkKey(worldName, Math.floorDiv(x, 16), Math.floorDiv(z, 16));
            ChunkBlockStore store = getOrCreateChunkStore(chunkKey);
            int paletteId = palette.getOrCreateId(blockData);
            int index = toLocalIndex(x, y, z);
            if (store.upsert(index, paletteId)) {
                trackedBlockCount++;
            }
        }
    }

    public void save() {
        lock.readLock().lock();
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(storageFile)))) {
            writeToStream(output);
        } catch (IOException e) {
            Bukkit.getLogger().log(Level.SEVERE, "Failed to save tracked block storage", e);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void writeToStream(DataOutputStream output) throws IOException {
        output.writeInt(STORAGE_MAGIC);
        output.writeInt(STORAGE_VERSION);
        output.writeInt(chunkStores.size());
        for (Map.Entry<ChunkKey, ChunkBlockStore> entry : chunkStores.entrySet()) {
            ChunkKey chunkKey = entry.getKey();
            ChunkBlockStore store = entry.getValue();
            output.writeUTF(chunkKey.getWorldName());
            output.writeInt(chunkKey.getX());
            output.writeInt(chunkKey.getZ());

            int blockCount = store.size();
            int[] indices = new int[blockCount];
            int[] localPaletteIds = new int[blockCount];
            IntIntHashMap paletteRemap = new IntIntHashMap();
            ArrayList<Integer> localPalette = new ArrayList<>();

            final int[] position = {0};
            store.forEach((index, globalPaletteId) -> {
                int local = paletteRemap.get(globalPaletteId);
                if (local == IntIntHashMap.NO_VALUE) {
                    local = localPalette.size();
                    paletteRemap.put(globalPaletteId, local);
                    localPalette.add(globalPaletteId);
                }
                indices[position[0]] = index;
                localPaletteIds[position[0]] = local;
                position[0]++;
            });

            int paletteSize = localPalette.size();
            output.writeInt(paletteSize);
            for (int i = 0; i < paletteSize; i++) {
                int globalPaletteId = localPalette.get(i);
                output.writeUTF(palette.getSerialized(globalPaletteId));
            }
            boolean paletteFitsInByte = paletteSize <= 255;
            output.writeBoolean(paletteFitsInByte);
            output.writeInt(blockCount);
            for (int i = 0; i < blockCount; i++) {
                int index = indices[i];
                int y = (index >>> 8) - BLOCK_INDEX_Y_OFFSET;
                int packedLocal = index & 0xFF;
                output.writeShort((short) y);
                output.writeByte((byte) packedLocal);
                int localPaletteId = localPaletteIds[i];
                if (paletteFitsInByte) {
                    output.writeByte((byte) localPaletteId);
                } else {
                    output.writeShort((short) localPaletteId);
                }
            }
        }
        output.flush();
    }

    public void trackBlock(Block block) {
        trackBlock(block, block.getBlockData());
    }

    public void trackBlock(Block block, BlockData originalData) {
        lock.writeLock().lock();
        try {
            Material type = block.getType();
            if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) {
                return;
            }

            BlockKey key = BlockKey.from(block);
            ChunkKey chunkKey = key.toChunkKey();
            ChunkBlockStore store = getOrCreateChunkStore(chunkKey);
            int paletteId = palette.getOrCreateId(originalData);
            int index = toLocalIndex(key.getX(), key.getY(), key.getZ());
            if (store.upsert(index, paletteId)) {
                trackedBlockCount++;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void untrackBlock(Block block) {
        untrackBlock(BlockKey.from(block));
    }

    public void untrackBlock(BlockKey key) {
        lock.writeLock().lock();
        try {
            ChunkKey chunkKey = key.toChunkKey();
            ChunkBlockStore store = chunkStores.get(chunkKey);
            if (store == null) {
                return;
            }
            int index = toLocalIndex(key.getX(), key.getY(), key.getZ());
            if (store.remove(index)) {
                trackedBlockCount--;
                if (store.isEmpty()) {
                    chunkStores.remove(chunkKey);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<TrackedBlock> getNearbyBlocks(String worldName, int chunkX, int chunkZ, int radius) {
        ArrayList<TrackedBlock> result = new ArrayList<>();
        collectNearbyBlocks(worldName, chunkX, chunkZ, radius, result);
        return result;
    }

    public void collectNearbyBlocks(String worldName, int chunkX, int chunkZ, int radius, List<TrackedBlock> output) {
        lock.readLock().lock();
        try {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    ChunkKey chunkKey = new ChunkKey(worldName, chunkX + dx, chunkZ + dz);
                    ChunkBlockStore store = chunkStores.get(chunkKey);
                    if (store == null || store.isEmpty()) {
                        continue;
                    }
                    if (output instanceof ArrayList<?> arrayList) {
                        @SuppressWarnings("unchecked")
                        ArrayList<TrackedBlock> casted = (ArrayList<TrackedBlock>) arrayList;
                        casted.ensureCapacity(output.size() + store.size());
                    }
                    store.collect(worldName, chunkKey.getX(), chunkKey.getZ(), palette, output);
                }
            }
        } finally {
            lock.readLock().unlock();
        }
    }

    public TrackedBlock get(BlockKey key) {
        lock.readLock().lock();
        try {
            ChunkBlockStore store = chunkStores.get(key.toChunkKey());
            if (store == null) {
                return null;
            }
            int index = toLocalIndex(key.getX(), key.getY(), key.getZ());
            int paletteId = store.getPaletteId(index);
            if (paletteId == IntIntHashMap.NO_VALUE) {
                return null;
            }
            return new TrackedBlock(key, palette.get(paletteId));
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getTrackedCount() {
        lock.readLock().lock();
        try {
            return trackedBlockCount;
        } finally {
            lock.readLock().unlock();
        }
    }

    public void purgeWorld(World world) {
        lock.writeLock().lock();
        try {
            String worldName = world.getName();
            Iterator<Map.Entry<ChunkKey, ChunkBlockStore>> iterator = chunkStores.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<ChunkKey, ChunkBlockStore> entry = iterator.next();
                if (!Objects.equals(entry.getKey().getWorldName(), worldName)) {
                    continue;
                }
                trackedBlockCount -= entry.getValue().size();
                iterator.remove();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private ChunkBlockStore getOrCreateChunkStore(ChunkKey chunkKey) {
        return chunkStores.computeIfAbsent(chunkKey, unused -> new ChunkBlockStore());
    }

    private int toLocalIndex(int blockX, int blockY, int blockZ) {
        int localX = blockX & LOCAL_COORD_MASK;
        int localZ = blockZ & LOCAL_COORD_MASK;
        return ((blockY + BLOCK_INDEX_Y_OFFSET) << 8) | (localZ << 4) | localX;
    }
}
