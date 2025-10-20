package org.topiacube.topiaAntiCaveFinder.listener;

import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.topiacube.topiaAntiCaveFinder.config.PluginConfig;
import org.topiacube.topiaAntiCaveFinder.mask.CaveMaskManager;
import org.topiacube.topiaAntiCaveFinder.mask.TrackedBlock;
import org.topiacube.topiaAntiCaveFinder.mask.BlockKey;
import org.topiacube.topiaAntiCaveFinder.player.PlayerViewService;
import org.topiacube.topiaAntiCaveFinder.mask.MaskPaletteResolver;

import java.util.List;

public final class BlockActivityListener implements Listener {

    private final PluginConfig config;
    private final CaveMaskManager maskManager;
    private final PlayerViewService viewService;

    public BlockActivityListener(PluginConfig config, CaveMaskManager maskManager, PlayerViewService viewService) {
        this.config = config;
        this.maskManager = maskManager;
        this.viewService = viewService;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (config.isWorldExcluded(block.getWorld().getName())) {
            maskManager.untrackBlock(block);
            return;
        }
        BlockKey key = BlockKey.from(block);
        if (shouldTrack(block)) {
            maskManager.trackBlock(block);
            if (viewService != null) {
                viewService.registerExcavatedBlock(event.getPlayer(), block);
            }
        } else {
            maskManager.untrackBlock(block);
            if (viewService != null) {
                viewService.invalidateBlock(key);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlockPlaced();
        if (config.isWorldExcluded(block.getWorld().getName())) {
            maskManager.untrackBlock(block);
            return;
        }
        BlockKey key = BlockKey.from(block);
        TrackedBlock existing = maskManager.get(key);
        if (existing != null) {
            if (event.getBlockPlaced().getBlockData().matches(existing.getOriginalData())) {
                maskManager.untrackBlock(key);
                if (viewService != null) {
                    viewService.invalidateBlock(key);
                }
            } else if (viewService != null) {
                viewService.registerExcavatedBlock(event.getPlayer(), block);
            }
            return;
        }
        if (!shouldTrack(block)) {
            maskManager.untrackBlock(key);
            if (viewService != null) {
                viewService.invalidateBlock(key);
            }
            return;
        }

        BlockData palette = MaskPaletteResolver.resolveFromNeighbors(block, config);
        if (palette != null) {
            maskManager.trackBlock(block, palette);
        }

        if (viewService != null) {
            viewService.registerExcavatedBlock(event.getPlayer(), block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        trackBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        trackBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (viewService == null) {
            return;
        }
        org.bukkit.Chunk chunk = event.getChunk();
        String worldName = chunk.getWorld().getName();
        if (config.isWorldExcluded(worldName)) {
            return;
        }
        if (!maskManager.hasTrackedBlocks(worldName, chunk.getX(), chunk.getZ())) {
            return;
        }
        viewService.handleChunkLoad(chunk);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        event.getBlocks().forEach(block -> {
            BlockKey key = BlockKey.from(block);
            maskManager.untrackBlock(key);
            if (viewService != null) {
                viewService.invalidateBlock(key);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        event.getBlocks().forEach(block -> {
            BlockKey key = BlockKey.from(block);
            maskManager.untrackBlock(key);
            if (viewService != null) {
                viewService.invalidateBlock(key);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldUnload(WorldUnloadEvent event) {
        maskManager.purgeWorld(event.getWorld());
    }

    private void trackBlocks(List<Block> blocks) {
        for (Block block : blocks) {
            BlockKey key = BlockKey.from(block);
            if (shouldTrack(block)) {
                maskManager.trackBlock(block);
            } else {
                maskManager.untrackBlock(key);
                if (viewService != null) {
                    viewService.invalidateBlock(key);
                }
            }
        }
    }

    private boolean shouldTrack(Block block) {
        return config.shouldTrackBlock(block.getWorld(), block.getType());
    }
}
