package org.topiacube.topiaAntiCaveFinder.mask;

import org.bukkit.block.data.BlockData;

public final class TrackedBlock {
    private final BlockKey key;
    private final BlockData originalData;

    public TrackedBlock(BlockKey key, BlockData originalData) {
        this.key = key;
        this.originalData = originalData;
    }

    public BlockKey getKey() {
        return key;
    }

    public BlockData getOriginalData() {
        return originalData;
    }
}
