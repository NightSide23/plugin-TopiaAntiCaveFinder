package org.topiacube.topiaAntiCaveFinder.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.topiacube.topiaAntiCaveFinder.player.PlayerViewService;

public final class PlayerActivityListener implements Listener {

    private final PlayerViewService viewService;

    public PlayerActivityListener(PlayerViewService viewService) {
        this.viewService = viewService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        viewService.initializePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        viewService.removePlayer(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangeWorld(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        viewService.resetPlayer(player);
        viewService.initializePlayer(player);
    }
}
