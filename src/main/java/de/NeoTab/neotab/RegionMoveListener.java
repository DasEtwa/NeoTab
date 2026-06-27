package de.NeoTab.neotab;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class RegionMoveListener implements Listener {
    private final RegionManager regionManager;

    public RegionMoveListener(RegionManager regionManager) {
        this.regionManager = regionManager;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!changedBlockOrWorld(event.getFrom(), event.getTo())) {
            return;
        }
        regionManager.handleMove(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        regionManager.handleQuit(event.getPlayer());
    }

    private boolean changedBlockOrWorld(Location from, Location to) {
        if (to == null) {
            return false;
        }
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return true;
        }
        return from.getBlockX() != to.getBlockX()
            || from.getBlockY() != to.getBlockY()
            || from.getBlockZ() != to.getBlockZ();
    }
}
