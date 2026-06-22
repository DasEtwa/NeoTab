package de.NeoTab.neotab;

import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public final class NearestPlayerModule implements ActionBarModule {
    private static final String SOURCE = "nearest-player";

    private final NeoTab plugin;
    private final ConfigManager configManager;
    private final ActionBarService actionBarService;
    private final ActionBarTextFormatter formatter;

    private BukkitTask task;

    public NearestPlayerModule(NeoTab plugin, ConfigManager configManager, ActionBarService actionBarService, ActionBarTextFormatter formatter) {
        this.plugin = plugin;
        this.configManager = configManager;
        this.actionBarService = actionBarService;
        this.formatter = formatter;
    }

    @Override
    public void start() {
        stopTask();
        formatter.refresh();
        ConfigManager.NearestPlayerActionBarConfig config = configManager.getActionBarConfig().nearestPlayer();
        if (!config.enabled()) {
            actionBarService.clearSource(SOURCE);
            return;
        }

        long intervalTicks = Math.max(40L, config.checkIntervalTicks());
        task = new BukkitRunnable() {
            @Override
            public void run() {
                checkPlayers();
            }
        }.runTaskTimer(plugin, intervalTicks, intervalTicks);
    }

    @Override
    public void stop() {
        stopTask();
        actionBarService.clearSource(SOURCE);
    }

    private void checkPlayers() {
        ConfigManager.NearestPlayerActionBarConfig config = configManager.getActionBarConfig().nearestPlayer();
        if (!config.enabled()) {
            stop();
            return;
        }

        double maxDistanceSquared = (double) config.maxDistance() * (double) config.maxDistance();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location playerLocation = player.getLocation();
            Player nearest = null;
            double nearestDistanceSquared = Double.MAX_VALUE;
            for (Player candidate : Bukkit.getOnlinePlayers()) {
                if (candidate.equals(player)) {
                    continue;
                }
                Location candidateLocation = candidate.getLocation();
                boolean sameWorld = candidate.getWorld().equals(player.getWorld());
                if (config.sameWorldOnly() && !sameWorld) {
                    continue;
                }

                double distanceSquared = sameWorld
                    ? playerLocation.distanceSquared(candidateLocation)
                    : distanceSquared(playerLocation, candidateLocation);
                if (distanceSquared > maxDistanceSquared || distanceSquared >= nearestDistanceSquared) {
                    continue;
                }
                nearest = candidate;
                nearestDistanceSquared = distanceSquared;
            }

            if (nearest == null) {
                actionBarService.clear(player, SOURCE);
                continue;
            }

            long distance = Math.round(Math.sqrt(nearestDistanceSquared));
            actionBarService.submit(
                player,
                SOURCE,
                formatter.render(
                    player,
                    config.text(),
                    Map.of("player", nearest.getName(), "player_name", nearest.getName(), "distance", Long.toString(distance)),
                    "actionbar.nearest-player"
                ),
                ActionBarService.PRIORITY_NEAREST_PLAYER,
                Math.max(3L, config.checkIntervalTicks() / 20L + 2L) * 1000L
            );
        }
    }

    private void stopTask() {
        if (task == null) {
            return;
        }
        task.cancel();
        task = null;
    }

    private double distanceSquared(Location left, Location right) {
        double dx = left.getX() - right.getX();
        double dy = left.getY() - right.getY();
        double dz = left.getZ() - right.getZ();
        return dx * dx + dy * dy + dz * dz;
    }
}
