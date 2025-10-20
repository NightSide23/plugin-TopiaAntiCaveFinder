package org.topiacube.topiaAntiCaveFinder;

import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.topiacube.topiaAntiCaveFinder.config.PluginConfig;
import org.topiacube.topiaAntiCaveFinder.listener.BlockActivityListener;
import org.topiacube.topiaAntiCaveFinder.listener.PlayerActivityListener;
import org.topiacube.topiaAntiCaveFinder.mask.CaveMaskManager;
import org.topiacube.topiaAntiCaveFinder.player.PlayerViewService;

public final class TopiaAntiCaveFinder extends JavaPlugin {

    private CaveMaskManager maskManager;
    private PlayerViewService viewService;
    private PluginConfig pluginConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        this.pluginConfig = new PluginConfig(this);
        pluginConfig.reload(getConfig());

        this.maskManager = new CaveMaskManager(getDataFolder());
        maskManager.load();

        boolean legacyServer = isLegacyMaskingTarget();
        boolean entityMaskingEnabled = !legacyServer;

        this.viewService = new PlayerViewService(this, maskManager, pluginConfig, entityMaskingEnabled);
        viewService.start();
        if (!entityMaskingEnabled) {
            getLogger().info("Entity masking disabled on this server version.");
        }

        PluginManager pluginManager = getServer().getPluginManager();
        pluginManager.registerEvents(new BlockActivityListener(pluginConfig, maskManager, viewService), this);
        pluginManager.registerEvents(new PlayerActivityListener(viewService), this);

        Bukkit.getOnlinePlayers().forEach(viewService::initializePlayer);
        getLogger().info("TopiaAntiCaveFinder enabled. Tracking " + maskManager.getTrackedCount() + " artificial cave blocks.");
    }

    @Override
    public void onDisable() {
        if (viewService != null) {
            viewService.shutdown();
        }
        if (maskManager != null) {
            maskManager.save();
        }
        HandlerList.unregisterAll(this);
    }

    public void reloadRuntimeConfig() {
        reloadConfig();
        pluginConfig.reload(getConfig());
    }

    private boolean isLegacyMaskingTarget() {
        String version = Bukkit.getBukkitVersion();
        if (version == null || version.isEmpty()) {
            return false;
        }
        String base = version.split("-")[0];
        String[] parts = base.split("\\.");
        try {
            int major = parts.length > 0 ? Integer.parseInt(parts[0]) : 0;
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            return major < 1 || (major == 1 && minor <= 16);
        } catch (NumberFormatException ex) {
            return false;
        }
    }

}
