package de.NeoTab.neotab;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;

public final class RegionMoveListener implements Listener {
    private final RegionManager regionManager;

    public RegionMoveListener(RegionManager regionManager) {
        this.regionManager = regionManager;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!changedBlockOrWorld(event.getFrom(), event.getTo())) {
            return;
        }
        regionManager.handleMove(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        regionManager.handleMove(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        regionManager.handleMove(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        regionManager.handleMove(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        regionManager.handleRespawn(event.getPlayer(), event.getRespawnLocation());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        regionManager.handleQuit(event.getPlayer());
    }

    static boolean changedBlockOrWorld(Location from, Location to) {
        if (from == null || to == null) {
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
