package org.topiacube.topiaAntiCaveFinder.mask;

import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class BlockPalette {

    private final Map<String, Integer> indexByState = new HashMap<>();
    private final List<BlockData> states = new ArrayList<>();
    private final List<String> serializedStates = new ArrayList<>();

    void clear() {
        indexByState.clear();
        states.clear();
        serializedStates.clear();
    }

    int getOrCreateId(BlockData data) {
        String serialized = data.getAsString();
        Integer existing = indexByState.get(serialized);
        if (existing != null) {
            return existing;
        }
        BlockData stored = Bukkit.createBlockData(serialized);
        int id = states.size();
        states.add(stored);
        serializedStates.add(serialized);
        indexByState.put(serialized, id);
        return id;
    }

    BlockData get(int id) {
        return states.get(id);
    }

    String getSerialized(int id) {
        return serializedStates.get(id);
    }
}
